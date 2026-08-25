package ai.edgez.androiddevtools;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.WeakHashMap;

/** Cross-process controls shown over Expo and embedded app previews. */
public final class CodexPreviewBridge {
    public static final String ACTION_QUERY =
            "ai.edgez.androiddevtools.codex.preview.QUERY";
    public static final String ACTION_OPEN =
            "ai.edgez.androiddevtools.codex.preview.OPEN";
    public static final String ACTION_VOICE_START =
            "ai.edgez.androiddevtools.codex.preview.VOICE_START";
    public static final String ACTION_VOICE_FINISH =
            "ai.edgez.androiddevtools.codex.preview.VOICE_FINISH";
    public static final String ACTION_ACTIVE =
            "ai.edgez.androiddevtools.codex.preview.ACTIVE";
    public static final String ACTION_UPDATE =
            "ai.edgez.androiddevtools.codex.preview.UPDATE";
    public static final String EXTRA_ACTIVE = "active";
    public static final String EXTRA_MESSAGE = "message";
    private static final String PREFS = "codex_preview_bridge";
    private static final String PREF_ACTIVE = "active";

    private static final WeakHashMap<Activity, Overlay> OVERLAYS = new WeakHashMap<>();

    private CodexPreviewBridge() { }

    public static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle state) {
                if (isPreviewActivity(activity)) attach(activity);
            }

            @Override
            public void onActivityResumed(Activity activity) {
                Overlay overlay = OVERLAYS.get(activity);
                if (overlay != null) overlay.queryActiveSession();
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                detach(activity);
            }

            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        });
    }

    /** Explicit attachment used after React/embedded activities finish setting their content. */
    public static void attach(Activity activity) {
        if (!isPreviewActivity(activity) || OVERLAYS.containsKey(activity)) return;
        OVERLAYS.put(activity, new Overlay(activity));
    }

    public static void detach(Activity activity) {
        Overlay overlay = OVERLAYS.remove(activity);
        if (overlay != null) overlay.close();
    }

    public static void broadcastActive(Context context, boolean active) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_ACTIVE, active).apply();
        context.sendBroadcast(new Intent(ACTION_ACTIVE)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_ACTIVE, active));
    }

    public static void broadcastUpdate(Context context, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String safeMessage = message.trim();
        if (safeMessage.length() > 600) safeMessage = safeMessage.substring(0, 600) + "…";
        context.sendBroadcast(new Intent(ACTION_UPDATE)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_MESSAGE, safeMessage));
    }

    private static boolean isPreviewActivity(Activity activity) {
        String name = activity.getClass().getName();
        return name.contains("ExpoRuntimeActivity")
                || name.contains("EmbeddedBundleActivity")
                || name.contains("expo.modules.devlauncher");
    }

    private static final class Overlay {
        private final Activity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ImageButton codexButton;
        private final TextView snackbar;
        private final Runnable hideSnackbar;
        private final BroadcastReceiver receiver;
        private boolean recording;
        private boolean registered;

        Overlay(Activity activity) {
            this.activity = activity;
            codexButton = new ImageButton(activity);
            codexButton.setImageResource(R.drawable.ic_codex_chat);
            codexButton.setColorFilter(Color.WHITE);
            codexButton.setContentDescription(activity.getString(R.string.workspace_codex_open));
            codexButton.setPadding(dp(14), dp(14), dp(14), dp(14));
            codexButton.setBackground(roundRect(activity.getColor(R.color.edgez_blue), 99));
            codexButton.setElevation(dp(12));
            codexButton.setVisibility(View.GONE);
            codexButton.setOnClickListener(view -> send(ACTION_OPEN));
            installVoiceGesture();

            snackbar = new TextView(activity);
            snackbar.setTextSize(14);
            snackbar.setTextColor(Color.WHITE);
            snackbar.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            snackbar.setMaxLines(4);
            snackbar.setPadding(dp(16), dp(12), dp(16), dp(12));
            snackbar.setBackground(roundRect(0xEE20242C, 14));
            snackbar.setElevation(dp(11));
            snackbar.setVisibility(View.GONE);
            hideSnackbar = () -> snackbar.setVisibility(View.GONE);

            activity.addContentView(snackbar, snackbarParams());
            activity.addContentView(codexButton, buttonParams());

            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_ACTIVE.equals(intent.getAction())) {
                        codexButton.setVisibility(intent.getBooleanExtra(EXTRA_ACTIVE, false)
                                ? View.VISIBLE : View.GONE);
                    } else if (ACTION_UPDATE.equals(intent.getAction())) {
                        String message = intent.getStringExtra(EXTRA_MESSAGE);
                        if (message != null && !message.trim().isEmpty()) showSnackbar(message);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_ACTIVE);
            filter.addAction(ACTION_UPDATE);
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(receiver, filter);
            }
            registered = true;
            applyPersistedActiveState();
        }

        private void installVoiceGesture() {
            int longPressTimeout = ViewConfiguration.getLongPressTimeout();
            float[] start = new float[2];
            Runnable beginVoice = () -> {
                if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 4103);
                    showSnackbar(activity.getString(R.string.workspace_codex_microphone_permission));
                    return;
                }
                recording = true;
                codexButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                codexButton.animate().scaleX(1.35f).scaleY(1.35f).setDuration(140).start();
                showSnackbar(activity.getString(R.string.workspace_codex_release_to_send));
                send(ACTION_VOICE_START);
            };
            codexButton.setOnTouchListener((view, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        start[0] = event.getRawX();
                        start[1] = event.getRawY();
                        recording = false;
                        handler.postDelayed(beginVoice, longPressTimeout);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getRawX() - start[0]) > dp(36)
                                || Math.abs(event.getRawY() - start[1]) > dp(72)) {
                            handler.removeCallbacks(beginVoice);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(beginVoice);
                        view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                        if (recording) {
                            recording = false;
                            showSnackbar(activity.getString(R.string.workspace_codex_recognizing));
                            send(ACTION_VOICE_FINISH);
                        } else {
                            view.performClick();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(beginVoice);
                        view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                        if (recording) {
                            recording = false;
                            showSnackbar(activity.getString(R.string.workspace_codex_recognizing));
                            send(ACTION_VOICE_FINISH);
                        }
                        return true;
                    default:
                        return true;
                }
            });
        }

        void queryActiveSession() {
            applyPersistedActiveState();
            send(ACTION_QUERY);
        }

        private void applyPersistedActiveState() {
            boolean active = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(PREF_ACTIVE, false);
            codexButton.setVisibility(active ? View.VISIBLE : View.GONE);
        }

        void close() {
            handler.removeCallbacksAndMessages(null);
            if (registered) {
                activity.unregisterReceiver(receiver);
                registered = false;
            }
        }

        private void showSnackbar(String message) {
            snackbar.setText(message);
            snackbar.setVisibility(View.VISIBLE);
            snackbar.bringToFront();
            codexButton.bringToFront();
            handler.removeCallbacks(hideSnackbar);
            handler.postDelayed(hideSnackbar, 6_000);
        }

        private void send(String action) {
            activity.sendBroadcast(new Intent(action).setPackage(activity.getPackageName()));
        }

        private FrameLayout.LayoutParams buttonParams() {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dp(56), dp(56), Gravity.END | Gravity.BOTTOM);
            params.setMargins(dp(16), dp(16), dp(16), dp(22));
            return params;
        }

        private FrameLayout.LayoutParams snackbarParams() {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
            params.setMargins(dp(16), dp(16), dp(84), dp(28));
            return params;
        }

        private GradientDrawable roundRect(int fill, int radius) {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(fill);
            shape.setCornerRadius(dp(radius));
            return shape;
        }

        private int dp(int value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }
    }
}
