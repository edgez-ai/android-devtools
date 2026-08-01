package ai.edgez.androiddevtools;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class ConfigStore {
    static final String DEFAULT_JOIN_ENDPOINT = "https://www.edgez.ai/api/join";
    private static final String TAG = "AndroidDevTools";
    private static final String PREFS = "edgejoin";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_PEER_ID = "peer_id";
    private static final String KEY_PRIVATE_KEY = "private_key";
    private static final String KEY_PUBLIC_KEY = "public_key";
    private static final String KEY_JOIN_RESPONSE = "join_response";
    private static final String KEY_JOIN_KEY = "join_key";
    private static final String KEY_SERIAL = "serial_number";
    private static final String KEY_ADB_HOST = "adb_proxy_host";
    private static final String KEY_ADB_PORT = "adb_proxy_port";

    private ConfigStore() {
    }

    static String join(
            Context context,
            String endpoint,
            String serial,
            String joinKey,
            String name) throws IOException, JSONException {
        JSONObject identity = loadOrCreateIdentity(context, name);

        JSONObject payload = new JSONObject();
        payload.put("id", identity.getString("peer_id"));
        payload.put("join_key", joinKey);
        payload.put("serial_number", serial);
        payload.put("platform", "android");
        payload.put("arch", Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0]);
        payload.put("version", "android-devtools/0.4.0");
        payload.put("name", name);
        payload.put("port", 22);
        payload.put("username", "android");

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + joinKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        int status = connection.getResponseCode();
        InputStream responseStream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = readFully(responseStream);
        connection.disconnect();
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("join returned HTTP " + status + ": " + response);
        }

        JSONObject config = new JSONObject(response);
        config.put("key", identity.getString("private_key"));
        config.put("public", false);

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIG, config.toString())
                .putString(KEY_PEER_ID, identity.getString("peer_id"))
                .putString(KEY_PRIVATE_KEY, identity.getString("private_key"))
                .putString(KEY_PUBLIC_KEY, identity.optString("public_key", ""))
                .putString(KEY_JOIN_RESPONSE, response)
                .putString(KEY_JOIN_KEY, joinKey)
                .putString(KEY_SERIAL, serial)
                .apply();
        return identity.getString("peer_id");
    }

    private static JSONObject loadOrCreateIdentity(Context context, String name)
            throws IOException, JSONException {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String peerId = trimmed(preferences.getString(KEY_PEER_ID, ""));
        String privateKey = trimmed(preferences.getString(KEY_PRIVATE_KEY, ""));
        String publicKey = trimmed(preferences.getString(KEY_PUBLIC_KEY, ""));

        // Older installations may have the private key only in the assembled
        // client config. Recover it instead of changing the device identity.
        if (privateKey.isEmpty()) {
            String storedConfig = preferences.getString(KEY_CONFIG, "");
            if (storedConfig != null && !storedConfig.trim().isEmpty()) {
                try {
                    privateKey = trimmed(new JSONObject(storedConfig).optString("key", ""));
                } catch (JSONException exception) {
                    Log.w(TAG, "Stored libp2p config is invalid; cannot recover identity", exception);
                }
            }
        }

        if (!peerId.isEmpty() && isValidEncodedKey(privateKey)) {
            preferences.edit()
                    .putString(KEY_PRIVATE_KEY, privateKey)
                    .apply();
            JSONObject identity = new JSONObject();
            identity.put("ok", true);
            identity.put("peer_id", peerId);
            identity.put("private_key", privateKey);
            identity.put("public_key", publicKey);
            Log.i(TAG, "Reusing stored libp2p identity peer=" + peerId);
            return identity;
        }

        String identityText = NativeBridge.nativeCreateIdentity(name);
        JSONObject identity = new JSONObject(identityText);
        if (!identity.optBoolean("ok")) {
            throw new IOException(identity.optString("error", "identity generation failed"));
        }

        peerId = identity.getString("peer_id");
        privateKey = identity.getString("private_key");
        publicKey = identity.optString("public_key", "");
        if (!isValidEncodedKey(privateKey)) {
            throw new IOException("native identity generation returned an invalid private key");
        }

        // Save before contacting the join service. A timeout or rejected join
        // must not cause the next attempt to generate a different peer ID.
        boolean saved = preferences.edit()
                .putString(KEY_PEER_ID, peerId)
                .putString(KEY_PRIVATE_KEY, privateKey)
                .putString(KEY_PUBLIC_KEY, publicKey)
                .commit();
        if (!saved) {
            throw new IOException("unable to persist generated libp2p identity");
        }
        Log.i(TAG, "Generated and persisted new libp2p identity peer=" + peerId);
        return identity;
    }

    private static boolean isValidEncodedKey(String encoded) {
        if (encoded.isEmpty()) {
            return false;
        }
        try {
            return Base64.decode(encoded, Base64.DEFAULT).length >= 32;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    static void saveAdbEndpoint(Context context, Endpoint endpoint) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ADB_HOST, endpoint.host)
                .putInt(KEY_ADB_PORT, endpoint.port)
                .apply();
    }

    static Endpoint loadAdbEndpoint(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int port = preferences.getInt(KEY_ADB_PORT, 0);
        if (port < 1 || port > 65535) {
            return null;
        }
        String host = preferences.getString(KEY_ADB_HOST, "127.0.0.1");
        return new Endpoint(host == null || host.trim().isEmpty() ? "127.0.0.1" : host, port);
    }

    static boolean isConfigured(Context context) {
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CONFIG, "").isEmpty();
    }

    static String peerId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PEER_ID, "");
    }

    static String storedSerial(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SERIAL, "");
    }

    static String storedJoinKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_JOIN_KEY, "");
    }

    static String clientConfig(Context context) throws JSONException {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = preferences.getString(KEY_CONFIG, "");
        if (stored == null || stored.isEmpty()) {
            throw new JSONException("device has not joined the network");
        }
        JSONObject config = new JSONObject(stored);
        String serial = preferences.getString(KEY_SERIAL, "");
        if (serial != null && !serial.isEmpty()) {
            config.put("serial_number", serial);
        }
        Endpoint adb = loadAdbEndpoint(context);
        if (adb != null) {
            // The dynamic mDNS host identifies this device on Wi-Fi. Once paired,
            // loopback is the stable and private route to the same local adbd.
            config.put("adb_proxy_host", "127.0.0.1");
            config.put("adb_proxy_port", adb.port);
            JSONObject targets = config.optJSONObject("tap_targets");
            if (targets == null) {
                targets = new JSONObject();
            }
            targets.put("5555", "127.0.0.1:" + adb.port);
            config.put("tap_targets", targets);
        }
        return config.toString();
    }

    private static String readFully(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        return output.toString();
    }
}
