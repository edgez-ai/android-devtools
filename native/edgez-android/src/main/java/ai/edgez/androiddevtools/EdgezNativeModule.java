package ai.edgez.androiddevtools;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.reflect.Method;

public final class EdgezNativeModule extends ReactContextBaseJavaModule
        implements ActivityEventListener {
    private static final String EVENT_STATUS_CHANGED = "EdgezStatusChanged";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            emitStatus();
        }
    };
    private boolean receiverRegistered;
    @Nullable private Promise scanPromise;

    EdgezNativeModule(ReactApplicationContext reactContext) {
        super(reactContext);
        reactContext.addActivityEventListener(this);
    }

    @NonNull
    @Override
    public String getName() {
        return "EdgezNative";
    }

    @Override
    public void initialize() {
        super.initialize();
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ProxyStatus.ACTION_CHANGED);
        filter.addAction(UsbIpServer.ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(
                getReactApplicationContext(), statusReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    @Override
    public void invalidate() {
        if (receiverRegistered) {
            getReactApplicationContext().unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        if (scanPromise != null) {
            scanPromise.reject("EDGEZ_SCAN_CANCELLED", "React Native was reloaded");
            scanPromise = null;
        }
        executor.shutdownNow();
        super.invalidate();
    }

    @ReactMethod
    public void getStatus(Promise promise) {
        promise.resolve(statusMap());
    }

    @ReactMethod
    public void scanAndJoin(Promise promise) {
        Activity activity = getCurrentActivity();
        if (activity == null) {
            promise.reject("EDGEZ_NO_ACTIVITY", "The app is not in the foreground");
            return;
        }
        if (scanPromise != null) {
            promise.reject("EDGEZ_SCAN_ACTIVE", "A QR scan is already active");
            return;
        }
        scanPromise = promise;
        try {
            new IntentIntegrator(activity)
                    .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt("Point the camera at the pairing QR code shown on edgez.ai.")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .initiateScan();
        } catch (RuntimeException exception) {
            scanPromise = null;
            promise.reject("EDGEZ_SCAN_FAILED", exception);
        }
    }

    @Override
    public void onActivityResult(
            Activity activity, int requestCode, int resultCode, @Nullable Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null || scanPromise == null) {
            return;
        }
        Promise promise = scanPromise;
        scanPromise = null;
        String rawValue = result.getContents();
        if (rawValue == null) {
            promise.reject("EDGEZ_SCAN_CANCELLED", "QR code scan canceled");
            return;
        }
        executor.execute(() -> joinFromQr(rawValue, promise));
    }

    @Override
    public void onNewIntent(Intent intent) {
        // No activity result is delivered through a new intent for QR scanning.
    }

    @ReactMethod
    public void beginPairing(Promise promise) {
        try {
            if (!ConfigStore.isConfigured(getReactApplicationContext())) {
                promise.reject("EDGEZ_NOT_CONFIGURED", "Join the EdgeZ network first");
                return;
            }
            PairingService.start(getReactApplicationContext());
            Intent settings = new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                getReactApplicationContext().startActivity(settings);
            } catch (RuntimeException unavailable) {
                getReactApplicationContext().startActivity(
                        new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            promise.resolve(null);
        } catch (RuntimeException exception) {
            promise.reject("EDGEZ_PAIRING", exception);
        }
    }

    @ReactMethod
    public void requestBatteryExemption(Promise promise) {
        try {
            PowerManager powerManager = getReactApplicationContext()
                    .getSystemService(PowerManager.class);
            if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(
                    getReactApplicationContext().getPackageName())) {
                promise.resolve(null);
                return;
            }
            Intent request = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getReactApplicationContext().getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getReactApplicationContext().startActivity(request);
            promise.resolve(null);
        } catch (RuntimeException exception) {
            try {
                getReactApplicationContext().startActivity(
                        new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                promise.resolve(null);
            } catch (RuntimeException fallback) {
                promise.reject("EDGEZ_BATTERY_SETTINGS", fallback);
            }
        }
    }

    @ReactMethod
    public void openProjectLauncher(Promise promise) {
        UiThreadUtil.runOnUiThread(() -> {
            try {
                Class<?> controllerClass = Class.forName(
                        "expo.modules.devlauncher.DevLauncherController");
                Method getInstance = controllerClass.getMethod("getInstance");
                Object controller = getInstance.invoke(null);
                Method navigateToLauncher = controllerClass.getMethod("navigateToLauncher");
                promise.resolve(null);
                navigateToLauncher.invoke(controller);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                promise.reject("EDGEZ_PROJECT_LAUNCHER", exception);
            }
        });
    }

    @ReactMethod
    public void startProxy(Promise promise) {
        try {
            if (!ConfigStore.isConfigured(getReactApplicationContext())) {
                promise.reject("EDGEZ_NOT_CONFIGURED", "Join the EdgeZ network first");
                return;
            }
            ProxyService.start(getReactApplicationContext());
            promise.resolve(null);
        } catch (RuntimeException exception) {
            promise.reject("EDGEZ_START_PROXY", exception);
        }
    }

    @ReactMethod
    public void stopProxy(Promise promise) {
        try {
            ProxyService.stop(getReactApplicationContext());
            promise.resolve(null);
        } catch (RuntimeException exception) {
            promise.reject("EDGEZ_STOP_PROXY", exception);
        }
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Required by NativeEventEmitter. The broadcast receiver is shared.
    }

    @ReactMethod
    public void removeListeners(double count) {
        // Required by NativeEventEmitter. The broadcast receiver is shared.
    }

    private void joinFromQr(String rawValue, Promise promise) {
        try {
            JSONObject payload = new JSONObject(rawValue);
            String serial = payload.optString("serial_number", "").trim();
            String joinKey = payload.optString("join_key", "").trim();
            if (serial.isEmpty() || joinKey.isEmpty()) {
                promise.reject(
                        "EDGEZ_INVALID_QR",
                        "The QR code must contain serial_number and join_key");
                return;
            }
            String peerId = ConfigStore.join(
                    getReactApplicationContext(), ConfigStore.DEFAULT_JOIN_ENDPOINT,
                    serial, joinKey, Build.MANUFACTURER + " " + Build.MODEL);
            ProxyService.start(getReactApplicationContext());
            emitStatus();
            promise.resolve(peerId);
        } catch (JSONException exception) {
            promise.reject("EDGEZ_INVALID_QR", "This is not a valid EdgeZ pairing QR code");
        } catch (Throwable throwable) {
            promise.reject("EDGEZ_JOIN_FAILED", throwable);
        }
    }

    private WritableMap statusMap() {
        ReactApplicationContext context = getReactApplicationContext();
        ProxyStatus.Snapshot snapshot = ProxyStatus.current(context);
        WritableMap status = new WritableNativeMap();
        status.putBoolean("configured", ConfigStore.isConfigured(context));
        String peerId = ConfigStore.peerId(context);
        if (peerId == null || peerId.isEmpty()) {
            status.putNull("peerId");
        } else {
            status.putString("peerId", peerId);
        }
        status.putString("proxyState", snapshot.state);
        status.putString("proxyDetail", snapshot.detail);
        status.putBoolean("adbPaired", ConfigStore.loadAdbEndpoint(context) != null);
        status.putBoolean("notificationsGranted", Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED);
        status.putBoolean("nearbyWifiGranted", Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                == PackageManager.PERMISSION_GRANTED);
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        status.putBoolean("batteryUnrestricted", powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(context.getPackageName()));

        UsbManager usbManager = context.getSystemService(UsbManager.class);
        int attached = 0;
        int permitted = 0;
        if (usbManager != null) {
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                attached++;
                if (usbManager.hasPermission(device)) {
                    permitted++;
                }
            }
        }
        status.putInt("usbAttached", attached);
        status.putInt("usbPermitted", permitted);
        return status;
    }

    private void emitStatus() {
        if (!getReactApplicationContext().hasActiveReactInstance()) {
            return;
        }
        getReactApplicationContext()
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(EVENT_STATUS_CHANGED, statusMap());
    }
}
