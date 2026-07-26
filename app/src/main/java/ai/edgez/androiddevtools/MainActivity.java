package ai.edgez.androiddevtools;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 10;
    private static final String EXTRA_PROMPT_WIRELESS_DEBUG =
            "ai.edgez.androiddevtools.extra.PROMPT_WIRELESS_DEBUG";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AlertDialog wirelessDebugDialog;
    private EditText serialInput;
    private EditText joinKeyInput;
    private TextView peerIdText;
    private TextView permissionStatusText;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NativeBridge.initialize(this);
        setContentView(buildContent());
        refreshPeerId();
        refreshPermissionStatus();
        requestRuntimePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (permissionStatusText != null) {
            refreshPermissionStatus();
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
        int padding = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView title = text("Android DevTools", 28, Color.rgb(13, 71, 161));
        content.addView(title);
        TextView subtitle = text(
                "Physical-device deploy and Flutter hot reload through adb-sidecar",
                15,
                Color.DKGRAY);
        content.addView(subtitle, margins(0, 4, 0, 20));

        content.addView(section("1. Allow background device discovery"));
        content.addView(text(
                "Notifications expose proxy health, Nearby devices allows Wireless Debugging "
                        + "discovery, and unrestricted battery use keeps the relay reservation alive.",
                14,
                Color.DKGRAY));
        permissionStatusText = text("", 14, Color.DKGRAY);
        content.addView(permissionStatusText, margins(0, 8, 0, 4));
        content.addView(button("Grant required permissions", view -> requestRequiredPermissions()));

        content.addView(
                section("2. Get device serial and join key from edgez.ai portal"),
                margins(0, 20, 0, 4));
        content.addView(text(
                "Enter the device serial used by JupyterHub and its join key.",
                14,
                Color.DKGRAY));
        content.addView(button(
                "How to get the serial and join key",
                view -> startActivity(new Intent(this, JoinKeyTipsActivity.class))));
        String storedSerial = ConfigStore.storedSerial(this);
        String storedJoinKey = ConfigStore.storedJoinKey(this);
        serialInput = input(
                "Serial number",
                storedSerial.isEmpty() ? defaultSerial() : storedSerial,
                false);
        joinKeyInput = input("Join key", storedJoinKey, true);
        content.addView(serialInput);
        content.addView(joinKeyInput);
        content.addView(button("Join network", view -> joinNetwork()));

        peerIdText = text("Peer ID: not joined", 14, Color.DKGRAY);
        peerIdText.setTextIsSelectable(true);
        content.addView(peerIdText, margins(0, 10, 0, 20));

        content.addView(section("3. Pair Wireless Debugging"));
        content.addView(text(
                "Tap below, choose “Pair device with pairing code” in Android settings, "
                        + "then enter the six-digit code directly in the pairing notification.",
                14,
                Color.DKGRAY));
        content.addView(button("Pair from notification", view -> beginNotificationPairing()));

        content.addView(section("4. Proxy lifecycle"), margins(0, 20, 0, 4));
        content.addView(button("Refresh ADB & start", view -> startProxy()));
        content.addView(button("Stop proxy", view -> {
            ProxyService.stop(this);
            showStatus("Proxy stop requested.");
        }));

        statusText = text(
                "Ready. The JupyterHub side should connect to adb-sidecar at 127.0.0.1:5555.",
                14,
                Color.rgb(40, 40, 40));
        statusText.setTextIsSelectable(true);
        statusText.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusText.setBackgroundColor(Color.rgb(238, 242, 247));
        content.addView(statusText, margins(0, 20, 0, 20));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private void joinNetwork() {
        String serial = value(serialInput);
        String joinKey = value(joinKeyInput);
        if (serial.isEmpty() || joinKey.isEmpty()) {
            showStatus("Serial number and join key are required.");
            return;
        }
        runTask("Joining network…", () -> {
            String peerId = ConfigStore.join(
                    this,
                    ConfigStore.DEFAULT_JOIN_ENDPOINT,
                    serial,
                    joinKey,
                    Build.MANUFACTURER + " " + Build.MODEL);
            runOnUiThread(() -> {
                refreshPeerId();
                ProxyService.start(this);
                showStatus(
                        "Joined successfully. Libp2p is starting in the background. Peer ID: "
                                + peerId);
            });
        });
    }

    private void beginNotificationPairing() {
        if (!hasRuntimePermissions()) {
            showStatus(
                    "Grant notification and Nearby Wi-Fi permissions before starting pairing.");
            requestRuntimePermissions();
            return;
        }
        if (!ConfigStore.isConfigured(this)) {
            showStatus("Join the libp2p network before pairing.");
            return;
        }
        PairingService.start(this);
        openWirelessDebugging();
        showStatus(
                "Pairing search started. Open “Pair device with pairing code”, "
                        + "then reply to the Android DevTools notification.");
    }

    private void startProxy() {
        if (!ConfigStore.isConfigured(this)) {
            showStatus("Join the libp2p network first.");
            return;
        }
        ProxyService.start(this);
        showStatus("Proxy start requested. Check the persistent notification for status.");
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
        if (!ConfigStore.isConfigured(this)) {
            return;
        }
        if (!hasRuntimePermissions()) {
            return;
        }
        if (!force && isWirelessDebugEnabled()) {
            return;
        }
        if (isFinishing() || isDestroyed()
                || (wirelessDebugDialog != null && wirelessDebugDialog.isShowing())) {
            return;
        }

        String message = isWirelessDebugEnabled()
                ? "Wireless Debugging is enabled, but its ADB endpoint was not found. "
                    + "Open Wireless Debugging and choose “Pair device with pairing code”."
                : "Turn on Wireless Debugging, then choose “Pair device with pairing code”. "
                    + "Keep Settings open until Android DevTools finds the pairing endpoint.";
        wirelessDebugDialog = new AlertDialog.Builder(this)
                .setTitle("Wireless Debugging required")
                .setMessage(message)
                .setPositiveButton("Open Wireless Debugging", (dialog, which) ->
                        beginNotificationPairing())
                .setNegativeButton("Later", null)
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
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
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
                showStatusFromWorker("Error: " + safeMessage(throwable));
            }
        });
    }

    private void refreshPeerId() {
        String peerId = ConfigStore.peerId(this);
        peerIdText.setText(peerId.isEmpty() ? "Peer ID: not joined" : "Peer ID: " + peerId);
    }

    private void showStatus(String message) {
        statusText.setText(message);
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
            showStatus("All required permissions are granted.");
            return;
        }
        try {
            Intent request = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
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
                permissionLine("Notifications", notifications)
                        + "\n" + permissionLine("Nearby Wi-Fi", nearby)
                        + "\n" + permissionLine("Battery unrestricted", unrestricted));
        permissionStatusText.setTextColor(
                notifications && nearby && unrestricted
                        ? Color.rgb(30, 120, 60) : Color.rgb(180, 85, 20));
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
            showStatus("Pairing permissions granted. Allow unrestricted battery use next.");
            requestUnrestrictedBattery();
        } else {
            showStatus(
                    "Nearby Wi-Fi and notification permissions are needed for discovery "
                            + "and reliable background status.");
        }
    }

    private EditText input(String hint, String value, boolean secret) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(true);
        input.setTextSize(15);
        if (secret) {
            input.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        input.setLayoutParams(margins(0, 2, 0, 2));
        return input;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setLayoutParams(margins(0, 6, 0, 2));
        return button;
    }

    private TextView section(String label) {
        return text(label, 19, Color.rgb(13, 71, 161));
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String value(EditText input) {
        return input.getText().toString().trim();
    }

    private static String defaultSerial() {
        return Build.MANUFACTURER.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                + "-" + Build.MODEL.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
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
