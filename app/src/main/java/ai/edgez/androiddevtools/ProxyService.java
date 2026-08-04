package ai.edgez.androiddevtools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ProxyService extends Service {
    static final String ACTION_START = "ai.edgez.androiddevtools.START";
    static final String ACTION_STOP = "ai.edgez.androiddevtools.STOP";
    private static final String TAG = "AndroidDevTools";
    private static final String CHANNEL_ID = "adb_proxy";
    private static final int NOTIFICATION_ID = 4101;
    private static final long WIRELESS_DEBUG_PROMPT_INTERVAL_MS = 30_000L;
    private static final AtomicLong LAST_WIRELESS_DEBUG_PROMPT_MS = new AtomicLong();
    private static final AtomicBoolean SERVICE_RUNNING = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean startScheduled = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile boolean failed;
    private volatile UsbIpServer usbIpServer;

    @Override
    public void onCreate() {
        super.onCreate();
        SERVICE_RUNNING.set(true);
        NativeBridge.initialize(this);
        createNotificationChannel();
        ProxyStatus.publish(this, ProxyStatus.CONNECTING, "");
        Notification notification = buildNotification(getString(R.string.proxy_starting));
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!stopping && startScheduled.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    startProxy();
                } finally {
                    startScheduled.set(false);
                }
            });
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        stopUsbIpServer();
        stopNativeClient();
        executor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        SERVICE_RUNNING.set(false);
        if (!failed) {
            ProxyStatus.publish(this, ProxyStatus.DISCONNECTED, "");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startProxy() {
        if (stopping) {
            return;
        }
        try {
            if (!ConfigStore.isConfigured(this)) {
                Log.i(TAG, "No stored libp2p config; stopping proxy service");
                ProxyStatus.publish(this, ProxyStatus.DISCONNECTED, "");
                stopSelf();
                return;
            }

            startUsbIpServer();
            Log.i(TAG, "Libp2p startup: loading stored config");
            Endpoint endpoint = WirelessDebugDiscovery.discoverConnect(this, 5_000);
            if (endpoint != null) {
                ConfigStore.saveAdbEndpoint(this, endpoint);
            } else {
                endpoint = ConfigStore.loadAdbEndpoint(this);
            }

            if (stopping || Thread.currentThread().isInterrupted()) {
                return;
            }
            JSONObject runtimeConfig = new JSONObject(ConfigStore.clientConfig(this));
            runtimeConfig.put("usbip_socket_name", usbIpServer.socketName());
            String response = NativeBridge.nativeStartClient(runtimeConfig.toString());
            JSONObject result = new JSONObject(response);
            if (!result.optBoolean("ok")) {
                throw new IllegalStateException(result.optString("error", response));
            }
            if (stopping || Thread.currentThread().isInterrupted()) {
                stopNativeClient();
                return;
            }
            String state = result.optString("state", "running");
            Log.i(
                    TAG,
                    "Libp2p startup complete: state=" + state
                            + " peer=" + result.optString("peer_id", ConfigStore.peerId(this))
                            + " relayBooked=" + result.optBoolean("relay_booked"));
            if (endpoint != null) {
                startLocalScrcpy();
            }
            if (endpoint == null) {
                ProxyStatus.publish(this, ProxyStatus.MESH_ONLINE, "");
                updateNotification(getString(
                        R.string.proxy_online_no_adb, ConfigStore.peerId(this)));
            } else {
                ProxyStatus.publish(this, ProxyStatus.ADB_ONLINE,
                        getString(R.string.proxy_endpoint_format, endpoint.port));
                updateNotification(getString(
                        R.string.proxy_online, ConfigStore.peerId(this), endpoint.port));
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "Unable to start proxy", throwable);
            String message = safeMessage(throwable);
            failed = true;
            ProxyStatus.publish(this, ProxyStatus.ERROR, message);
            stopSelf();
        }
    }

    private void requestStop() {
        if (stopping) {
            return;
        }
        stopping = true;
        ProxyStatus.publish(this, ProxyStatus.STOPPING, "");
        updateNotification(getString(R.string.proxy_stopping));
        executor.execute(() -> {
            stopNativeClient();
            stopSelf();
        });
    }

    private void stopNativeClient() {
        try {
            String response = NativeBridge.nativeStopClient();
            Log.i(TAG, "Libp2p client stopped: " + response);
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to stop native client", throwable);
        }
    }

    private void startUsbIpServer() throws Exception {
        if (usbIpServer != null) {
            return;
        }
        UsbIpServer server = new UsbIpServer(this);
        server.start();
        usbIpServer = server;
        Log.i(TAG, "USB/IP stream available on libp2p target port "
                + UsbIpServer.ROUTE_PORT
                + "; socket=@" + server.socketName()
                + "; exported devices=" + server.exportedDevices());
    }

    private void stopUsbIpServer() {
        UsbIpServer server = usbIpServer;
        usbIpServer = null;
        if (server != null) {
            server.close();
        }
    }

    static void start(Context context) {
        Intent intent = new Intent(context, ProxyService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    static void stop(Context context) {
        boolean stopped = context.stopService(new Intent(context, ProxyService.class));
        if (!stopped) {
            ProxyStatus.publish(context, ProxyStatus.DISCONNECTED, "");
        }
    }

    static boolean isRunning() {
        return SERVICE_RUNNING.get();
    }

    static void refreshAdbTarget(Context context) {
        Thread refreshThread = new Thread(() -> {
            Endpoint endpoint = WirelessDebugDiscovery.discoverConnect(context, 8_000);
            if (endpoint == null) {
                Log.w(TAG, "Native reconnect requested but no local ADB endpoint was found");
                if (!isProxyConnected(context)) {
                    requestWirelessDebugPrompt(context);
                } else {
                    Log.i(TAG, "Mesh proxy is connected; suppressing Wireless Debugging prompt");
                }
                return;
            }
            ConfigStore.saveAdbEndpoint(context, endpoint);
            String response = NativeBridge.nativeSetAdbProxyTarget("127.0.0.1", endpoint.port);
            Log.i(TAG, "Updated native ADB target to " + endpoint.display() + ": " + response);
            startLocalScrcpy();
        }, "adb-target-refresh");
        refreshThread.start();
    }

    private static boolean isProxyConnected(Context context) {
        String state = ProxyStatus.current(context).state;
        return ProxyStatus.CONNECTING.equals(state)
                || ProxyStatus.MESH_ONLINE.equals(state)
                || ProxyStatus.ADB_ONLINE.equals(state);
    }

    private static void startLocalScrcpy() {
        try {
            String response = NativeBridge.nativeStartScrcpy();
            JSONObject result = new JSONObject(response);
            if (result.optBoolean("ok")) {
                Log.i(TAG, "Local scrcpy startup complete: " + response);
            } else {
                Log.w(
                        TAG,
                        "Local scrcpy startup deferred: "
                                + result.optString("error", response));
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Local scrcpy startup failed", throwable);
        }
    }

    private static void requestWirelessDebugPrompt(Context context) {
        long now = android.os.SystemClock.elapsedRealtime();
        long previous = LAST_WIRELESS_DEBUG_PROMPT_MS.get();
        if ((previous != 0L && now - previous < WIRELESS_DEBUG_PROMPT_INTERVAL_MS)
                || !LAST_WIRELESS_DEBUG_PROMPT_MS.compareAndSet(previous, now)) {
            return;
        }
        try {
            MainActivity.requestWirelessDebugPrompt(context.getApplicationContext());
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to show Wireless Debugging prompt", exception);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.proxy_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.proxy_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(getString(R.string.edgez_app_name))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
