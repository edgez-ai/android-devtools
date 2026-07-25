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

public final class ProxyService extends Service {
    static final String ACTION_START = "ai.edgez.androiddevtools.START";
    static final String ACTION_STOP = "ai.edgez.androiddevtools.STOP";
    private static final String TAG = "AndroidDevTools";
    private static final String CHANNEL_ID = "adb_proxy";
    private static final int NOTIFICATION_ID = 4101;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean stopping;

    @Override
    public void onCreate() {
        super.onCreate();
        NativeBridge.initialize(this);
        createNotificationChannel();
        Notification notification = buildNotification("Starting libp2p ADB proxy…");
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
            stopping = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        executor.execute(this::startProxy);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        executor.execute(() -> {
            try {
                NativeBridge.nativeStopClient();
            } catch (Throwable throwable) {
                Log.w(TAG, "Unable to stop native client", throwable);
            }
        });
        executor.shutdown();
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
            Endpoint endpoint = WirelessDebugDiscovery.discoverConnect(this, 5_000);
            if (endpoint != null) {
                ConfigStore.saveAdbEndpoint(this, endpoint);
            } else {
                endpoint = ConfigStore.loadAdbEndpoint(this);
            }
            if (endpoint == null) {
                updateNotification("Enable Wireless debugging, then refresh in the app");
                return;
            }

            String response = NativeBridge.nativeStartClient(ConfigStore.clientConfig(this));
            JSONObject result = new JSONObject(response);
            if (!result.optBoolean("ok")) {
                throw new IllegalStateException(result.optString("error", response));
            }
            updateNotification(
                    "ADB proxy online • " + ConfigStore.peerId(this)
                            + " • local adbd :" + endpoint.port);
        } catch (Throwable throwable) {
            Log.e(TAG, "Unable to start proxy", throwable);
            updateNotification("Proxy error: " + safeMessage(throwable));
        }
    }

    static void start(Context context) {
        Intent intent = new Intent(context, ProxyService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    static void stop(Context context) {
        context.startService(new Intent(context, ProxyService.class).setAction(ACTION_STOP));
    }

    static void refreshAdbTarget(Context context) {
        Thread refreshThread = new Thread(() -> {
            Endpoint endpoint = WirelessDebugDiscovery.discoverConnect(context, 8_000);
            if (endpoint == null) {
                Log.w(TAG, "Native reconnect requested but Wireless Debugging was not discovered");
                return;
            }
            ConfigStore.saveAdbEndpoint(context, endpoint);
            String response = NativeBridge.nativeSetAdbProxyTarget("127.0.0.1", endpoint.port);
            Log.i(TAG, "Updated native ADB target to " + endpoint.display() + ": " + response);
        }, "adb-target-refresh");
        refreshThread.start();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Remote ADB proxy", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the libp2p ADB tunnel available to the Flutter workspace");
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
                .setContentTitle("Android DevTools")
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
