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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONException;
import org.json.JSONArray;
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
    private final BroadcastReceiver usbStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshUsbStatus();
        }
    };
    private AlertDialog wirelessDebugDialog;
    private AlertDialog projectSelectionDialog;
    private PopupWindow accountPopup;
    private final Button[] stepTabButtons = new Button[3];
    private final View[] stepPanels = new View[3];
    private TextView peerIdText;
    private TextView permissionStatusText;
    private TextView proxyStateText;
    private TextView proxyDetailText;
    private TextView usbStatusText;
    private TextView statusText;
    private View proxyStateDot;
    private View usbStateDot;
    private Button proxyToggleButton;
    private EditText loginEmail;
    private LinearLayout projectContent;
    private LinearLayout templatesContent;
    private LinearLayout templateDetailContent;
    private EditText workspaceNameInput;
    private Spinner workspaceSizeSpinner;
    private JSONObject selectedTemplate;
    private JSONArray allowedWorkspaceSizes = new JSONArray();
    private int templatesPage = 1;
    private JSONArray organizations = new JSONArray();
    private JSONArray projects = new JSONArray();
    private boolean updatingPortalSelectors;
    private boolean receiverRegistered;
    private boolean showingSettings;
    private boolean showingTemplates;
    private int selectedStep;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showHomePage();
        handleAuthIntent(getIntent());
        reconcileProxyStatus();
        refreshProxyStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerProxyStatusReceiver();
        reconcileProxyStatus();
        refreshProxyStatus();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(proxyStatusReceiver);
            unregisterReceiver(usbStatusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
        refreshUsbStatus();
        refreshStepMarkers();
        boolean promptRequested = consumeWirelessDebugPrompt();
        maybeShowWirelessDebugDialog(promptRequested
                || (showingSettings && !isWirelessDebugEnabled()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthIntent(intent);
        maybeShowWirelessDebugDialog(consumeWirelessDebugPrompt());
    }

    @Override
    protected void onDestroy() {
        if (wirelessDebugDialog != null) {
            wirelessDebugDialog.dismiss();
            wirelessDebugDialog = null;
        }
        dismissAccountPopup();
        dismissProjectSelectionDialog();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (showingTemplates && selectedTemplate != null) {
            showTemplatesPage(templatesPage);
            return;
        }
        if (showingSettings || showingTemplates) {
            showHomePage();
            return;
        }
        super.onBackPressed();
    }

    private void showHomePage() {
        dismissAccountPopup();
        dismissProjectSelectionDialog();
        showingSettings = false;
        showingTemplates = false;
        selectedTemplate = null;
        resetViewReferences();
        setContentView(buildHomeContent());
        if (PortalStore.isSignedIn(this)) {
            loadPortalHome(PortalStore.organizationId(this));
        }
    }

    private void showSettingsPage() {
        dismissAccountPopup();
        dismissProjectSelectionDialog();
        showingSettings = true;
        showingTemplates = false;
        resetViewReferences();
        setContentView(buildSettingsContent());
        refreshPeerId();
        refreshPermissionStatus();
        refreshProxyStatus();
        refreshUsbStatus();
        refreshStepMarkers();
    }

    private void showTemplatesPage() {
        showTemplatesPage(1);
    }

    private void showTemplatesPage(int page) {
        dismissAccountPopup();
        dismissProjectSelectionDialog();
        showingSettings = false;
        showingTemplates = true;
        selectedTemplate = null;
        templatesPage = Math.max(1, page);
        resetViewReferences();
        setContentView(buildTemplatesContent());
        loadTemplates(templatesPage);
    }

    private void resetViewReferences() {
        peerIdText = null;
        permissionStatusText = null;
        proxyStateText = null;
        proxyDetailText = null;
        usbStatusText = null;
        proxyStateDot = null;
        usbStateDot = null;
        proxyToggleButton = null;
        statusText = null;
        loginEmail = null;
        projectContent = null;
        templatesContent = null;
        templateDetailContent = null;
        workspaceNameInput = null;
        workspaceSizeSpinner = null;
        for (int index = 0; index < stepTabButtons.length; index++) {
            stepTabButtons[index] = null;
            stepPanels[index] = null;
        }
    }

    private View buildHomeContent() {
        LinearLayout content = pageContent();
        content.addView(homeHeader(), margins(0, 0, 0, 22));

        if (!PortalStore.isSignedIn(this)) {
            content.addView(loginCard());
            statusText = statusLabel(getString(R.string.login_status_signed_out));
            content.addView(statusText, margins(0, 16, 0, 0));
            return scroll(content);
        }

        projectContent = new LinearLayout(this);
        projectContent.setOrientation(LinearLayout.VERTICAL);
        projectContent.addView(description(R.string.portal_loading));
        content.addView(projectContent);

        statusText = statusLabel(getString(R.string.portal_loading));
        content.addView(statusText, margins(0, 22, 0, 0));
        return scroll(content);
    }

    private View loginCard() {
        LinearLayout card = card();
        card.addView(cardTitle(R.string.login_title));
        card.addView(description(R.string.login_description), margins(0, 5, 0, 12));
        loginEmail = new EditText(this);
        loginEmail.setHint(R.string.login_email_hint);
        loginEmail.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        loginEmail.setSingleLine(true);
        loginEmail.setTextSize(15);
        loginEmail.setPadding(dp(12), dp(10), dp(12), dp(10));
        loginEmail.setBackground(roundRect(color(R.color.edgez_blue_soft), 12));
        card.addView(loginEmail);
        card.addView(actionButton(R.string.login_send_link, view -> requestMagicLink(), true),
                margins(0, 12, 0, 0));
        return card;
    }

    private View buildTemplatesContent() {
        LinearLayout content = pageContent();
        content.addView(subpageHeader(R.string.templates_title), margins(0, 0, 0, 18));
        content.addView(cardTitle(R.string.templates_title));
        content.addView(description(R.string.templates_description), margins(0, 4, 0, 14));
        templatesContent = new LinearLayout(this);
        templatesContent.setOrientation(LinearLayout.VERTICAL);
        templatesContent.addView(description(R.string.templates_loading));
        content.addView(templatesContent);
        statusText = statusLabel(getString(R.string.templates_loading));
        content.addView(statusText, margins(0, 18, 0, 0));
        return scroll(content);
    }

    private void loadTemplates(int page) {
        runTask(getString(R.string.templates_loading), () -> {
            JSONObject response = PortalStore.loadTemplates(this, page, 8);
            runOnUiThread(() -> applyTemplates(response));
        });
    }

    private void applyTemplates(JSONObject response) {
        if (!showingTemplates || templatesContent == null) return;
        int page = Math.max(1, response.optInt("page", 1));
        templatesPage = page;
        int totalPages = Math.max(1, response.optInt("totalPages", 1));
        int total = Math.max(0, response.optInt("total", 0));
        JSONArray templates = response.optJSONArray("templates");
        templatesContent.removeAllViews();
        if (templates == null || templates.length() == 0) {
            templatesContent.addView(description(R.string.templates_empty));
        } else {
            for (int index = 0; index < templates.length(); index += 2) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                JSONObject left = templates.optJSONObject(index);
                JSONObject right = templates.optJSONObject(index + 1);
                if (left != null) {
                    row.addView(templateCard(left), gridTileMargins(0, 6));
                }
                if (right != null) {
                    row.addView(templateCard(right), gridTileMargins(6, 0));
                } else {
                    row.addView(new View(this), gridTileMargins(6, 0));
                }
                templatesContent.addView(row, margins(0, 0, 0, 12));
            }
        }

        LinearLayout pagination = new LinearLayout(this);
        pagination.setOrientation(LinearLayout.HORIZONTAL);
        pagination.setGravity(Gravity.CENTER_VERTICAL);
        Button previous = actionButton(R.string.templates_previous,
                view -> loadTemplates(page - 1), false);
        previous.setEnabled(page > 1);
        pagination.addView(previous, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView pageLabel = text(getString(R.string.templates_page, page, totalPages),
                12, color(R.color.edgez_text_muted));
        pageLabel.setGravity(Gravity.CENTER);
        pagination.addView(pageLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button next = actionButton(R.string.templates_next,
                view -> loadTemplates(page + 1), false);
        next.setEnabled(page < totalPages);
        pagination.addView(next, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        templatesContent.addView(pagination, margins(0, 2, 0, 0));
        showStatus(getString(R.string.templates_loaded, total));
    }

    private View templateCard(JSONObject template) {
        LinearLayout card = verticalPanel(14, roundRect(color(R.color.edgez_surface), 18));
        TextView icon = text(template.optBoolean("mobile") ? "APP"
                : template.optBoolean("firmware") ? "IoT" : "CODE",
                14, Color.WHITE);
        icon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundRect(color(R.color.edgez_blue), 14));
        card.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        TextView title = text(template.optString("name", getString(R.string.template_title)),
                16, color(R.color.edgez_text));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title, margins(0, 12, 0, 0));
        String description = template.optString("description", "").trim();
        if (!description.isEmpty()) {
            TextView copy = text(description, 12, color(R.color.edgez_text_muted));
            copy.setMaxLines(3);
            copy.setEllipsize(android.text.TextUtils.TruncateAt.END);
            copy.setMinHeight(dp(58));
            card.addView(copy, margins(0, 5, 0, 9));
        }
        JSONObject baseImage = template.optJSONObject("baseImage");
        String baseName = baseImage == null ? "" : baseImage.optString("name", "");
        String framework = template.optString("framework", "");
        String capabilities = templateCapabilities(template);
        card.addView(text(getString(R.string.template_meta, baseName, framework,
                        capabilities.isEmpty() ? getString(R.string.template_general) : capabilities),
                11, color(R.color.edgez_blue)));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> openTemplateDetail(template));
        return card;
    }

    private String templateCapabilities(JSONObject template) {
        List<String> capabilities = new ArrayList<>();
        if (template.optBoolean("frontend")) capabilities.add("Frontend");
        if (template.optBoolean("backend")) capabilities.add("Backend");
        if (template.optBoolean("mobile")) capabilities.add("Mobile");
        if (template.optBoolean("firmware")) capabilities.add("IoT");
        return String.join(" · ", capabilities);
    }

    private void openTemplateDetail(JSONObject template) {
        selectedTemplate = template;
        resetViewReferences();
        setContentView(buildTemplateDetailContent(template));
        runTask(getString(R.string.template_detail_loading), () -> {
            JSONObject response = PortalStore.loadTemplate(this,
                    template.optString("id", ""), PortalStore.organizationId(this));
            runOnUiThread(() -> applyTemplateDetail(response));
        });
    }

    private View buildTemplateDetailContent(JSONObject template) {
        LinearLayout content = pageContent();
        content.addView(subpageHeader(R.string.template_detail_title,
                view -> showTemplatesPage(templatesPage)), margins(0, 0, 0, 18));
        templateDetailContent = new LinearLayout(this);
        templateDetailContent.setOrientation(LinearLayout.VERTICAL);
        TextView initialTitle = text(template.optString(
                "name", getString(R.string.template_title)), 20, color(R.color.edgez_text));
        initialTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        templateDetailContent.addView(initialTitle);
        templateDetailContent.addView(description(R.string.template_detail_loading),
                margins(0, 6, 0, 0));
        content.addView(templateDetailContent);
        statusText = statusLabel(getString(R.string.template_detail_loading));
        content.addView(statusText, margins(0, 18, 0, 0));
        return scroll(content);
    }

    private void applyTemplateDetail(JSONObject response) {
        if (!showingTemplates || templateDetailContent == null) return;
        JSONObject template = response.optJSONObject("template");
        if (template == null) return;
        selectedTemplate = template;
        allowedWorkspaceSizes = response.optJSONArray("allowedWorkspaceSizes");
        if (allowedWorkspaceSizes == null) allowedWorkspaceSizes = new JSONArray();
        templateDetailContent.removeAllViews();

        TextView title = text(template.optString("name", getString(R.string.template_title)),
                24, color(R.color.edgez_text));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        templateDetailContent.addView(title);
        String copy = template.optString("description", "").trim();
        if (!copy.isEmpty()) {
            templateDetailContent.addView(text(copy, 14, color(R.color.edgez_text_muted)),
                    margins(0, 8, 0, 14));
        }
        JSONObject baseImage = template.optJSONObject("baseImage");
        templateDetailContent.addView(text(getString(R.string.template_details,
                        templateCapabilities(template), template.optString("framework", ""),
                        baseImage == null ? "" : baseImage.optString("name", ""),
                        template.optString("minimumWorkspaceSize", "small")),
                13, color(R.color.edgez_text)), margins(0, 0, 0, 18));

        LinearLayout deploy = card();
        deploy.addView(cardTitle(R.string.template_deploy_title));
        deploy.addView(description(template.optBoolean("mobileWorkspace")
                ? R.string.template_deploy_mobile_description
                : R.string.template_deploy_description), margins(0, 5, 0, 12));
        workspaceNameInput = new EditText(this);
        workspaceNameInput.setSingleLine(true);
        workspaceNameInput.setHint(R.string.template_workspace_name);
        workspaceNameInput.setText(defaultWorkspaceName(template));
        workspaceNameInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        workspaceNameInput.setBackground(roundRect(color(R.color.edgez_blue_soft), 12));
        deploy.addView(workspaceNameInput);

        workspaceSizeSpinner = portalSpinner();
        List<String> sizes = new ArrayList<>();
        for (int index = 0; index < allowedWorkspaceSizes.length(); index++) {
            String size = allowedWorkspaceSizes.optString(index, "");
            if (!size.isEmpty()) sizes.add(size);
        }
        workspaceSizeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, sizes));
        deploy.addView(workspaceSizeSpinner, margins(0, 10, 0, 0));
        Button deployButton = actionButton(R.string.template_deploy_button,
                view -> confirmTemplateDeployment(), true);
        deployButton.setEnabled(!sizes.isEmpty());
        deploy.addView(deployButton, margins(0, 12, 0, 0));
        templateDetailContent.addView(deploy);
        showStatus(getString(R.string.template_detail_ready));
    }

    private String defaultWorkspaceName(JSONObject template) {
        String slug = template.optString("slug", template.optString("name", "workspace"))
                .toLowerCase().replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "workspace" : slug;
    }

    private void confirmTemplateDeployment() {
        if (selectedTemplate == null || workspaceNameInput == null || workspaceSizeSpinner == null) {
            return;
        }
        String projectId = PortalStore.projectId(this);
        if (projectId.isEmpty()) {
            showStatus(getString(R.string.choose_project_prompt));
            return;
        }
        String message = selectedTemplate.optBoolean("mobileWorkspace")
                ? getString(R.string.template_deploy_confirm_mobile)
                : getString(R.string.template_deploy_confirm);
        new AlertDialog.Builder(this)
                .setTitle(R.string.template_deploy_title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.template_deploy_button,
                        (dialog, which) -> deploySelectedTemplate())
                .show();
    }

    private void deploySelectedTemplate() {
        String name = workspaceNameInput == null ? "" : workspaceNameInput.getText().toString().trim();
        String size = workspaceSizeSpinner == null || workspaceSizeSpinner.getSelectedItem() == null
                ? "" : workspaceSizeSpinner.getSelectedItem().toString();
        if (name.isEmpty() || size.isEmpty() || selectedTemplate == null) {
            showStatus(getString(R.string.template_deploy_invalid));
            return;
        }
        runTask(getString(R.string.template_deploying), () -> {
            JSONObject result = PortalStore.deployTemplate(this,
                    PortalStore.organizationId(this), PortalStore.projectId(this),
                    selectedTemplate.optString("id", ""), name, size,
                    ConfigStore.storedSerial(this), ConfigStore.peerId(this));
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle(R.string.template_deploy_success_title)
                    .setMessage(getString(R.string.template_deploy_success,
                            result.optString("name", name)))
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> showHomePage())
                    .show());
        });
    }

    private Spinner portalSpinner() {
        Spinner spinner = new Spinner(this);
        spinner.setBackground(roundRect(color(R.color.edgez_blue_soft), 12));
        spinner.setPadding(dp(8), dp(4), dp(8), dp(4));
        spinner.setMinimumHeight(dp(48));
        return spinner;
    }

    private void addProjectApps(LinearLayout content, JSONObject project) {
        TextView sectionTitle = cardTitle(R.string.apps_title);
        content.addView(sectionTitle);
        content.addView(description(R.string.apps_description), margins(0, 4, 0, 14));

        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        firstRow.addView(appTile("MAP", R.string.demo_app_title,
                R.string.demo_app_description, R.string.app_badge_embedded,
                view -> openEmbeddedDemo()), gridTileMargins(0, 6));
        firstRow.addView(appTile("8081", R.string.metro_app_title,
                R.string.metro_app_description, R.string.app_badge_remote,
                view -> openExpoProject()), gridTileMargins(6, 0));
        content.addView(firstRow);

        LinearLayout secondRow = new LinearLayout(this);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        secondRow.addView(appTile("EXPO", R.string.expo_projects_title,
                R.string.expo_projects_description, R.string.app_badge_projects,
                view -> openExpoLauncher()), gridTileMargins(0, 6));
        View spacer = new View(this);
        secondRow.addView(spacer, gridTileMargins(6, 0));
        content.addView(secondRow, margins(0, 12, 0, 0));

        JSONArray workspaces = project.optJSONArray("workspaces");
        if (workspaces == null || workspaces.length() == 0) {
            return;
        }
        for (int index = 0; index < workspaces.length(); index++) {
            JSONObject workspace = workspaces.optJSONObject(index);
            if (workspace != null) {
                content.addView(workspaceCard(workspace), margins(0, 12, 0, 0));
            }
        }
    }

    private View workspaceCard(JSONObject workspace) {
        LinearLayout card = card();
        TextView title = text(workspace.optString("name", getString(R.string.workspace_title)),
                16, color(R.color.edgez_text));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);
        String state = workspace.optString("desiredState", "stopped");
        String repository = workspace.optString("githubRepositoryFullName", "");
        card.addView(text(state + (repository.isEmpty() ? "" : " · " + repository),
                12, color(R.color.edgez_text_muted)), margins(0, 4, 0, 0));
        return card;
    }

    private TextView statusLabel(String value) {
        TextView status = text(value, 13, color(R.color.edgez_text));
        status.setTextIsSelectable(true);
        status.setPadding(dp(14), dp(13), dp(14), dp(13));
        status.setBackground(roundRect(color(R.color.edgez_status_background), 14));
        return status;
    }

    private void requestMagicLink() {
        String email = loginEmail == null ? "" : loginEmail.getText().toString().trim();
        if (email.isEmpty() || !email.contains("@")) {
            showStatus(getString(R.string.login_invalid_email));
            return;
        }
        runTask(getString(R.string.login_sending), () -> {
            PortalStore.requestMagicLink(email);
            showStatusFromWorker(getString(R.string.login_check_email));
        });
    }

    private void handleAuthIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"edgez-devtools".equals(data.getScheme())
                || !"auth-callback".equals(data.getHost())) {
            return;
        }
        intent.setData(null);
        String error = data.getQueryParameter("error");
        String code = data.getQueryParameter("code");
        if (error != null && !error.isEmpty()) {
            showStatus(getString(R.string.login_failed, error));
            return;
        }
        if (code == null || code.isEmpty()) {
            showStatus(getString(R.string.login_failed, "missing login code"));
            return;
        }
        runTask(getString(R.string.login_finishing), () -> {
            PortalStore.exchangeCode(this, code);
            runOnUiThread(this::showHomePage);
        });
    }

    private void loadPortalHome(String organizationId) {
        runTask(getString(R.string.portal_loading), () -> {
            JSONObject response = PortalStore.loadHome(this, organizationId);
            runOnUiThread(() -> applyPortalHome(response));
        });
    }

    private void applyPortalHome(JSONObject response) {
        organizations = response.optJSONArray("organizations");
        projects = response.optJSONArray("projects");
        if (organizations == null) organizations = new JSONArray();
        if (projects == null) projects = new JSONArray();
        String selectedOrganizationId = response.optString("selectedOrganizationId", "");
        if (!selectedOrganizationId.isEmpty()) {
            PortalStore.setOrganizationId(this, selectedOrganizationId);
        }
        if (projectContent == null) return;
        if (projects.length() == 0) {
            projectContent.removeAllViews();
            projectContent.addView(description(R.string.no_projects));
            PortalStore.setProjectId(this, "");
            showStatus(getString(R.string.no_projects));
        } else {
            int selected = projectIndex(PortalStore.projectId(this));
            if (selected >= 0) {
                renderSelectedProject(selected);
                showStatus(getString(R.string.home_status_ready));
            } else {
                PortalStore.setProjectId(this, "");
                projectContent.removeAllViews();
                projectContent.addView(description(R.string.choose_project_prompt));
                showStatus(getString(R.string.choose_project_prompt));
                projectContent.post(() -> showProjectSelectionDialog(true));
            }
        }
    }

    private int projectIndex(String projectId) {
        for (int index = 0; index < projects.length(); index++) {
            JSONObject project = projects.optJSONObject(index);
            if (project != null && projectId.equals(project.optString("id", ""))) return index;
        }
        return -1;
    }

    private int organizationIndex(String organizationId) {
        for (int index = 0; index < organizations.length(); index++) {
            JSONObject organization = organizations.optJSONObject(index);
            if (organization != null
                    && organizationId.equals(organization.optString("id", ""))) return index;
        }
        return 0;
    }

    private List<String> organizationLabels() {
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < organizations.length(); index++) {
            JSONObject organization = organizations.optJSONObject(index);
            if (organization != null) labels.add(organization.optString(
                    "name", organization.optString("id", "Organization")));
        }
        return labels;
    }

    private List<String> projectLabels() {
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < projects.length(); index++) {
            JSONObject project = projects.optJSONObject(index);
            if (project != null) labels.add(project.optString(
                    "name", project.optString("id", "Project")));
        }
        return labels;
    }

    private void configureOrganizationSpinner(Spinner spinner) {
        updatingPortalSelectors = true;
        List<String> labels = organizationLabels();
        spinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        if (!labels.isEmpty()) spinner.setSelection(
                organizationIndex(PortalStore.organizationId(this)), false);
        updatingPortalSelectors = false;
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingPortalSelectors) return;
                JSONObject organization = organizations.optJSONObject(position);
                if (organization == null) return;
                String nextId = organization.optString("id", "");
                if (!nextId.isEmpty() && !nextId.equals(PortalStore.organizationId(MainActivity.this))) {
                    dismissAccountPopup();
                    dismissProjectSelectionDialog();
                    PortalStore.setOrganizationId(MainActivity.this, nextId);
                    if (projectContent != null) {
                        projectContent.removeAllViews();
                        projectContent.addView(description(R.string.portal_loading));
                    }
                    loadPortalHome(nextId);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void configureProjectSpinner(Spinner spinner, boolean switchOnSelection) {
        updatingPortalSelectors = true;
        List<String> labels = projectLabels();
        spinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        int selected = projectIndex(PortalStore.projectId(this));
        if (!labels.isEmpty()) spinner.setSelection(Math.max(0, selected), false);
        updatingPortalSelectors = false;
        if (!switchOnSelection) return;
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingPortalSelectors) return;
                JSONObject project = projects.optJSONObject(position);
                if (project == null || project.optString("id", "")
                        .equals(PortalStore.projectId(MainActivity.this))) return;
                renderSelectedProject(position);
                dismissAccountPopup();
                showStatus(getString(R.string.home_status_ready));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private LinearLayout scopeSelectorPanel(Spinner organization, Spinner project) {
        LinearLayout panel = verticalPanel(16, roundRect(color(R.color.edgez_surface), 18));
        panel.addView(text(getString(R.string.organization_label), 12,
                color(R.color.edgez_text_muted)));
        panel.addView(organization, margins(0, 3, 0, 12));
        panel.addView(text(getString(R.string.project_label), 12,
                color(R.color.edgez_text_muted)));
        panel.addView(project, margins(0, 3, 0, 0));
        return panel;
    }

    private void showProjectSelectionDialog(boolean required) {
        if (showingSettings || projects.length() == 0 || isFinishing() || isDestroyed()
                || (projectSelectionDialog != null && projectSelectionDialog.isShowing())) return;
        Spinner organization = portalSpinner();
        Spinner project = portalSpinner();
        configureOrganizationSpinner(organization);
        configureProjectSpinner(project, false);
        projectSelectionDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.choose_project_title)
                .setMessage(R.string.choose_project_description)
                .setView(scopeSelectorPanel(organization, project))
                .setPositiveButton(R.string.open_project, (dialog, which) -> {
                    renderSelectedProject(project.getSelectedItemPosition());
                    showStatus(getString(R.string.home_status_ready));
                })
                .setOnDismissListener(dialog -> projectSelectionDialog = null)
                .create();
        projectSelectionDialog.setCanceledOnTouchOutside(!required);
        projectSelectionDialog.setCancelable(!required);
        projectSelectionDialog.show();
    }

    private void dismissProjectSelectionDialog() {
        if (projectSelectionDialog != null) {
            projectSelectionDialog.dismiss();
            projectSelectionDialog = null;
        }
    }

    private void showAccountDropdown(View anchor) {
        dismissAccountPopup();
        JSONObject user = PortalStore.user(this);
        LinearLayout panel = verticalPanel(16, roundRect(color(R.color.edgez_surface), 18));
        String name = user.optString("name", "").trim();
        TextView title = text(name.isEmpty() ? getString(R.string.account_title) : name,
                17, color(R.color.edgez_text));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        panel.addView(title);
        panel.addView(text(user.optString("email", ""), 12,
                color(R.color.edgez_text_muted)), margins(0, 2, 0, 12));
        Spinner organization = portalSpinner();
        Spinner project = portalSpinner();
        configureOrganizationSpinner(organization);
        configureProjectSpinner(project, true);
        panel.addView(scopeSelectorPanel(organization, project));
        panel.addView(actionButton(R.string.templates_button, view -> showTemplatesPage(), false),
                margins(0, 12, 0, 0));
        panel.addView(actionButton(R.string.settings_button, view -> showSettingsPage(), false),
                margins(0, 8, 0, 0));
        panel.addView(actionButton(R.string.sign_out, view -> signOut(), false),
                margins(0, 8, 0, 0));
        accountPopup = new PopupWindow(panel, dp(320), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        accountPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        accountPopup.setOutsideTouchable(true);
        accountPopup.setElevation(dp(10));
        accountPopup.setOnDismissListener(() -> accountPopup = null);
        accountPopup.showAsDropDown(anchor, anchor.getWidth() - dp(320), dp(4));
    }

    private void dismissAccountPopup() {
        if (accountPopup != null) {
            accountPopup.dismiss();
            accountPopup = null;
        }
    }

    private void renderSelectedProject(int position) {
        JSONObject project = projects.optJSONObject(position);
        if (project == null || projectContent == null) return;
        PortalStore.setSelection(this, PortalStore.organizationId(this),
                project.optString("id", ""));
        projectContent.removeAllViews();
        addProjectApps(projectContent, project);
    }

    private void signOut() {
        dismissAccountPopup();
        dismissProjectSelectionDialog();
        PortalStore.signOut(this);
        organizations = new JSONArray();
        projects = new JSONArray();
        showHomePage();
    }

    private View buildSettingsContent() {
        LinearLayout content = pageContent();
        content.addView(settingsHeader(), margins(0, 0, 0, 10));

        LinearLayout connection = connectionCard();
        content.addView(connection, margins(0, 0, 0, 12));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        stepTabButtons[0] = stepTab(0, R.string.step_tab_permissions);
        stepTabButtons[1] = stepTab(1, R.string.step_tab_join);
        stepTabButtons[2] = stepTab(2, R.string.step_tab_pair);
        tabs.addView(stepTabButtons[0], weightedButtonMargins(0, 4));
        tabs.addView(stepTabButtons[1], weightedButtonMargins(4, 4));
        tabs.addView(stepTabButtons[2], weightedButtonMargins(4, 0));
        content.addView(tabs, margins(0, 0, 0, 10));

        LinearLayout permissions = card();
        permissions.addView(stepTitle("1", R.string.permissions_title));
        permissions.addView(description(R.string.permissions_description));
        permissionStatusText = text("", 13, color(R.color.edgez_text_muted));
        permissions.addView(permissionStatusText, margins(0, 12, 0, 4));
        permissions.addView(actionButton(R.string.grant_permissions,
                view -> requestRequiredPermissions(), false));
        stepPanels[0] = permissions;
        content.addView(permissions, margins(0, 0, 0, 12));

        LinearLayout join = card();
        join.addView(stepTitle("2", R.string.join_title));
        join.addView(description(R.string.join_description));
        join.addView(actionButton(R.string.scan_qr_join, view -> scanPairingQr(), true));
        stepPanels[1] = join;
        content.addView(join, margins(0, 0, 0, 12));

        LinearLayout wireless = card();
        wireless.addView(stepTitle("3", R.string.wireless_title));
        wireless.addView(description(R.string.wireless_description));
        wireless.addView(actionButton(R.string.pair_notification,
                view -> beginNotificationPairing(), false));
        stepPanels[2] = wireless;
        content.addView(wireless, margins(0, 0, 0, 12));

        statusText = text(getString(R.string.status_ready), 13, color(R.color.edgez_text));
        statusText.setTextIsSelectable(true);
        statusText.setPadding(dp(14), dp(13), dp(14), dp(13));
        statusText.setBackground(roundRect(color(R.color.edgez_status_background), 14));
        content.addView(statusText);
        showStep(selectedStep);
        return scroll(content);
    }

    private LinearLayout pageContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(24));
        content.setBackgroundColor(color(R.color.edgez_background));
        return content;
    }

    private View homeHeader() {
        LinearLayout hero = verticalPanel(16, gradient(
                color(R.color.edgez_blue_dark), color(R.color.edgez_blue), 18));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.hero_title), 24, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (PortalStore.isSignedIn(this)) {
            JSONObject user = PortalStore.user(this);
            String name = user.optString("name", "").trim();
            Button account = compactButton(R.string.account_title, view -> { });
            account.setText((name.isEmpty() ? getString(R.string.account_title) : name) + " ▾");
            account.setOnClickListener(this::showAccountDropdown);
            row.addView(account);
        } else {
            Button settings = compactButton(R.string.settings_button, view -> showSettingsPage());
            row.addView(settings);
        }
        hero.addView(row);
        TextView subtitle = text(getString(R.string.apps_hero_subtitle), 13, 0xFFE4EEFF);
        subtitle.setLineSpacing(0, 1.05f);
        hero.addView(subtitle, margins(0, 4, 0, 0));
        return hero;
    }

    private View settingsHeader() {
        return subpageHeader(R.string.settings_title);
    }

    private View subpageHeader(int titleLabel) {
        return subpageHeader(titleLabel, view -> showHomePage());
    }

    private View subpageHeader(int titleLabel, View.OnClickListener backListener) {
        LinearLayout hero = verticalPanel(14, gradient(
                color(R.color.edgez_blue_dark), color(R.color.edgez_blue), 18));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button back = compactButton(R.string.back_to_apps, backListener);
        row.addView(back);
        TextView title = text(getString(titleLabel), 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.END);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        hero.addView(row);
        return hero;
    }

    private LinearLayout connectionCard() {
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

        LinearLayout usbRow = new LinearLayout(this);
        usbRow.setOrientation(LinearLayout.HORIZONTAL);
        usbRow.setGravity(Gravity.CENTER_VERTICAL);
        usbStateDot = new View(this);
        LinearLayout.LayoutParams usbDotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        usbDotParams.setMargins(dp(2), 0, dp(12), 0);
        usbRow.addView(usbStateDot, usbDotParams);
        usbStatusText = text(getString(R.string.usb_status_unavailable), 13,
                color(R.color.edgez_text_muted));
        usbRow.addView(usbStatusText);
        connection.addView(usbRow, margins(0, 12, 0, 0));

        proxyToggleButton = actionButton(
                R.string.start_proxy, view -> toggleProxy(), true);
        connection.addView(proxyToggleButton, margins(0, 12, 0, 0));
        connection.addView(actionButton(
                R.string.open_expo_project, view -> openExpoProject(), false),
                margins(0, 8, 0, 0));
        return connection;
    }

    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        return scroll;
    }

    private View appTile(String icon, int titleLabel, int descriptionLabel, int badgeLabel,
            View.OnClickListener listener) {
        LinearLayout tile = verticalPanel(14, roundRect(color(R.color.edgez_surface), 18));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(listener);
        TextView iconView = text(icon, icon.length() > 3 ? 13 : 18, Color.WHITE);
        iconView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(roundRect(color(R.color.edgez_blue), 14));
        tile.addView(iconView, new LinearLayout.LayoutParams(dp(54), dp(54)));
        TextView title = text(getString(titleLabel), 16, color(R.color.edgez_text));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tile.addView(title, margins(0, 12, 0, 0));
        TextView description = text(getString(descriptionLabel), 12,
                color(R.color.edgez_text_muted));
        description.setMinHeight(dp(54));
        tile.addView(description, margins(0, 5, 0, 8));
        TextView badge = text(getString(badgeLabel), 11, color(R.color.edgez_blue));
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tile.addView(badge);
        return tile;
    }

    private Button compactButton(int label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
        button.setMinHeight(dp(40));
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setOnClickListener(listener);
        return button;
    }

    private Button compactLightButton(int label, View.OnClickListener listener) {
        Button button = compactButton(label, listener);
        button.setTextColor(color(R.color.edgez_blue));
        button.setBackgroundTintList(ColorStateList.valueOf(color(R.color.edgez_blue_soft)));
        return button;
    }

    private LinearLayout.LayoutParams gridTileMargins(int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(left), 0, dp(right), 0);
        return params;
    }

    private void openEmbeddedDemo() {
        try {
            Intent intent = new Intent()
                    .setClassName(getPackageName(),
                            "ai.edgez.androiddevtools.runtime.EmbeddedBundleActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            showStatus(getString(R.string.status_opening_demo));
        } catch (ActivityNotFoundException exception) {
            showStatus(getString(R.string.status_expo_unavailable));
        }
    }

    private void openExpoLauncher() {
        try {
            Class<?> launcher = Class.forName(
                    "expo.modules.devlauncher.launcher.DevLauncherActivity");
            startActivity(new Intent(this, launcher)
                    .putExtra("edgez.rootTab", "home")
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            showStatus(getString(R.string.status_opening_projects));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            showStatus(getString(R.string.status_expo_unavailable));
        }
    }

    private void openExpoProject() {
        Uri projectUri = Uri.parse("exp://127.0.0.1:8081");
        Uri developmentClientUri = new Uri.Builder()
                .scheme("exp+edgez-android-devtools")
                .authority("expo-development-client")
                .appendQueryParameter("url", "http://127.0.0.1:8081")
                .build();
        Intent bundledRuntime = new Intent(Intent.ACTION_VIEW, developmentClientUri)
                .setPackage(getPackageName());
        try {
            startActivity(bundledRuntime);
            statusText.setText(R.string.status_opening_expo);
            return;
        } catch (ActivityNotFoundException ignored) {
            // The standalone EdgeZ build may use a separately installed Expo Go runtime.
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, projectUri));
            statusText.setText(R.string.status_opening_expo);
        } catch (ActivityNotFoundException exception) {
            statusText.setText(R.string.status_expo_unavailable);
        }
    }

    @SuppressWarnings("deprecation")
    private void scanPairingQr() {
        showStatus(getString(R.string.status_scan_prompt));
        try {
            new IntentIntegrator(this)
                    .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt(getString(R.string.status_scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .initiateScan();
        } catch (RuntimeException error) {
            showStatus(getString(R.string.status_scan_failed, safeMessage(error)));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(
                requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() == null) {
                showStatus(getString(R.string.status_scan_canceled));
            } else {
                joinFromPairingQr(result.getContents());
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
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
                refreshStepMarkers();
                showStep(2);
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
        if (!isWirelessDebugEnabled()) {
            maybeShowWirelessDebugDialog(true);
            return;
        }
        ProxyService.start(this);
        showStatus(getString(R.string.status_proxy_start));
    }

    private void toggleProxy() {
        if (ProxyService.isRunning()) {
            ProxyService.stop(this);
            showStatus(getString(R.string.status_proxy_stop_requested));
            return;
        }
        startProxy();
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
        if (!force || !hasRuntimePermissions()) {
            return;
        }
        boolean wirelessDebugEnabled = isWirelessDebugEnabled();
        String proxyState = ProxyStatus.current(this).state;
        if (wirelessDebugEnabled && (ProxyStatus.CONNECTING.equals(proxyState)
                || ProxyStatus.MESH_ONLINE.equals(proxyState)
                || ProxyStatus.ADB_ONLINE.equals(proxyState))) {
            return;
        }
        if (isFinishing() || isDestroyed()
                || (wirelessDebugDialog != null && wirelessDebugDialog.isShowing())) {
            return;
        }
        int message = wirelessDebugEnabled
                ? R.string.wireless_enabled_missing : R.string.wireless_disabled;
        wirelessDebugDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.wireless_required_title)
                .setMessage(message)
                .setPositiveButton(R.string.open_wireless_debugging,
                        (dialog, which) -> openWirelessDebugging())
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
        if (peerIdText == null) {
            return;
        }
        String peerId = ConfigStore.peerId(this);
        peerIdText.setText(peerId.isEmpty()
                ? getString(R.string.peer_not_joined) : getString(R.string.peer_id_format, peerId));
        refreshStepMarkers();
    }

    private void refreshProxyStatus() {
        reconcileProxyStatus();
        ProxyStatus.Snapshot snapshot = ProxyStatus.current(this);
        updateProxyStatus(snapshot.state, snapshot.detail);
    }

    private void reconcileProxyStatus() {
        if (ProxyService.isRunning()) {
            return;
        }
        String state = ProxyStatus.current(this).state;
        if (ProxyStatus.CONNECTING.equals(state)
                || ProxyStatus.MESH_ONLINE.equals(state)
                || ProxyStatus.ADB_ONLINE.equals(state)
                || ProxyStatus.STOPPING.equals(state)) {
            ProxyStatus.publish(this, ProxyStatus.DISCONNECTED, "");
        }
    }

    private void refreshUsbStatus() {
        if (usbStatusText == null || usbStateDot == null) {
            return;
        }
        UsbManager usbManager = getSystemService(UsbManager.class);
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
        int dotColor;
        if (permitted > 0) {
            usbStatusText.setText(getString(R.string.usb_status_ready, permitted));
            dotColor = color(R.color.edgez_success);
        } else {
            usbStatusText.setText(attached > 0
                    ? R.string.usb_status_permission : R.string.usb_status_unavailable);
            dotColor = attached > 0
                    ? color(R.color.edgez_warning) : color(R.color.edgez_offline);
        }
        usbStateDot.setBackground(roundRect(dotColor, 99));
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
        updateProxyToggle(safeState);
        refreshStepMarkers();
    }

    private void updateProxyToggle(String state) {
        if (proxyToggleButton == null) {
            return;
        }
        boolean running = ProxyStatus.CONNECTING.equals(state)
                || ProxyStatus.MESH_ONLINE.equals(state)
                || ProxyStatus.ADB_ONLINE.equals(state);
        boolean stopping = ProxyStatus.STOPPING.equals(state);
        proxyToggleButton.setEnabled(!stopping);
        proxyToggleButton.setText(stopping
                ? R.string.proxy_button_stopping
                : running ? R.string.stop_proxy
                : ProxyStatus.ERROR.equals(state)
                        ? R.string.retry_proxy : R.string.start_proxy);
        proxyToggleButton.setTextColor(running
                ? color(R.color.edgez_blue) : Color.WHITE);
        proxyToggleButton.setBackgroundTintList(ColorStateList.valueOf(running
                ? color(R.color.edgez_blue_soft) : color(R.color.edgez_blue)));
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerProxyStatusReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ProxyStatus.ACTION_CHANGED);
        IntentFilter usbFilter = new IntentFilter(UsbIpServer.ACTION_USB_PERMISSION);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(proxyStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(usbStatusReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(proxyStatusReceiver, filter);
            registerReceiver(usbStatusReceiver, usbFilter);
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
        if (Build.VERSION.SDK_INT < 23) {
            refreshPermissionStatus();
            return;
        }
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
        } else {
            refreshPermissionStatus();
        }
    }

    private boolean hasRuntimePermissions() {
        boolean locationGranted = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        boolean bluetoothGranted = Build.VERSION.SDK_INT < 31
                || (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED);
        boolean android13Granted = Build.VERSION.SDK_INT < 33
                || (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                        == PackageManager.PERMISSION_GRANTED);
        return locationGranted && bluetoothGranted && android13Granted;
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
        refreshStepMarkers();
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

    private Button stepTab(int index, int label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(58));
        button.setPadding(dp(6), dp(7), dp(6), dp(7));
        button.setOnClickListener(view -> showStep(index));
        button.setTag(label);
        return button;
    }

    private void showStep(int index) {
        if (index < 0 || index >= stepPanels.length) {
            return;
        }
        selectedStep = index;
        for (int current = 0; current < stepPanels.length; current++) {
            View panel = stepPanels[current];
            if (panel != null) {
                panel.setVisibility(current == index ? View.VISIBLE : View.GONE);
            }
            Button tab = stepTabButtons[current];
            if (tab != null) {
                boolean selected = current == index;
                tab.setTextColor(selected ? Color.WHITE : color(R.color.edgez_blue));
                tab.setBackgroundTintList(ColorStateList.valueOf(selected
                        ? color(R.color.edgez_blue) : color(R.color.edgez_blue_soft)));
            }
        }
    }

    private void refreshStepMarkers() {
        boolean[] complete = {
                hasAllRequiredPermissions(),
                ConfigStore.isConfigured(this),
                ConfigStore.isConfigured(this) && ConfigStore.loadAdbEndpoint(this) != null
        };
        for (int index = 0; index < stepTabButtons.length; index++) {
            Button tab = stepTabButtons[index];
            if (tab == null) {
                continue;
            }
            int label = (Integer) tab.getTag();
            tab.setText((index + 1) + (complete[index] ? " ✅" : "")
                    + "\n" + getString(label));
        }
        showStep(selectedStep);
    }

    private boolean hasAllRequiredPermissions() {
        PowerManager powerManager = getSystemService(PowerManager.class);
        return hasRuntimePermissions()
                && powerManager.isIgnoringBatteryOptimizations(getPackageName());
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
