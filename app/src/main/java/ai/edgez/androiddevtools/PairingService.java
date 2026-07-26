package ai.edgez.androiddevtools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PairingService extends Service {
    private static final String TAG = "AndroidDevTools";
    private static final String CHANNEL_ID = "adb_pairing";
    private static final int NOTIFICATION_ID = 0xE743;
    private static final String ACTION_START = "ai.edgez.androiddevtools.pairing.START";
    private static final String ACTION_REPLY = "ai.edgez.androiddevtools.pairing.REPLY";
    private static final String ACTION_STOP = "ai.edgez.androiddevtools.pairing.STOP";
    private static final String REMOTE_INPUT_KEY = "edgejoin_pair_code";
    private static final String EXTRA_HOST = "pairing_host";
    private static final String EXTRA_PORT = "pairing_port";
    private static final int REQUEST_REPLY = 1;
    private static final int REQUEST_STOP = 2;
    private static final int REQUEST_CONTENT = 3;

    private final AtomicBoolean searching = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Thread searchThread;

    @Override
    public void onCreate() {
        super.onCreate();
        NativeBridge.initialize(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSearch();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REPLY.equals(action)) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            String code = results == null ? "" :
                    String.valueOf(results.getCharSequence(REMOTE_INPUT_KEY, "")).trim();
            String host = intent.getStringExtra(EXTRA_HOST);
            int port = intent.getIntExtra(EXTRA_PORT, 0);
            stopSearch();
            startForegroundCompat(buildWorkingNotification());
            pairFromNotification(code, host == null ? "" : host, port);
            return START_REDELIVER_INTENT;
        }
        if (ACTION_START.equals(action)) {
            startForegroundCompat(buildSearchingNotification());
            startSearch();
            return START_REDELIVER_INTENT;
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopSearch();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startSearch() {
        if (!searching.compareAndSet(false, true)) {
            return;
        }
        searchThread = new Thread(() -> {
            try {
                while (searching.get() && !Thread.currentThread().isInterrupted()) {
                    Endpoint endpoint = WirelessDebugDiscovery.discoverPairing(this, 2_000);
                    if (endpoint != null) {
                        Log.i(TAG, "Wireless pairing endpoint found: " + endpoint.display());
                        getSystemService(NotificationManager.class)
                                .notify(NOTIFICATION_ID, buildInputNotification(endpoint));
                        return;
                    }
                }
            } catch (Throwable throwable) {
                Log.w(TAG, "Pairing endpoint discovery failed", throwable);
                postResult("Pairing discovery failed: " + safeMessage(throwable), true);
            } finally {
                searching.set(false);
            }
        }, "wireless-adb-pairing-search");
        searchThread.start();
    }

    private void stopSearch() {
        searching.set(false);
        Thread thread = searchThread;
        if (thread != null) {
            thread.interrupt();
        }
        searchThread = null;
    }

    private void pairFromNotification(String code, String pairHost, int pairPort) {
        executor.execute(() -> {
            if (code.length() < 6) {
                postResult("The Wireless Debugging pairing code must contain six digits.", true);
                return;
            }
            if (pairHost.trim().isEmpty() || pairPort < 1 || pairPort > 65535) {
                postResult("The Wireless Debugging pairing endpoint is no longer available.", true);
                return;
            }
            try {
                // A device with a new, untrusted key may advertise only the
                // pairing service while the pairing-code dialog is open. Pair
                // first, then wait for Android to publish the connect service.
                String response = NativeBridge.nativePairWireless(
                        pairHost, pairPort, code, "", 0);
                JSONObject result = new JSONObject(response);
                if (!result.optBoolean("ok")) {
                    throw new IllegalStateException(result.optString("error", response));
                }

                Endpoint discovered = discoverConnectAfterPairing();
                Endpoint localAdb = discovered == null
                        ? ConfigStore.loadAdbEndpoint(this)
                        : new Endpoint("127.0.0.1", discovered.port);
                if (localAdb != null) {
                    // The mDNS result supplies the dynamic port, while the
                    // device-local authenticated ADB connection uses loopback.
                    // Samsung may stop advertising the connect service while
                    // the local port remains valid, so retain the stored port.
                    ConfigStore.saveAdbEndpoint(this, localAdb);
                    String targetResponse = NativeBridge.nativeSetAdbProxyTarget(
                            localAdb.host, localAdb.port);
                    Log.i(
                            TAG,
                            (discovered == null
                                    ? "Using stored Wireless Debugging connect endpoint: "
                                    : "Wireless connect endpoint found after pairing: ")
                                    + localAdb.display() + " target=" + targetResponse);
                } else {
                    Log.w(
                            TAG,
                            "Wireless pairing succeeded; connect endpoint is not advertised yet");
                }

                ProxyService.start(this);
                postResult(
                        localAdb == null
                                ? "Wireless Debugging paired. Waiting for its connect endpoint."
                                : "Wireless Debugging paired. The libp2p ADB proxy is starting.",
                        false);
            } catch (Throwable throwable) {
                Log.w(TAG, "Notification pairing failed", throwable);
                postResult("Pairing failed: " + safeMessage(throwable), true);
            }
        });
    }

    private Endpoint discoverConnectAfterPairing() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            Endpoint endpoint = WirelessDebugDiscovery.discoverConnect(this, 8_000);
            if (endpoint != null) {
                return endpoint;
            }
            Log.i(TAG, "Waiting for Wireless Debugging connect endpoint, attempt=" + attempt);
        }
        return null;
    }

    private Notification buildSearchingNotification() {
        return baseBuilder()
                .setContentTitle("Searching for Wireless Debugging")
                .setContentText("Open “Pair device with pairing code” in Android settings.")
                .setOngoing(true)
                .addAction(stopAction())
                .build();
    }

    private Notification buildInputNotification(Endpoint endpoint) {
        return baseBuilder()
                .setContentTitle("Wireless Debugging found")
                .setContentText("Enter the six-digit pairing code here.")
                .setStyle(new Notification.BigTextStyle()
                        .bigText("Enter the six-digit pairing code in this notification."))
                .setOngoing(true)
                .addAction(replyAction(endpoint))
                .addAction(stopAction())
                .build();
    }

    private Notification buildWorkingNotification() {
        return baseBuilder()
                .setContentTitle("Pairing Wireless Debugging")
                .setContentText("Authenticating the local ADB connection…")
                .setOngoing(true)
                .build();
    }

    private Notification.Builder baseBuilder() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(ai.edgez.androiddevtools.R.drawable.ic_launcher)
                .setOnlyAlertOnce(true)
                .setContentIntent(appContentIntent());
    }

    @SuppressWarnings("deprecation")
    private Notification.Action replyAction(Endpoint endpoint) {
        RemoteInput remoteInput = new RemoteInput.Builder(REMOTE_INPUT_KEY)
                .setLabel("Pairing code")
                .build();
        Intent replyIntent = new Intent(this, PairingService.class)
                .setAction(ACTION_REPLY)
                .putExtra(EXTRA_HOST, endpoint.host)
                .putExtra(EXTRA_PORT, endpoint.port);
        PendingIntent pendingIntent = PendingIntent.getForegroundService(
                this,
                REQUEST_REPLY,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        return new Notification.Action.Builder(0, "Enter pairing code", pendingIntent)
                .addRemoteInput(remoteInput)
                .build();
    }

    @SuppressWarnings("deprecation")
    private Notification.Action stopAction() {
        PendingIntent pendingIntent = PendingIntent.getService(
                this,
                REQUEST_STOP,
                new Intent(this, PairingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(0, "Stop", pendingIntent).build();
    }

    private PendingIntent appContentIntent() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this,
                REQUEST_CONTENT,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void postResult(String message, boolean error) {
        stopSearch();
        stopForeground(STOP_FOREGROUND_DETACH);
        Notification result = baseBuilder()
                .setContentTitle(error ? "Wireless Debugging pairing failed" : "Wireless Debugging paired")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, result);
        stopSelf();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Wireless ADB pairing",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Enter the Wireless Debugging pairing code from a notification");
        channel.setSound(null, null);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    static void start(Context context) {
        Intent intent = new Intent(context, PairingService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
