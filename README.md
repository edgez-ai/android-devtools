# EdgeZ Android DevTools

EdgeZ Android DevTools combines the existing native Android application with an
Expo SDK 57 React Native development runtime for connecting a physical Android
device to a remote workspace through [`adb-sidecar`](../adb-sidecar). The one
installable application contains:

- the original native Android DevTools UI as the default launcher and home;
- the Expo development-client runtime for loading projects from Metro;
- `react-native-ble-plx` for Bluetooth Low Energy central/client access;
- the ESP-IDF provisioning bridge for secure BLE Wi-Fi and custom-endpoint provisioning;
- the existing EdgeZ libp2p, Wireless Debugging, USB/IP, and scrcpy Android
  implementation.

Android DevTools runs in the main app process. Expo Home, Settings, and Metro
projects run only when requested, in an isolated `:expo` process and task, so a
test bundle cannot replace the native Android DevTools screen. The Expo
launcher and dev-menu Home actions bring the native task back to the foreground.
There is no permanent Expo tab in the native UI.

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

The generated Gradle project resolves Node from an optional
`edgezNodeExecutable` Gradle property, `NODE_BINARY`, standard Homebrew paths,
or an installed NVM Node 22. This lets Android Studio sync when its GUI process
does not inherit the shell's `PATH`.

Expo SDK 57's autolinking Gradle plugin also launches Node internally before
project configuration is available. The postinstall step patches those local
`node_modules` launch points—including Expo Constants, Expo Modules Core, and
React Native's CLI resolver—to use the Node executable that ran `npm install`.
It also backports Expo's deferred publication setup so Android Studio sync waits
for AGP to register the `release` software component.

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

`assembleDebug` packages the native Android DevTools app, the Expo development
runtime, its embedded fallback JavaScript bundle, and native modules into one
APK. The native Home works without Metro. The merged APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Open the `android` directory—not the repository root—as the project in Android
Studio. Select the `app` run configuration to build and install the same debug
application.

## Build release APKs

The GitHub Actions `Build Android DevTools release` workflow builds one ARM64
release binary and publishes it in two forms:

- `android-devtools-release-unsigned.apk` for downstream signing;
- `android-devtools-release-signed.apk` signed with the protected
  EdgeZ release keystore and verified with `apksigner`.

Configure the `ANDROID_KEYSTORE_BASE64` and `ANDROID_KEYSTORE_PASSWORD`
repository secrets before dispatching the workflow or pushing a version tag.
`scripts/generate-release-secrets.sh` creates values with the expected
`edgez-android-release` key alias. This retains the pre-Expo release process:
Gradle first builds without `key.properties`, then CI restores the keystore and
properties and rebuilds the signed APK. `apksigner` verifies both results.

## Run a Metro project

Start Metro for this development client:

```sh
npm start
```

Install the debug APK and open a development-client URL from Expo CLI (or scan
its QR code) to start the isolated Expo runtime. For an attached device, Expo
CLI normally configures the required ADB port forwarding. Launching the app
normally always opens the native Android DevTools screen.

The native **Open Expo project** button opens `http://127.0.0.1:8081` directly
in the bundled development runtime. When Metro runs on the attached computer,
forward that port first with `adb reverse tcp:8081 tcp:8081`.

The EdgeZ proxy never starts at app launch, after joining, after pairing, or
after a device reboot. The user must press **Start proxy**. Its foreground
service is non-sticky and stops when the proxy is stopped or startup fails.
When Wireless Debugging is off, the native Home prompts the user to enable it
without starting the proxy.

Each proxy run uses a unique internal `@edgez-usbip-<pid>-<run>` Unix-socket
bridge. This avoids a stale Android abstract-socket binding preventing a later
restart. Both that bridge and the TCP Metro listener on `127.0.0.1:8081` start
and stop with the proxy.

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

`@orbital-systems/react-native-esp-idf-provisioning` is also autolinked and
configured for BLE transport, allowing Metro projects to use Espressif
Security 1 provisioning and custom endpoints such as `mqtt-config`.

## EdgeZ device setup

1. Grant notification and Nearby Wi-Fi permissions and allow unrestricted
   battery use.
2. Tap **Scan QR code & join** and scan the join QR code from edgez.ai.
3. Tap **Pair from notification**, open **Pair device with pairing code** in
   Wireless Debugging, and reply to the EdgeZ notification with the code.
4. Copy the peer ID shown by the app into
   `LIBP2P_AGENT_MOBILE_PEER_ID` for `adb-sidecar`.

The EdgeZ tunnel does not restart after boot. Enable Wireless Debugging if
needed, then press **Start proxy** when you want the foreground service to run.

## Rebuild the native libp2p libraries

The normal Android build uses the checked-in `libedgejoin.so` files. To rebuild
them from `third_party/libp2p-go`:

```sh
ANDROID_NDK_HOME=/path/to/android-ndk ./scripts/build-native.sh
```

Commit both an updated submodule pointer and the regenerated libraries when
upgrading the native implementation.
