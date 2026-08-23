package ai.edgez.androiddevtools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class PortalStore {
    static final String BASE_URL = "https://www.edgez.ai";
    static final String AUTH_CALLBACK = "edgez-devtools://auth-callback";
    private static final String PREFS = "edgez_portal";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_USER = "user";
    private static final String KEY_ORGANIZATION = "organization_id";
    private static final String KEY_PROJECT = "project_id";
    private static final String KEY_PROJECT_ORGANIZATION = "project_organization_id";

    private PortalStore() {
    }

    static boolean isSignedIn(Context context) {
        return !accessToken(context).isEmpty();
    }

    static String accessToken(Context context) {
        return preferences(context).getString(KEY_ACCESS_TOKEN, "");
    }

    static JSONObject user(Context context) {
        try {
            return new JSONObject(preferences(context).getString(KEY_USER, "{}"));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    static String organizationId(Context context) {
        return preferences(context).getString(KEY_ORGANIZATION, "");
    }

    static void setOrganizationId(Context context, String organizationId) {
        SharedPreferences current = preferences(context);
        SharedPreferences.Editor editor = current.edit().putString(KEY_ORGANIZATION, organizationId);
        if (!organizationId.equals(current.getString(KEY_ORGANIZATION, ""))) {
            editor.remove(KEY_PROJECT);
            editor.remove(KEY_PROJECT_ORGANIZATION);
        }
        editor.commit();
    }

    static String projectId(Context context) {
        SharedPreferences current = preferences(context);
        String projectId = current.getString(KEY_PROJECT, "");
        String projectOrganizationId = current.getString(KEY_PROJECT_ORGANIZATION, "");
        String organizationId = current.getString(KEY_ORGANIZATION, "");
        if (!projectOrganizationId.isEmpty() && !projectOrganizationId.equals(organizationId)) {
            return "";
        }
        return projectId;
    }

    static void setProjectId(Context context, String projectId) {
        SharedPreferences current = preferences(context);
        current.edit()
                .putString(KEY_PROJECT, projectId)
                .putString(KEY_PROJECT_ORGANIZATION,
                        current.getString(KEY_ORGANIZATION, ""))
                .commit();
    }

    static void setSelection(Context context, String organizationId, String projectId) {
        preferences(context).edit()
                .putString(KEY_ORGANIZATION, organizationId)
                .putString(KEY_PROJECT, projectId)
                .putString(KEY_PROJECT_ORGANIZATION, organizationId)
                .commit();
    }

    static void signOut(Context context) {
        preferences(context).edit().clear().apply();
    }

    static void requestMagicLink(String email) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("redirect", AUTH_CALLBACK);
        request("POST", BASE_URL + "/api/mobile-auth/request", null, body);
    }

    static void exchangeCode(Context context, String code) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("code", code);
        JSONObject response = request(
                "POST", BASE_URL + "/api/mobile-auth/exchange", null, body);
        String accessToken = response.optString("accessToken", "").trim();
        if (accessToken.isEmpty()) {
            throw new IOException("The login response did not contain an access token.");
        }
        preferences(context).edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_USER, response.optJSONObject("user") == null
                        ? "{}" : response.optJSONObject("user").toString())
                .apply();
    }

    static JSONObject loadHome(Context context, String organizationId)
            throws IOException, JSONException {
        String endpoint = BASE_URL + "/api/mobile/home";
        if (organizationId != null && !organizationId.trim().isEmpty()) {
            endpoint += "?organizationId=" + URLEncoder.encode(
                    organizationId, StandardCharsets.UTF_8.name());
        }
        return request("GET", endpoint, accessToken(context), null);
    }

    static JSONObject loadTemplates(Context context, int page, int pageSize)
            throws IOException, JSONException {
        String endpoint = BASE_URL + "/api/mobile/templates?page=" + Math.max(1, page)
                + "&pageSize=" + Math.max(1, pageSize);
        return request("GET", endpoint, accessToken(context), null);
    }

    static JSONObject loadTemplate(Context context, String templateId, String organizationId)
            throws IOException, JSONException {
        String endpoint = BASE_URL + "/api/mobile/templates/" + URLEncoder.encode(
                templateId, StandardCharsets.UTF_8.name()) + "?organizationId="
                + URLEncoder.encode(organizationId, StandardCharsets.UTF_8.name());
        return request("GET", endpoint, accessToken(context), null);
    }

    static JSONObject loadGithubRepositories(
            Context context, String organizationId, String projectId)
            throws IOException, JSONException {
        String endpoint = BASE_URL + "/api/mobile/github/repositories?organizationId="
                + URLEncoder.encode(organizationId, StandardCharsets.UTF_8.name())
                + "&projectId="
                + URLEncoder.encode(projectId, StandardCharsets.UTF_8.name());
        return request("GET", endpoint, accessToken(context), null);
    }

    static JSONObject loadWorkspaces(Context context, String organizationId)
            throws IOException, JSONException {
        String endpoint = BASE_URL + "/api/jupyterhub/workspaces?organizationId="
                + URLEncoder.encode(organizationId, StandardCharsets.UTF_8.name());
        return request("GET", endpoint, accessToken(context), null);
    }

    static JSONObject changeWorkspaceState(
            Context context, String organizationId, String name, boolean start)
            throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("organizationId", organizationId);
        body.put("name", name);
        return request(start ? "PUT" : "PATCH", BASE_URL + "/api/jupyterhub/workspaces",
                accessToken(context), body);
    }

    static JSONObject deleteWorkspace(Context context, String organizationId, String name)
            throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("organizationId", organizationId);
        body.put("name", name);
        return request("DELETE", BASE_URL + "/api/jupyterhub/workspaces",
                accessToken(context), body);
    }

    static JSONObject loadWorkspaceCodexConnection(
            Context context, String organizationId, String name)
            throws IOException, JSONException {
        String endpoint = BASE_URL + "/api/mobile/workspaces/"
                + URLEncoder.encode(name, StandardCharsets.UTF_8.name())
                + "/codex?organizationId="
                + URLEncoder.encode(organizationId, StandardCharsets.UTF_8.name());
        return request("GET", endpoint, accessToken(context), null);
    }

    static JSONObject createProject(
            Context context, String organizationId, String name, int serverId)
            throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("organizationId", organizationId);
        body.put("name", name);
        body.put("serverId", serverId);
        return request("POST", BASE_URL + "/api/mobile/projects",
                accessToken(context), body);
    }

    static JSONObject deployTemplate(
            Context context,
            String organizationId,
            String projectId,
            String templateId,
            String name,
            String workspaceSize,
            String devicePeerId,
            String deviceName,
            String installationId,
            String repositoryId,
            String newRepositoryName,
            boolean newRepositoryPrivate) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("organizationId", organizationId);
        body.put("projectId", projectId);
        body.put("templateId", templateId);
        body.put("name", name);
        body.put("workspaceSize", workspaceSize);
        body.put("devicePeerId", devicePeerId);
        body.put("deviceName", deviceName);
        body.put("installationId", installationId);
        body.put("repositoryId", repositoryId);
        body.put("newRepositoryName", newRepositoryName);
        body.put("newRepositoryPrivate", newRepositoryPrivate);
        return request("POST", BASE_URL + "/api/mobile/templates/deploy",
                accessToken(context), body);
    }

    private static JSONObject request(
            String method, String endpoint, String accessToken, JSONObject body)
            throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(15_000);
        // Workspace start/stop follows the web flow and can wait for JupyterHub to
        // finish spawning or stopping the named server before returning.
        boolean workspaceLifecycle = ("PUT".equals(method) || "PATCH".equals(method))
                && endpoint.endsWith("/api/jupyterhub/workspaces");
        connection.setReadTimeout(workspaceLifecycle ? 300_000 : 25_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (accessToken != null && !accessToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        if (body != null) {
            byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(encoded.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(encoded);
            }
        }
        int status = connection.getResponseCode();
        String response = readFully(status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream());
        connection.disconnect();
        if (status < 200 || status >= 300) {
            String detail = response;
            try {
                detail = new JSONObject(response).optString("error", response);
            } catch (JSONException ignored) {
                // Preserve the raw response.
            }
            throw new IOException("HTTP " + status + (detail.isEmpty() ? "" : ": " + detail));
        }
        return response.isEmpty() ? new JSONObject() : new JSONObject(response);
    }

    private static String readFully(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line);
        }
        return output.toString();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
