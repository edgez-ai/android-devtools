package ai.edgez.androiddevtools;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Keep;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public final class NativeBridge {
    private static final String TAG = "AndroidDevTools";
    private static final Object SCRCPY_LOCK = new Object();
    private static volatile Context applicationContext;
    private static volatile boolean scrcpyServerProvided;

    static {
        System.loadLibrary("edgejoin_jni");
    }

    private NativeBridge() {
    }

    static void initialize(Context context) {
        applicationContext = context.getApplicationContext();
        provideScrcpyServer(applicationContext);
    }

    static native String nativeCreateIdentity(String name);

    static native String nativeStartClient(String configJson);

    static native String nativeStopClient();

    static native String nativeProvideScrcpyJar(byte[] bytes);

    static native String nativePairWireless(
            String pairHost, int pairPort, String code, String debugHost, int debugPort);

    static native String nativeSetAdbProxyTarget(String host, int port);

    private static void provideScrcpyServer(Context context) {
        if (scrcpyServerProvided) {
            return;
        }
        synchronized (SCRCPY_LOCK) {
            if (scrcpyServerProvided) {
                return;
            }
            try (InputStream input = context.getAssets().open("scrcpy/scrcpy-server.jar");
                 ByteArrayOutputStream output = new ByteArrayOutputStream(128 * 1024)) {
                byte[] buffer = new byte[8 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                byte[] server = output.toByteArray();
                String response = nativeProvideScrcpyJar(server);
                JSONObject result = new JSONObject(response);
                if (!result.optBoolean("ok")) {
                    throw new IllegalStateException(result.optString("error", response));
                }
                scrcpyServerProvided = true;
                Log.i(TAG, "Provided scrcpy server to native client, bytes=" + server.length);
            } catch (Throwable throwable) {
                // Leave the flag false so a later Activity/service initialization retries.
                Log.w(TAG, "Unable to provide scrcpy server to native client", throwable);
            }
        }
    }

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
