package ai.edgez.androiddevtools;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

final class ExpoGoLauncher {
    private static final String PACKAGE_NAME = "host.exp.exponent";
    private static final Uri INSTALL_URI = Uri.parse(
            "https://expo.dev/go?device=true&platform=android");

    private ExpoGoLauncher() {
    }

    static boolean isInstalled(Context context) {
        return context.getPackageManager().getLaunchIntentForPackage(PACKAGE_NAME) != null;
    }

    static String openOrInstall(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(PACKAGE_NAME);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            return context.getString(R.string.expo_opened);
        }

        Intent install = new Intent(Intent.ACTION_VIEW, INSTALL_URI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(install);
            return context.getString(R.string.expo_install_started);
        } catch (ActivityNotFoundException exception) {
            return context.getString(R.string.expo_no_browser);
        }
    }
}
