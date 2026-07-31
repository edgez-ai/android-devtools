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
            return "Expo Go opened. Start the project from the remote IDE to load it.";
        }

        Intent install = new Intent(Intent.ACTION_VIEW, INSTALL_URI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(install);
            return "Choose the Expo Go version matching your project, install it, then return here.";
        } catch (ActivityNotFoundException exception) {
            return "No browser is available. From the remote IDE, run npx expo start --android; "
                    + "Expo CLI can install the matching Expo Go build through ADB.";
        }
    }
}
