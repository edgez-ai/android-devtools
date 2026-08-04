# EdgeZ Android DevTools

EdgeZ Android DevTools is an Expo SDK 57 React Native development client for
connecting a physical Android device to a remote workspace through
[`adb-sidecar`](../adb-sidecar). It combines one installable application with:

- the React Native version of the original Android setup UI;
- the Expo development-client runtime for loading projects from Metro;
- `react-native-ble-plx` for Bluetooth Low Energy central/client access;
- the existing EdgeZ libp2p, Wireless Debugging, USB/IP, and scrcpy Android
  implementation.

This is a custom development client, not the store version of Expo Go. Native
modules such as BLE require this APK and cannot be added by installing only the
standard Expo runtime.

## Project layout

```text
src/                         React Native UI and TypeScript native bridge
app/src/main/                Existing EdgeZ Android sources and resources
native/edgez-android/        Android library that compiles those sources
plugins/withEdgezNative.js   Expo prebuild integration
android/                     Generated Android Studio/Gradle project
```

`native/edgez-android` references `app/src/main` directly. The APK therefore
contains the current native sources; it does not embed or copy a second APK.

## Install dependencies

Use Node.js 22 and JDK 17:

```sh
npm ci
```

The postinstall script applies the CMake version needed by the current Android
SDK installation.

## Build the merged debug APK

Generate the native project after changing `app.json`, the config plugin, or
native dependency configuration:

```sh
npx expo prebuild --clean --platform android --no-install
node scripts/patch-react-native-cmake.mjs
```

Then build with the standard Android `assembleDebug` task:

```sh
cd android
./gradlew :app:assembleDebug -PreactNativeArchitectures=arm64-v8a
```

The single merged APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Open the `android` directory—not the repository root—as the project in Android
Studio. Select the `app` run configuration to build and install the same debug
application.

## Run a Metro project

Start Metro for this development client:

```sh
npm start
```

Install and launch the debug APK, then select the local development server in
the development-client launcher. For an attached device, Expo CLI normally
configures the required ADB port forwarding. The React Native screen also has
an **Open developer menu** button.

The application ID is `ai.edgez.androiddevtools.runtime`. No separate Expo Go
installation is required.

## BLE

`react-native-ble-plx` is autolinked into the development client. Metro-loaded
React Native code can import it directly:

```ts
import { BleManager } from 'react-native-ble-plx';

const manager = new BleManager();
```

Android still requires runtime Bluetooth permissions before scanning. The
development client declares Bluetooth scan/connect and legacy location
permissions in `app.json`. Rebuild the native APK whenever native modules or
their config-plugin options change; JavaScript-only changes need only Metro.

## EdgeZ device setup

1. Grant notification and Nearby Wi-Fi permissions and allow unrestricted
   battery use.
2. Tap **Scan QR code & join** and scan the join QR code from edgez.ai.
3. Tap **Pair from notification**, open **Pair device with pairing code** in
   Wireless Debugging, and reply to the EdgeZ notification with the code.
4. Copy the peer ID shown by the app into
   `LIBP2P_AGENT_MOBILE_PEER_ID` for `adb-sidecar`.

The foreground service restores the EdgeZ tunnel after boot when join
configuration exists. Wireless Debugging itself may need to be enabled again
after a device reboot.

## Rebuild the native libp2p libraries

The normal Android build uses the checked-in `libedgejoin.so` files. To rebuild
them from `third_party/libp2p-go`:

```sh
ANDROID_NDK_HOME=/path/to/android-ndk ./scripts/build-native.sh
```

Commit both an updated submodule pointer and the regenerated libraries when
upgrading the native implementation.
