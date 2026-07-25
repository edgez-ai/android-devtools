package ai.edgez.androiddevtools;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Keep;

public final class NativeBridge {
    private static final String TAG = "AndroidDevTools";
    private static volatile Context applicationContext;

    static {
        System.loadLibrary("edgejoin_jni");
    }

    private NativeBridge() {
    }

    static void initialize(Context context) {
        applicationContext = context.getApplicationContext();
    }

    static native String nativeCreateIdentity(String name);

    static native String nativeStartClient(String configJson);

    static native String nativeStopClient();

    static native String nativePairWireless(
            String pairHost, int pairPort, String code, String debugHost, int debugPort);

    static native String nativeSetAdbProxyTarget(String host, int port);

    @Keep
    public static void onAdbUnreachable() {
        Context context = applicationContext;
        if (context == null) {
            Log.w(TAG, "Native client reported an unreachable ADB target before initialization");
            return;
        }
        ProxyService.refreshAdbTarget(context);
    }
}

