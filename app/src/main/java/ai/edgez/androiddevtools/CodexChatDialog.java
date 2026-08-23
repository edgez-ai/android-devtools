package ai.edgez.androiddevtools;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.text.method.LinkMovementMethod;
import android.media.MediaRecorder;
import android.os.Build;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.noties.markwon.Markwon;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class CodexChatDialog {
    private static final int MICROPHONE_PERMISSION_REQUEST = 4102;
    private static final String[] MODEL_IDS = {
            "gpt-5.6-luna",
            "gpt-5.6-terra",
            "gpt-5.6-sol"
    };
    private static final int INITIALIZE_ID = 1;
    private static final int THREAD_LIST_ID = 2;
    private static final int THREAD_START_ID = 3;
    private static final int THREAD_RESUME_ID = 4;
    private static final int THREAD_READ_ID = 5;

    private final Activity activity;
    private final JSONObject connection;
    private final OkHttpClient client;
    private final Markwon markwon;
    private final FrameLayout root;
    private final LinearLayout messages;
    private final ScrollView messageScroll;
    private final EditText input;
    private final Button send;
    private final Button inputModeToggle;
    private final Button holdToTalk;
    private final TextView connectionStatus;
    private final Map<String, JSONObject> threads = new LinkedHashMap<>();
    private WebSocket socket;
    private PopupWindow conversationDrawer;
    private MediaRecorder mediaRecorder;
    private File voiceRecordingFile;
    private String threadId;
    private String pendingPrompt;
    private TextView streamingAgentMessage;
    private StringBuilder streamingAgentMarkdown;
    private volatile String activeModel;
    private int nextRequestId = 10;
    private boolean closed;
    private boolean voiceMode;
    private boolean cancelVoiceMessage;
    private boolean voiceRecording;
    private float voiceGestureStartY;

    static CodexChatDialog attach(
            Activity activity,
            ViewGroup container,
            JSONObject connection,
            Spinner modelSelector) {
        CodexChatDialog client = new CodexChatDialog(activity, connection, modelSelector);
        client.attachTo(container);
        client.open();
        return client;
    }

    private CodexChatDialog(
            Activity activity, JSONObject connection, Spinner modelSelector) {
        this.activity = activity;
        this.connection = connection;
        this.activeModel = modelAt(modelSelector.getSelectedItemPosition());
        client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        markwon = Markwon.create(activity);
        root = new GestureFrameLayout(activity);
        root.setBackgroundColor(color(R.color.edgez_background));

        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(2), 0, dp(2), 0);
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        connectionStatus = text(activity.getString(R.string.workspace_codex_connecting),
                12, color(R.color.edgez_text_muted));
        connectionStatus.setGravity(Gravity.CENTER);
        page.addView(connectionStatus, margins(0, 0, 0, 4));

        messages = new LinearLayout(activity);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(2), dp(4), dp(2), dp(8));
        messageScroll = new ScrollView(activity);
        messageScroll.setFillViewport(true);
        messageScroll.addView(messages);
        page.addView(messageScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout composer = new LinearLayout(activity);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(8), dp(6), dp(6), dp(6));
        composer.setBackground(roundRect(Color.WHITE, 18));
        inputModeToggle = button(R.string.workspace_codex_voice_mode, false,
                view -> setVoiceMode(!voiceMode));
        inputModeToggle.setEnabled(false);
        composer.addView(inputModeToggle, new LinearLayout.LayoutParams(dp(62), dp(46)));
        input = new EditText(activity);
        input.setHint(R.string.workspace_codex_input_hint);
        input.setTextSize(15);
        input.setMinHeight(dp(46));
        input.setMaxLines(5);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setEnabled(false);
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEND) return false;
            sendPrompt();
            return true;
        });
        composer.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        holdToTalk = button(R.string.workspace_codex_hold_to_talk, false, view -> { });
        holdToTalk.setVisibility(View.GONE);
        holdToTalk.setEnabled(false);
        holdToTalk.setOnTouchListener((view, event) -> handleVoiceTouch(event));
        composer.addView(holdToTalk, new LinearLayout.LayoutParams(
                0, dp(46), 1));
        send = button(R.string.workspace_codex_send, true, view -> sendPrompt());
        send.setEnabled(false);
        composer.addView(send, new LinearLayout.LayoutParams(dp(76), dp(46)));
        page.addView(composer);

    }

    void attachTo(ViewGroup container) {
        ViewGroup parent = root.getParent() instanceof ViewGroup
                ? (ViewGroup) root.getParent() : null;
        if (parent != null) parent.removeView(root);
        container.removeAllViews();
        container.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrollToBottom();
    }

    void detach() {
        ViewGroup parent = root.getParent() instanceof ViewGroup
                ? (ViewGroup) root.getParent() : null;
        if (parent != null) parent.removeView(root);
        if (conversationDrawer != null) conversationDrawer.dismiss();
    }

    void setModelPosition(int position) {
        activeModel = modelAt(position);
    }

    private void open() {
        String url = connection.optString("webSocketUrl", "");
        String token = connection.optString("token", "");
        if (url.isEmpty() || token.isEmpty()) {
            fail(activity.getString(R.string.workspace_codex_invalid_connection));
            return;
        }
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + token)
                .build();
        socket = client.newWebSocket(request, new Listener());
    }

    void close() {
        if (closed) return;
        closed = true;
        if (conversationDrawer != null) conversationDrawer.dismiss();
        releaseVoiceRecorder(true);
        client.dispatcher().cancelAll();
        if (socket != null) socket.close(1000, "Mobile Codex chat closed");
        socket = null;
        client.dispatcher().executorService().shutdown();
    }

    private void initialize() throws JSONException {
        JSONObject clientInfo = new JSONObject()
                .put("name", "edgez_android_devtools")
                .put("title", "EdgeZ Android DevTools")
                .put("version", "1.0.0");
        sendRequest(INITIALIZE_ID, "initialize",
                new JSONObject().put("clientInfo", clientInfo));
    }

    private void initialized() throws JSONException {
        sendJson(new JSONObject().put("method", "initialized"));
        sendRequest(THREAD_LIST_ID, "thread/list", new JSONObject()
                .put("cursor", JSONObject.NULL)
                .put("limit", 50)
                .put("cwd", new JSONArray().put("/home/jovyan/workspace"))
                .put("sortKey", "updated_at"));
        activity.runOnUiThread(() -> {
            connectionStatus.setText(R.string.workspace_codex_connected);
            input.setEnabled(true);
            send.setEnabled(true);
            inputModeToggle.setEnabled(true);
            holdToTalk.setEnabled(true);
        });
    }

    private void setVoiceMode(boolean enabled) {
        voiceMode = enabled;
        inputModeToggle.setText(enabled
                ? R.string.workspace_codex_text_mode
                : R.string.workspace_codex_voice_mode);
        input.setVisibility(enabled ? View.GONE : View.VISIBLE);
        send.setVisibility(enabled ? View.GONE : View.VISIBLE);
        holdToTalk.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled && voiceRecording) {
            cancelVoiceMessage = true;
            releaseVoiceRecorder(true);
            resetVoiceControl();
        }
    }

    private boolean handleVoiceTouch(android.view.MotionEvent event) {
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                voiceGestureStartY = event.getRawY();
                startVoiceRecognition();
                return true;
            case android.view.MotionEvent.ACTION_MOVE:
                if (voiceRecording) {
                    cancelVoiceMessage = voiceGestureStartY - event.getRawY() >= dp(72);
                    holdToTalk.setText(cancelVoiceMessage
                            ? R.string.workspace_codex_release_to_cancel
                            : R.string.workspace_codex_release_to_send);
                }
                return true;
            case android.view.MotionEvent.ACTION_UP:
                finishVoiceRecognition();
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                cancelVoiceMessage = true;
                finishVoiceRecognition();
                return true;
            default:
                return false;
        }
    }

    private void startVoiceRecognition() {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            connectionStatus.setText(R.string.workspace_codex_microphone_permission);
            activity.requestPermissions(
                    new String[] { Manifest.permission.RECORD_AUDIO },
                    MICROPHONE_PERMISSION_REQUEST);
            return;
        }
        try {
            voiceRecordingFile = File.createTempFile(
                    "codex-voice-", ".m4a", activity.getCacheDir());
            mediaRecorder = Build.VERSION.SDK_INT >= 31
                    ? new MediaRecorder(activity)
                    : new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(64_000);
            mediaRecorder.setAudioSamplingRate(16_000);
            mediaRecorder.setMaxDuration(60_000);
            mediaRecorder.setOnInfoListener((recorder, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        && voiceRecording) {
                    cancelVoiceMessage = false;
                    activity.runOnUiThread(this::finishVoiceRecognition);
                }
            });
            mediaRecorder.setOutputFile(voiceRecordingFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            cancelVoiceMessage = false;
            voiceRecording = true;
            holdToTalk.setText(R.string.workspace_codex_release_to_send);
            connectionStatus.setText(R.string.workspace_codex_connected);
        } catch (IOException | RuntimeException error) {
            releaseVoiceRecorder(true);
            resetVoiceControl();
            connectionStatus.setText(R.string.workspace_codex_voice_unavailable);
        }
    }

    private void finishVoiceRecognition() {
        if (!voiceRecording || mediaRecorder == null) return;
        voiceRecording = false;
        if (cancelVoiceMessage) {
            releaseVoiceRecorder(true);
            resetVoiceControl();
        } else {
            holdToTalk.setText(R.string.workspace_codex_recognizing);
            holdToTalk.setEnabled(false);
            File recording = releaseVoiceRecorder(false);
            if (recording == null || recording.length() == 0) {
                resetVoiceControl();
                connectionStatus.setText(R.string.workspace_codex_voice_failed);
            } else {
                transcribeVoice(recording);
            }
        }
    }

    private File releaseVoiceRecorder(boolean deleteFile) {
        MediaRecorder recorder = mediaRecorder;
        mediaRecorder = null;
        File recording = voiceRecordingFile;
        voiceRecordingFile = null;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException error) {
                deleteFile = true;
            }
            recorder.reset();
            recorder.release();
        }
        if (deleteFile && recording != null) {
            //noinspection ResultOfMethodCallIgnored
            recording.delete();
            return null;
        }
        return recording;
    }

    private void transcribeVoice(File recording) {
        RequestBody audio = RequestBody.create(
                MediaType.get("audio/mp4"), recording);
        RequestBody multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", "voice.m4a", audio)
                .build();
        Request request = new Request.Builder()
                .url(PortalStore.BASE_URL + "/api/mobile/transcribe")
                .header("Authorization", "Bearer " + PortalStore.accessToken(activity))
                .post(multipart)
                .build();
        client.newBuilder()
                .callTimeout(90, TimeUnit.SECONDS)
                .readTimeout(75, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException error) {
                        //noinspection ResultOfMethodCallIgnored
                        recording.delete();
                        activity.runOnUiThread(() -> {
                            resetVoiceControl();
                            connectionStatus.setText(R.string.workspace_codex_voice_failed);
                        });
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        try (Response current = response) {
                            String payload = current.body() == null
                                    ? "" : current.body().string();
                            if (!current.isSuccessful()) {
                                throw new IOException("Transcription HTTP " + current.code());
                            }
                            String transcript = new JSONObject(payload).optString("text", "").trim();
                            if (transcript.isEmpty()) throw new IOException("Empty transcription");
                            activity.runOnUiThread(() -> {
                                resetVoiceControl();
                                connectionStatus.setText(R.string.workspace_codex_connected);
                                sendPrompt(transcript);
                            });
                        } catch (IOException | JSONException error) {
                            activity.runOnUiThread(() -> {
                                resetVoiceControl();
                                connectionStatus.setText(R.string.workspace_codex_voice_failed);
                            });
                        } finally {
                            //noinspection ResultOfMethodCallIgnored
                            recording.delete();
                        }
                    }
                });
    }

    private void resetVoiceControl() {
        voiceRecording = false;
        holdToTalk.setEnabled(true);
        holdToTalk.setText(R.string.workspace_codex_hold_to_talk);
    }

    private void sendPrompt() {
        sendPrompt(input.getText().toString());
    }

    private void sendPrompt(String value) {
        String prompt = value == null ? "" : value.trim();
        if (prompt.isEmpty() || socket == null) return;
        input.setText("");
        addMessage(prompt, true);
        send.setEnabled(false);
        try {
            if (threadId == null) {
                pendingPrompt = prompt;
                startThread();
                return;
            }
            startTurn(prompt);
        } catch (JSONException error) {
            fail(error.getMessage());
        }
    }

    private void startThread() throws JSONException {
        sendRequest(THREAD_START_ID, "thread/start", new JSONObject()
                .put("cwd", "/home/jovyan/workspace")
                .put("model", selectedModel()));
    }

    private void resumeThread(String id) {
        threadId = id;
        messages.removeAllViews();
        streamingAgentMessage = null;
        streamingAgentMarkdown = null;
        try {
            sendRequest(THREAD_RESUME_ID, "thread/resume",
                    new JSONObject().put("threadId", id));
        } catch (JSONException error) {
            fail(error.getMessage());
        }
    }

    private void readThread() throws JSONException {
        sendRequest(THREAD_READ_ID, "thread/read", new JSONObject()
                .put("threadId", threadId)
                .put("includeTurns", true));
    }

    private void startTurn(String prompt) throws JSONException {
        JSONArray content = new JSONArray().put(new JSONObject()
                .put("type", "text")
                .put("text", prompt));
        sendRequest(nextRequestId++, "turn/start", new JSONObject()
                .put("threadId", threadId)
                .put("model", selectedModel())
                .put("input", content));
    }

    private String selectedModel() {
        return activeModel;
    }

    private String modelAt(int position) {
        return MODEL_IDS[Math.max(0, Math.min(position, MODEL_IDS.length - 1))];
    }

    private void handle(JSONObject message) throws JSONException {
        if (message.has("id") && message.has("method")) {
            resolveServerRequest(message);
            return;
        }
        if (message.has("id")) {
            int id = message.optInt("id", -1);
            JSONObject error = message.optJSONObject("error");
            if (error != null) {
                fail(error.optString("message", "Codex request failed"));
                return;
            }
            JSONObject result = message.optJSONObject("result");
            if (id == INITIALIZE_ID) initialized();
            else if (id == THREAD_LIST_ID) applyThreadList(result);
            else if (id == THREAD_START_ID) applyStartedThread(result);
            else if (id == THREAD_RESUME_ID) readThread();
            else if (id == THREAD_READ_ID) renderThread(result);
            return;
        }

        String method = message.optString("method", "");
        JSONObject params = message.optJSONObject("params");
        if ("item/agentMessage/delta".equals(method) && params != null) {
            appendAgentDelta(params.optString("delta", ""));
        } else if ("item/completed".equals(method) && params != null) {
            JSONObject item = params.optJSONObject("item");
            if (item != null && "agentMessage".equals(item.optString("type", ""))) {
                String completedMarkdown = item.optString("text", "");
                activity.runOnUiThread(() -> {
                    if (streamingAgentMessage == null) {
                        addMessage(completedMarkdown, false);
                    } else if (!completedMarkdown.isEmpty()) {
                        streamingAgentMarkdown = new StringBuilder(completedMarkdown);
                        renderMarkdown(streamingAgentMessage, completedMarkdown);
                    }
                });
            }
        } else if ("turn/completed".equals(method)) {
            activity.runOnUiThread(() -> {
                streamingAgentMessage = null;
                streamingAgentMarkdown = null;
                send.setEnabled(true);
                loadThreads();
            });
        }
    }

    private void resolveServerRequest(JSONObject request) throws JSONException {
        String method = request.optString("method", "");
        JSONObject result;
        if (method.contains("requestApproval")) {
            result = new JSONObject().put("decision", "decline");
        } else if ("item/tool/requestUserInput".equals(method)) {
            result = new JSONObject().put("action", "cancel").put("content", JSONObject.NULL);
        } else {
            result = new JSONObject();
        }
        sendJson(new JSONObject().put("id", request.get("id")).put("result", result));
    }

    private void applyThreadList(JSONObject result) {
        JSONArray data = result == null ? null : result.optJSONArray("data");
        threads.clear();
        if (data != null) {
            for (int index = 0; index < data.length(); index++) {
                JSONObject thread = data.optJSONObject(index);
                if (thread != null) threads.put(thread.optString("id", ""), thread);
            }
        }
    }

    private final class GestureFrameLayout extends FrameLayout {
        private float gestureStartX;
        private float gestureStartY;
        private boolean trackingConversationGesture;

        GestureFrameLayout(Activity context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                gestureStartX = event.getX();
                gestureStartY = event.getY();
                trackingConversationGesture = gestureStartX >= getWidth() - dp(56);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    && trackingConversationGesture) {
                float horizontalDistance = gestureStartX - event.getX();
                float verticalDistance = Math.abs(gestureStartY - event.getY());
                trackingConversationGesture = false;
                if (horizontalDistance >= dp(56) && verticalDistance <= dp(120)) {
                    showConversationDrawer();
                }
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                trackingConversationGesture = false;
            }
            return super.dispatchTouchEvent(event);
        }
    }

    private void applyStartedThread(JSONObject result) throws JSONException {
        JSONObject thread = result == null ? null : result.optJSONObject("thread");
        threadId = thread == null ? null : thread.optString("id", null);
        if (threadId == null) throw new JSONException("Codex did not return a conversation ID");
        String prompt = pendingPrompt;
        pendingPrompt = null;
        if (prompt != null) startTurn(prompt);
    }

    private void renderThread(JSONObject result) {
        JSONObject thread = result == null ? null : result.optJSONObject("thread");
        JSONArray turns = thread == null ? null : thread.optJSONArray("turns");
        activity.runOnUiThread(() -> {
            messages.removeAllViews();
            streamingAgentMessage = null;
            streamingAgentMarkdown = null;
            if (turns != null) {
                for (int turnIndex = 0; turnIndex < turns.length(); turnIndex++) {
                    JSONObject turn = turns.optJSONObject(turnIndex);
                    JSONArray items = turn == null ? null : turn.optJSONArray("items");
                    if (items == null) continue;
                    for (int itemIndex = 0; itemIndex < items.length(); itemIndex++) {
                        JSONObject item = items.optJSONObject(itemIndex);
                        if (item == null) continue;
                        String type = item.optString("type", "");
                        if ("agentMessage".equals(type)) {
                            addMessage(item.optString("text", ""), false);
                        } else if ("userMessage".equals(type)) {
                            JSONArray content = item.optJSONArray("content");
                            if (content == null) continue;
                            for (int contentIndex = 0; contentIndex < content.length(); contentIndex++) {
                                JSONObject value = content.optJSONObject(contentIndex);
                                if (value != null && "text".equals(value.optString("type", ""))) {
                                    addMessage(value.optString("text", ""), true);
                                }
                            }
                        }
                    }
                }
            }
            send.setEnabled(true);
        });
    }

    private void appendAgentDelta(String delta) {
        if (delta.isEmpty()) return;
        activity.runOnUiThread(() -> {
            if (streamingAgentMessage == null) {
                streamingAgentMessage = addMessage("", false);
                streamingAgentMarkdown = new StringBuilder();
            }
            streamingAgentMarkdown.append(delta);
            renderMarkdown(streamingAgentMessage, streamingAgentMarkdown.toString());
            scrollToBottom();
        });
    }

    private TextView addMessage(String value, boolean mine) {
        TextView bubble = text(value, 15, mine ? Color.WHITE : color(R.color.edgez_text));
        bubble.setTextIsSelectable(true);
        bubble.setMovementMethod(LinkMovementMethod.getInstance());
        bubble.setPadding(dp(13), dp(10), dp(13), dp(10));
        bubble.setBackground(roundRect(mine ? color(R.color.edgez_blue) : Color.WHITE, 16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = mine ? Gravity.END : Gravity.START;
        params.setMargins(mine ? dp(44) : 0, dp(4), mine ? 0 : dp(44), dp(4));
        messages.addView(bubble, params);
        renderMarkdown(bubble, value);
        scrollToBottom();
        return bubble;
    }

    private void renderMarkdown(TextView view, String markdown) {
        markwon.setMarkdown(view, markdown == null ? "" : markdown);
    }

    private void showConversationDrawer() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(20), dp(14), dp(20));
        panel.setBackgroundColor(Color.WHITE);
        TextView heading = text(activity.getString(R.string.workspace_codex_conversations),
                19, color(R.color.edgez_text));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        panel.addView(heading, margins(0, 0, 0, 12));
        panel.addView(button(R.string.workspace_codex_new_conversation, true, view -> {
            threadId = null;
            pendingPrompt = null;
            messages.removeAllViews();
            streamingAgentMessage = null;
            streamingAgentMarkdown = null;
            if (conversationDrawer != null) conversationDrawer.dismiss();
        }), margins(0, 0, 0, 10));
        for (Map.Entry<String, JSONObject> entry : threads.entrySet()) {
            String preview = entry.getValue().optString("preview",
                    activity.getString(R.string.workspace_codex_untitled));
            Button item = button(preview.isEmpty()
                    ? activity.getString(R.string.workspace_codex_untitled) : preview,
                    false, view -> {
                        resumeThread(entry.getKey());
                        if (conversationDrawer != null) conversationDrawer.dismiss();
                    });
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            panel.addView(item, margins(0, 3, 0, 3));
        }
        ScrollView drawerScroll = new ScrollView(activity);
        drawerScroll.addView(panel);
        conversationDrawer = new PopupWindow(drawerScroll, dp(310),
                ViewGroup.LayoutParams.MATCH_PARENT, true);
        conversationDrawer.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        conversationDrawer.setOutsideTouchable(true);
        conversationDrawer.setElevation(dp(14));
        conversationDrawer.showAtLocation(root, Gravity.END, 0, 0);
    }

    private void loadThreads() {
        try {
            sendRequest(THREAD_LIST_ID, "thread/list", new JSONObject()
                    .put("cursor", JSONObject.NULL)
                    .put("limit", 50)
                    .put("cwd", new JSONArray().put("/home/jovyan/workspace"))
                    .put("sortKey", "updated_at"));
        } catch (JSONException ignored) {
        }
    }

    private void sendRequest(int id, String method, JSONObject params) throws JSONException {
        sendJson(new JSONObject().put("id", id).put("method", method).put("params", params));
    }

    private void sendJson(JSONObject message) {
        WebSocket current = socket;
        if (current != null) current.send(message.toString());
    }

    private void fail(String message) {
        activity.runOnUiThread(() -> {
            connectionStatus.setText(activity.getString(
                    R.string.workspace_codex_connection_failed,
                    message == null ? "Unknown error" : message));
            connectionStatus.setTextColor(color(R.color.edgez_error));
            input.setEnabled(false);
            send.setEnabled(false);
            inputModeToggle.setEnabled(false);
            holdToTalk.setEnabled(false);
        });
    }

    private void scrollToBottom() {
        messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
    }

    private Button button(int label, boolean primary, View.OnClickListener listener) {
        return button(activity.getString(label), primary, listener);
    }

    private Button button(String label, boolean primary, View.OnClickListener listener) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : color(R.color.edgez_blue));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                primary ? color(R.color.edgez_blue) : color(R.color.edgez_blue_soft)));
        button.setOnClickListener(listener);
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private TextView text(String value, int size, int textColor) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(textColor);
        return view;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private GradientDrawable roundRect(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int color(int id) {
        return activity.getColor(id);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private final class Listener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            try {
                initialize();
            } catch (JSONException error) {
                fail(error.getMessage());
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                handle(new JSONObject(text));
            } catch (JSONException error) {
                fail(error.getMessage());
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
            String detail = throwable.getMessage();
            if (response != null) detail = "HTTP " + response.code() + ": " + detail;
            fail(detail);
        }
    }
}
