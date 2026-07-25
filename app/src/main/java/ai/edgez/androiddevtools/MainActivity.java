package ai.edgez.androiddevtools;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Locale;

public final class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText endpointInput;
    private EditText serialInput;
    private EditText joinKeyInput;
    private EditText nameInput;
    private EditText pairCodeInput;
    private TextView peerIdText;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NativeBridge.initialize(this);
        setContentView(buildContent());
        refreshPeerId();
        requestNotifications();
    }

    @Override
    protected void onDestroy() {
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

        content.addView(section("1. Join the libp2p network"));
        endpointInput = input("Join endpoint", ConfigStore.DEFAULT_JOIN_ENDPOINT, false);
        serialInput = input("Device endpoint / serial", defaultSerial(), false);
        joinKeyInput = input("Join key", "", true);
        nameInput = input("Device name", Build.MANUFACTURER + " " + Build.MODEL, false);
        content.addView(endpointInput);
        content.addView(serialInput);
        content.addView(joinKeyInput);
        content.addView(nameInput);
        content.addView(button("Join network", view -> joinNetwork()));

        peerIdText = text("Peer ID: not joined", 14, Color.DKGRAY);
        peerIdText.setTextIsSelectable(true);
        content.addView(peerIdText, margins(0, 10, 0, 20));

        content.addView(section("2. Pair Wireless Debugging"));
        content.addView(text(
                "Open Wireless debugging → Pair device with pairing code. Keep that dialog open, "
                        + "enter the code below, then start discovery.",
                14,
                Color.DKGRAY));
        content.addView(button("Open Wireless debugging", view -> openWirelessDebugging()));
        pairCodeInput = input("Six-digit pairing code", "", false);
        pairCodeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        content.addView(pairCodeInput);
        content.addView(button("Discover, pair & start", view -> pairAndStart()));

        content.addView(section("3. Proxy lifecycle"), margins(0, 20, 0, 4));
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
        String endpoint = value(endpointInput);
        String serial = value(serialInput);
        String joinKey = value(joinKeyInput);
        String name = value(nameInput);
        if (endpoint.isEmpty() || serial.isEmpty() || joinKey.isEmpty()) {
            showStatus("Join endpoint, device serial, and join key are required.");
            return;
        }
        runTask("Joining network…", () -> {
            String peerId = ConfigStore.join(this, endpoint, serial, joinKey, name);
            runOnUiThread(() -> {
                joinKeyInput.setText("");
                refreshPeerId();
                showStatus("Joined successfully. Peer ID: " + peerId);
            });
        });
    }

    private void pairAndStart() {
        String code = value(pairCodeInput).replaceAll("\\s+", "");
        if (code.isEmpty()) {
            showStatus("Open the system pairing-code dialog and enter its code first.");
            return;
        }
        if (!ConfigStore.isConfigured(this)) {
            showStatus("Join the libp2p network before pairing.");
            return;
        }
        runTask("Discovering the pairing endpoint…", () -> {
            Endpoint pair = WirelessDebugDiscovery.discoverPairing(this, 8_000);
            if (pair == null) {
                throw new IllegalStateException(
                        "Pairing endpoint not found. Keep “Pair device with pairing code” open.");
            }
            showStatusFromWorker("Pairing at " + pair.display() + "…");
            Endpoint debug = WirelessDebugDiscovery.discoverConnect(this, 8_000);
            if (debug == null) {
                throw new IllegalStateException(
                        "Wireless-debug connect endpoint was not found. Ensure its master switch is on.");
            }
            String response = NativeBridge.nativePairWireless(
                    pair.host, pair.port, code, debug.host, debug.port);
            JSONObject result = new JSONObject(response);
            if (!result.optBoolean("ok")) {
                throw new IllegalStateException(result.optString("error", response));
            }
            ConfigStore.saveAdbEndpoint(this, debug);
            runOnUiThread(() -> {
                pairCodeInput.setText("");
                showStatus("Paired. Starting proxy to local adbd :" + debug.port + "…");
                ProxyService.start(this);
            });
        });
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

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
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
