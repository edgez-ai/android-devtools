package ai.edgez.androiddevtools;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class JoinKeyTipsActivity extends Activity {
    private static final Uri DEVICES_PORTAL =
            Uri.parse("https://www.edgez.ai/devices");
    private static final int BLUE = Color.rgb(13, 71, 161);
    private static final int BODY = Color.rgb(55, 65, 81);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
    }

    private View buildContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(28));

        TextView back = text("‹  Back", 16, BLUE);
        back.setGravity(Gravity.START);
        back.setPadding(0, dp(8), 0, dp(8));
        back.setOnClickListener(view -> finish());
        content.addView(back, margins(0, 0, 0, 10));

        TextView title = text("Get your device join key", 27, BLUE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);
        content.addView(
                text(
                        "Use the edgez.ai portal to copy the two values Android DevTools "
                                + "needs.",
                        15,
                        BODY),
                margins(0, 6, 0, 14));

        Button openPortal = new Button(this);
        openPortal.setText("Open edgez.ai Devices");
        openPortal.setAllCaps(false);
        openPortal.setOnClickListener(view -> openDevicesPortal());
        content.addView(openPortal, margins(0, 0, 0, 20));

        content.addView(step(
                "1",
                "Sign in and open Devices",
                "Sign in to the edgez.ai portal, then choose Devices from the navigation."));
        content.addView(step(
                "2",
                "Choose your device",
                "Open the device you want to connect. If it is not listed yet, tap "
                        + "Create Device, complete the registration, then open it."));
        content.addView(step(
                "3",
                "Copy Serial Number",
                "In Device Information, copy the value labeled Serial Number. Use the "
                        + "complete value shown by the portal."));
        content.addView(step(
                "4",
                "Create and copy the join key",
                "Under Join Key (Secret), tap Create Join Key, then tap Copy. The secret "
                        + "is shown only once, so copy it before leaving the page."));
        content.addView(step(
                "5",
                "Return and join",
                "Paste Serial Number and the copied join key into Android DevTools, then "
                        + "tap Join network."));

        LinearLayout warning = new LinearLayout(this);
        warning.setOrientation(LinearLayout.VERTICAL);
        warning.setPadding(dp(14), dp(12), dp(14), dp(12));
        warning.setBackground(roundedBackground(
                Color.rgb(255, 248, 225), Color.rgb(245, 166, 35), 10));
        TextView warningTitle = text("Keep the key safe", 15, Color.rgb(132, 82, 0));
        warningTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        warning.addView(warningTitle);
        warning.addView(
                text(
                        "If the page says the key is already set, its original value cannot "
                                + "be revealed. “Generate New Join Key” replaces it, so any "
                                + "client using the previous key must be updated.",
                        14,
                        Color.rgb(110, 74, 12)),
                margins(0, 4, 0, 0));
        content.addView(warning, margins(0, 6, 0, 18));

        Button done = new Button(this);
        done.setText("Back to setup");
        done.setAllCaps(false);
        done.setOnClickListener(view -> finish());
        content.addView(done);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private View step(String number, String heading, String body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.TOP);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(roundedBackground(
                Color.rgb(248, 250, 252), Color.rgb(218, 225, 234), 12));

        TextView badge = text(number, 15, Color.WHITE);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(circleBackground(BLUE));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        badgeParams.setMargins(0, 0, dp(12), 0);
        card.addView(badge, badgeParams);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView headingView = text(heading, 16, Color.rgb(25, 35, 50));
        headingView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(headingView);
        copy.addView(text(body, 14, BODY), margins(0, 4, 0, 0));
        card.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.setLayoutParams(margins(0, 0, 0, 10));
        return card;
    }

    private void openDevicesPortal() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, DEVICES_PORTAL));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(
                    this,
                    "Open https://www.edgez.ai/devices in a browser.",
                    Toast.LENGTH_LONG)
                    .show();
        }
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setLineSpacing(0, 1.12f);
        return text;
    }

    private GradientDrawable roundedBackground(int fill, int stroke, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(1), stroke);
        return background;
    }

    private GradientDrawable circleBackground(int fill) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(fill);
        return background;
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
}
