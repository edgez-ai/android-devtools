package ai.edgez.androiddevtools;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

final class ProxyStatus {
    static final String ACTION_CHANGED = "ai.edgez.androiddevtools.PROXY_STATUS_CHANGED";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_DETAIL = "detail";

    static final String DISCONNECTED = "disconnected";
    static final String CONNECTING = "connecting";
    static final String MESH_ONLINE = "mesh_online";
    static final String ADB_ONLINE = "adb_online";
    static final String STOPPING = "stopping";
    static final String ERROR = "error";

    private static final String PREFS = "edgejoin_proxy_status";
    private static final String KEY_STATE = "state";
    private static final String KEY_DETAIL = "detail";

    private ProxyStatus() {
    }

    static void publish(Context context, String state, String detail) {
        String safeDetail = detail == null ? "" : detail;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STATE, state)
                .putString(KEY_DETAIL, safeDetail)
                .apply();
        Intent changed = new Intent(ACTION_CHANGED)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_DETAIL, safeDetail);
        context.sendBroadcast(changed);
    }

    static Snapshot current(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Snapshot(
                preferences.getString(KEY_STATE, DISCONNECTED),
                preferences.getString(KEY_DETAIL, ""));
    }

    static final class Snapshot {
        final String state;
        final String detail;

        Snapshot(String state, String detail) {
            this.state = state == null ? DISCONNECTED : state;
            this.detail = detail == null ? "" : detail;
        }
    }
}
