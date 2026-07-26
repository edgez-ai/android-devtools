package ai.edgez.androiddevtools;

import android.app.Application;
import android.util.Log;

public final class AndroidDevToolsApp extends Application {
    private static final String TAG = "AndroidDevTools";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            boolean started = ProxyService.startIfConfigured(this);
            Log.i(TAG, "Application startup: libp2p service started=" + started);
        } catch (Throwable throwable) {
            // A boot/update receiver or the foreground activity can retry startup.
            Log.w(TAG, "Application startup could not start libp2p service", throwable);
        }
    }
}
