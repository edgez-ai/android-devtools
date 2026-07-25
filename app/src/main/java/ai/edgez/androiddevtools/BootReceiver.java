package ai.edgez.androiddevtools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AndroidDevTools";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ConfigStore.isConfigured(context)) {
            return;
        }
        try {
            ProxyService.start(context);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to restart proxy after " + intent.getAction(), exception);
        }
    }
}

