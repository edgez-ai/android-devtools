package ai.edgez.androiddevtools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AndroidDevTools";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            boolean started = ProxyService.startIfConfigured(context);
            Log.i(TAG, "Auto-start after " + intent.getAction() + ": started=" + started);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to restart proxy after " + intent.getAction(), exception);
        }
    }
}
