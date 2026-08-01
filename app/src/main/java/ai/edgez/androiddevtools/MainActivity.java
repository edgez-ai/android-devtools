package ai.edgez.androiddevtools;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 10;
    private static final String EXTRA_PROMPT_WIRELESS_DEBUG =
            "ai.edgez.androiddevtools.extra.PROMPT_WIRELESS_DEBUG";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver proxyStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateProxyStatus(
                    intent.getStringExtra(ProxyStatus.EXTRA_STATE),
                    intent.getStringExtra(ProxyStatus.EXTRA_DETAIL));
        }
    };
    private AlertDialog wirelessDebugDialog;
    private Button expoGoButton;
    private TextView peerIdText;
    private TextView permissionStatusText;
    private TextView proxyStateText;
    private TextView proxyDetailText;
    private TextView statusText;
    private View proxyStateDot;
    private boolean receiverRegistered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        refreshPeerId();
        refreshPermissionStatus();
        refreshProxyStatus();
        requestRuntimePermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerProxyStatusReceiver();
        refreshProxyStatus();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(proxyStatusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
        if (expoGoButton != null) {
            expoGoButton.setText(ExpoGoLauncher.isInstalled(this)
                    ? R.string.open_expo_go : R.string.install_expo_go);
        }
        maybeShowWirelessDebugDialog(consumeWirelessDebugPrompt());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeShowWirelessDebugDialog(consumeWirelessDebugPrompt());
    }

    @Override
    protected void onDestroy() {
        if (wirelessDebugDialog != null) {
            wirelessDebugDialog.dismiss();
            wirelessDebugDialog = null;
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(28));
        content.setBackgroundColor(color(R.color.edgez_background));

        LinearLayout hero = verticalPanel(22, gradient(
                color(R.color.edgez_blue_dark), color(R.color.edgez_blue), 24));
        TextView eyebrow = text(getString(R.string.hero_eyebrow), 12, Color.WHITE);
        eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.08f);
        hero.addView(eyebrow);
        TextView title = text(getString(R.string.hero_title), 30, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hero.addView(title, margins(0, 8, 0, 0));
        TextView subtitle = text(getString(R.string.hero_subtitle), 15, 0xFFE4EEFF);
        subtitle.setLineSpacing(0, 1.12f);
        hero.addView(subtitle, margins(0, 8, 0, 0));
        content.addView(hero, margins(0, 0, 0, 14));

        LinearLayout connection = card();
        connection.addView(cardTitle(R.string.connection_status));
        peerIdText = text(getString(R.string.peer_not_joined), 13, color(R.color.edgez_text_muted));
        peerIdText.setTextIsSelectable(true);
        connection.addView(peerIdText, margins(0, 6, 0, 14));
        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        proxyStateDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotParams.setMargins(0, 0, dp(10), 0);
        stateRow.addView(proxyStateDot, dotParams);
        LinearLayout stateCopy = new LinearLayout(this);
        stateCopy.setOrientation(LinearLayout.VERTICAL);
        proxyStateText = text(getString(R.string.proxy_state_disconnected), 16,
                color(R.color.edgez_text));
        proxyStateText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        stateCopy.addView(proxyStateText);
        proxyDetailText = text(getString(R.string.proxy_detail_disconnected), 13,
                color(R.color.edgez_text_muted));
        stateCopy.addView(proxyDetailText, margins(0, 2, 0, 0));
        stateRow.addView(stateCopy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        connection.addView(stateRow);
        content.addView(connection, margins(0, 0, 0, 12));

        LinearLayout permissions = card();
        permissions.addView(stepTitle("1", R.string.permissions_title));
        permissions.addView(description(R.string.permissions_description));
        permissionStatusText = text("", 13, color(R.color.edgez_text_muted));
        permissions.addView(permissionStatusText, margins(0, 12, 0, 4));
        permissions.addView(actionButton(R.string.grant_permissions,
                view -> requestRequiredPermissions(), false));
        content.addView(permissions, margins(0, 0, 0, 12));

        LinearLayout join = card();
        join.addView(stepTitle("2", R.string.join_title));
        join.addView(description(R.string.join_description));
        join.addView(actionButton(R.string.scan_qr_join, view -> scanPairingQr(), true));
        content.addView(join, margins(0, 0, 0, 12));

        LinearLayout wireless = card();
        wireless.addView(stepTitle("3", R.string.wireless_title));
        wireless.addView(description(R.string.wireless_description));
        wireless.addView(actionButton(R.string.pair_notification,
                view -> beginNotificationPairing(), false));
        content.addView(wireless, margins(0, 0, 0, 12));

        LinearLayout tools = card();
        tools.addView(cardTitle(R.string.developer_tools_title));
        tools.addView(description(R.string.expo_description));
        expoGoButton = actionButton(
                ExpoGoLauncher.isInstalled(this) ? R.string.open_expo_go : R.string.install_expo_go,
                view -> showStatus(ExpoGoLauncher.openOrInstall(this)), false);
        tools.addView(expoGoButton);
        tools.addView(divider(), margins(0, 16, 0, 12));
        tools.addView(description(R.string.proxy_description));
        LinearLayout proxyActions = new LinearLayout(this);
        proxyActions.setOrientation(LinearLayout.HORIZONTAL);
        Button start = actionButton(R.string.start_proxy, view -> startProxy(), true);
        Button stop = actionButton(R.string.stop_proxy, view -> {
            ProxyService.stop(this);
            showStatus(getString(R.string.status_proxy_stop_requested));
        }, false);
        proxyActions.addView(start, weightedButtonMargins(0, 8));
        proxyActions.addView(stop, weightedButtonMargins(8, 0));
        tools.addView(proxyActions);
        content.addView(tools, margins(0, 0, 0, 12));

        statusText = text(getString(R.string.status_ready), 13, color(R.color.edgez_text));
        statusText.setTextIsSelectable(true);
        statusText.setPadding(dp(14), dp(13), dp(14), dp(13));
        statusText.setBackground(roundRect(color(R.color.edgez_status_background), 14));
        content.addView(statusText);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        return scroll;
    }

    private void scanPairingQr() {
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, options);
        showStatus(getString(R.string.status_scan_prompt));
        scanner.startScan()
                .addOnSuccessListener(barcode -> joinFromPairingQr(barcode.getRawValue()))
                .addOnCanceledListener(() -> showStatus(getString(R.string.status_scan_canceled)))
                .addOnFailureListener(error -> showStatus(
                        getString(R.string.status_scan_failed, safeMessage(error))));
    }

    private void joinFromPairingQr(String rawValue) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            showStatus(getString(R.string.status_qr_empty));
            return;
        }
        try {
            JSONObject payload = new JSONObject(rawValue);
            String serial = payload.optString("serial_number", "").trim();
            String joinKey = payload.optString("join_key", "").trim();
            if (serial.isEmpty() || joinKey.isEmpty()) {
                showStatus(getString(R.string.status_qr_fields_missing));
                return;
            }
            joinNetwork(serial, joinKey);
        } catch (JSONException error) {
            showStatus(getString(R.string.status_qr_invalid));
        }
    }

    private void joinNetwork(String serial, String joinKey) {
        runTask(getString(R.string.status_joining), () -> {
            String peerId = ConfigStore.join(this, ConfigStore.DEFAULT_JOIN_ENDPOINT, serial,
                    joinKey, Build.MANUFACTURER + " " + Build.MODEL);
            runOnUiThread(() -> {
                refreshPeerId();
                ProxyService.start(this);
                showStatus(getString(R.string.status_joined, peerId));
            });
        });
    }

    private void beginNotificationPairing() {
        if (!hasRuntimePermissions()) {
            showStatus(getString(R.string.status_permissions_before_pairing));
            requestRuntimePermissions();
            return;
        }
        if (!ConfigStore.isConfigured(this)) {
            showStatus(getString(R.string.status_join_before_pairing));
            return;
        }
        PairingService.start(this);
        openWirelessDebugging();
        showStatus(getString(R.string.status_pairing_search));
    }

    private void startProxy() {
        if (!ConfigStore.isConfigured(this)) {
            showStatus(getString(R.string.status_join_before_proxy));
            return;
        }
        ProxyService.start(this);
        showStatus(getString(R.string.status_proxy_start));
    }

    private void openWirelessDebugging() {
        Intent direct = new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS");
        try {
            startActivity(direct);
        } catch (ActivityNotFoundException exception) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        }
    }

    private boolean consumeWirelessDebugPrompt() {
        Intent intent = getIntent();
        if (intent == null) {
            return false;
        }
        boolean requested = intent.getBooleanExtra(EXTRA_PROMPT_WIRELESS_DEBUG, false);
        if (requested) {
            intent.removeExtra(EXTRA_PROMPT_WIRELESS_DEBUG);
        }
        return requested;
    }

    private void maybeShowWirelessDebugDialog(boolean force) {
        if (!ConfigStore.isConfigured(this) || !hasRuntimePermissions()) {
            return;
        }
        if (!force && isWirelessDebugEnabled()) {
            return;
        }
        if (isFinishing() || isDestroyed()
                || (wirelessDebugDialog != null && wirelessDebugDialog.isShowing())) {
            return;
        }
        int message = isWirelessDebugEnabled()
                ? R.string.wireless_enabled_missing : R.string.wireless_disabled;
        wirelessDebugDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.wireless_required_title)
                .setMessage(message)
                .setPositiveButton(R.string.open_wireless_debugging,
                        (dialog, which) -> beginNotificationPairing())
                .setNegativeButton(R.string.later, null)
                .setOnDismissListener(dialog -> wirelessDebugDialog = null)
                .show();
    }

    private boolean isWirelessDebugEnabled() {
        try {
            return Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static void requestWirelessDebugPrompt(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_PROMPT_WIRELESS_DEBUG, true);
        context.startActivity(intent);
    }

    private void runTask(String initialStatus, ThrowingRunnable task) {
        showStatus(initialStatus);
        executor.execute(() -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                showStatusFromWorker(getString(R.string.status_error, safeMessage(throwable)));
            }
        });
    }

    private void refreshPeerId() {
        String peerId = ConfigStore.peerId(this);
        peerIdText.setText(peerId.isEmpty()
                ? getString(R.string.peer_not_joined) : getString(R.string.peer_id_format, peerId));
    }

    private void refreshProxyStatus() {
        ProxyStatus.Snapshot snapshot = ProxyStatus.current(this);
        updateProxyStatus(snapshot.state, snapshot.detail);
    }

    private void updateProxyStatus(String state, String detail) {
        if (proxyStateText == null) {
            return;
        }
        String safeState = state == null ? ProxyStatus.DISCONNECTED : state;
        int label;
        int description;
        int dotColor;
        switch (safeState) {
            case ProxyStatus.CONNECTING:
                label = R.string.proxy_state_connecting;
                description = R.string.proxy_detail_connecting;
                dotColor = color(R.color.edgez_connecting);
                break;
            case ProxyStatus.MESH_ONLINE:
                label = R.string.proxy_state_mesh_online;
                description = R.string.proxy_detail_mesh_online;
                dotColor = color(R.color.edgez_success);
                break;
            case ProxyStatus.ADB_ONLINE:
                label = R.string.proxy_state_adb_online;
                description = R.string.proxy_detail_adb_online;
                dotColor = color(R.color.edgez_success);
                break;
            case ProxyStatus.STOPPING:
                label = R.string.proxy_state_stopping;
                description = R.string.proxy_detail_stopping;
                dotColor = color(R.color.edgez_warning);
                break;
            case ProxyStatus.ERROR:
                label = R.string.proxy_state_error;
                description = R.string.proxy_detail_error;
                dotColor = color(R.color.edgez_error);
                break;
            default:
                label = R.string.proxy_state_disconnected;
                description = R.string.proxy_detail_disconnected;
                dotColor = color(R.color.edgez_offline);
        }
        proxyStateText.setText(label);
        String baseDetail = getString(description);
        proxyDetailText.setText(detail == null || detail.trim().isEmpty()
                ? baseDetail : baseDetail + "\n" + detail.trim());
        proxyStateDot.setBackground(roundRect(dotColor, 99));
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerProxyStatusReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ProxyStatus.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(proxyStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(proxyStatusReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void showStatus(String message) {
        if (statusText != null) {
            statusText.setText(message);
        }
    }

    private void showStatusFromWorker(String message) {
        runOnUiThread(() -> showStatus(message));
    }

    private void requestRequiredPermissions() {
        if (!hasRuntimePermissions()) {
            requestRuntimePermissions();
            return;
        }
        requestUnrestrictedBattery();
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 33) {
            refreshPermissionStatus();
            return;
        }
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
        } else {
            refreshPermissionStatus();
        }
    }

    private boolean hasRuntimePermissions() {
        return Build.VERSION.SDK_INT < 33
                || (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                        == PackageManager.PERMISSION_GRANTED);
    }

    private void requestUnrestrictedBattery() {
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            refreshPermissionStatus();
            showStatus(getString(R.string.status_all_permissions));
            return;
        }
        try {
            Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(request);
        } catch (ActivityNotFoundException exception) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void refreshPermissionStatus() {
        if (permissionStatusText == null) {
            return;
        }
        boolean notifications = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        boolean nearby = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        PowerManager powerManager = getSystemService(PowerManager.class);
        boolean unrestricted = powerManager.isIgnoringBatteryOptimizations(getPackageName());
        permissionStatusText.setText(
                permissionLine(getString(R.string.permission_notifications), notifications)
                        + "\n" + permissionLine(getString(R.string.permission_nearby_wifi), nearby)
                        + "\n" + permissionLine(getString(R.string.permission_battery), unrestricted));
        permissionStatusText.setTextColor(notifications && nearby && unrestricted
                ? color(R.color.edgez_success) : color(R.color.edgez_warning));
    }

    private static String permissionLine(String name, boolean granted) {
        return (granted ? "✓ " : "○ ") + name;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) {
            return;
        }
        refreshPermissionStatus();
        if (hasRuntimePermissions()) {
            showStatus(getString(R.string.status_pairing_permissions));
            requestUnrestrictedBattery();
        } else {
            showStatus(getString(R.string.status_permissions_needed));
        }
    }

    private LinearLayout card() {
        return verticalPanel(18, roundRect(color(R.color.edgez_surface), 20));
    }

    private LinearLayout verticalPanel(int padding, GradientDrawable background) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        panel.setBackground(background);
        panel.setElevation(dp(2));
        return panel;
    }

    private TextView cardTitle(int label) {
        TextView title = text(getString(label), 19, color(R.color.edgez_text));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return title;
    }

    private LinearLayout stepTitle(String number, int label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(number, 13, Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setBackground(roundRect(color(R.color.edgez_blue), 99));
        row.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView title = cardTitle(label);
        row.addView(title, margins(12, 0, 0, 0));
        return row;
    }

    private TextView description(int label) {
        TextView description = text(getString(label), 14, color(R.color.edgez_text_muted));
        description.setLineSpacing(0, 1.12f);
        description.setLayoutParams(margins(0, 10, 0, 4));
        return description;
    }

    private Button actionButton(int label, View.OnClickListener listener, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : color(R.color.edgez_blue));
        button.setBackgroundTintList(ColorStateList.valueOf(primary
                ? color(R.color.edgez_blue) : color(R.color.edgez_blue_soft)));
        button.setOnClickListener(listener);
        button.setMinHeight(dp(46));
        button.setLayoutParams(margins(0, 10, 0, 0));
        return button;
    }

    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(0xFFE4EAF3);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return divider;
    }

    private TextView text(String value, int size, int textColor) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(textColor);
        return text;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams weightedButtonMargins(int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(left), 0, dp(right), 0);
        return params;
    }

    private GradientDrawable roundRect(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private GradientDrawable gradient(int start, int end, int radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int color(int resource) {
        return getColor(resource);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
