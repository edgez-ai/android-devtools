#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const EXPO_COMMIT = '2f42cf5057404fd1a07d09a0f245018d5f056236';
const BLE_PLX_VERSION = '3.5.1';

const expoRoot = path.resolve(process.argv[2] ?? '');
if (!process.argv[2] || !fs.existsSync(path.join(expoRoot, '.git'))) {
  throw new Error('Usage: prepare-expo-go-57.mjs /absolute/path/to/expo');
}

function read(relativePath) {
  return fs.readFileSync(path.join(expoRoot, relativePath), 'utf8');
}

function write(relativePath, contents) {
  fs.writeFileSync(path.join(expoRoot, relativePath), contents);
}

function replaceOnce(relativePath, source, replacement) {
  const contents = read(relativePath);
  if (contents.includes(replacement)) {
    return;
  }
  const first = contents.indexOf(source);
  if (first < 0 || contents.indexOf(source, first + source.length) >= 0) {
    throw new Error(`Expected one integration anchor in ${relativePath}: ${source}`);
  }
  write(relativePath, contents.replace(source, replacement));
}

const packagePath = 'apps/expo-go/package.json';
const packageJson = JSON.parse(read(packagePath));
packageJson.dependencies['react-native-ble-plx'] = BLE_PLX_VERSION;
packageJson.dependencies = Object.fromEntries(
  Object.entries(packageJson.dependencies).sort(([left], [right]) => left.localeCompare(right))
);
write(packagePath, `${JSON.stringify(packageJson, null, 2)}\n`);

replaceOnce(
  'apps/expo-go/android/settings.gradle',
  "include ':app'",
  `include ':app'\n\ndef edgezRoot = providers.gradleProperty("edgezRoot").get()\ninclude ':edgez-devtools'\nproject(':edgez-devtools').projectDir = new File(edgezRoot, 'expo-go/android-library')`
);

replaceOnce(
  'apps/expo-go/android/app/build.gradle',
  "dependencies {\n  implementation fileTree(dir: 'libs', include: ['*.jar'])",
  "dependencies {\n  implementation project(':edgez-devtools')\n  implementation fileTree(dir: 'libs', include: ['*.jar'])"
);

replaceOnce(
  'apps/expo-go/android/app/build.gradle',
  "'appLabel': '@string/versioned_app_name'",
  "'appLabel': '@string/edgez_app_name'"
);

const exponentPackage =
  'apps/expo-go/android/expoview/src/main/java/versioned/host/exp/exponent/ExponentPackage.kt';
replaceOnce(
  exponentPackage,
  'import com.airbnb.android.react.lottie.LottiePackage',
  'import com.airbnb.android.react.lottie.LottiePackage\nimport com.bleplx.BlePlxPackage'
);
replaceOnce(
  exponentPackage,
  'nativeModules.add(NetInfoModule(reactContext))',
  'nativeModules.add(NetInfoModule(reactContext))\n        nativeModules.addAll(blePlxPackage.createNativeModules(reactContext))'
);
replaceOnce(
  exponentPackage,
  'private val stripePackage = StripeSdkPackage()',
  'private val blePlxPackage = BlePlxPackage()\n    private val stripePackage = StripeSdkPackage()'
);

const bluetoothPermissions = `    <!-- EdgeZ custom Expo Go: react-native-ble-plx -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" tools:targetApi="s" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" tools:targetApi="s" />`;
const legacyBluetoothPermissions = `    <!-- EdgeZ custom Expo Go: react-native-ble-plx -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission
        android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation"
        tools:targetApi="s" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" tools:targetApi="s" />`;
const templateManifestPath = 'template-files/android/AndroidManifest.xml';
if (read(templateManifestPath).includes(legacyBluetoothPermissions)) {
  write(
    templateManifestPath,
    read(templateManifestPath).replace(legacyBluetoothPermissions, bluetoothPermissions)
  );
}
replaceOnce(
  templateManifestPath,
  '    <!-- ADD PERMISSIONS HERE -->',
  `    <!-- ADD PERMISSIONS HERE -->\n${bluetoothPermissions}`
);
replaceOnce(
  templateManifestPath,
  `    <uses-feature
        android:name="android.hardware.camera"
        android:required="false" />`,
  `    <uses-feature
        android:name="android.hardware.camera"
        android:required="false" />
    <uses-feature
        android:name="android.hardware.bluetooth_le"
        android:required="false" />`
);

const homeLauncherFilter = `            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
`;
replaceOnce(
  templateManifestPath,
  homeLauncherFilter,
  '            <!-- EdgeZ MainActivity owns the launcher; expo-home:// still opens Expo Home. -->\n'
);

// React Native 0.86 configures its Hermes CMake installer eagerly. Allow the build wrapper to
// supply a compatible system CMake when Android's requested package and sdkmanager are absent.
const hermesGradle =
  'react-native-lab/react-native/packages/react-native/ReactAndroid/hermes-engine/build.gradle.kts';
const previousCmakeOverride = `val cmakePath = "${'${getSDKPath()}'}/cmake/$cmakeVersion"
val edgezCmakeBinary = System.getenv("EDGEZ_CMAKE_BINARY")?.takeIf { File(it).canExecute() }
val cmakeBinaryPath = edgezCmakeBinary ?: "${'${cmakePath}'}/bin/cmake"
val cmakeInstallCheckPath = edgezCmakeBinary?.let { File(it).parent } ?: cmakePath`;
if (read(hermesGradle).includes(previousCmakeOverride)) {
  write(
    hermesGradle,
    read(hermesGradle).replace(
      previousCmakeOverride,
      `${previousCmakeOverride}\nval edgezCmakeVersion = System.getenv("EDGEZ_CMAKE_VERSION")`
    )
  );
}
replaceOnce(
  hermesGradle,
  `val cmakePath = "${'${getSDKPath()}'}/cmake/$cmakeVersion"
val cmakeBinaryPath = "${'${cmakePath}'}/bin/cmake"`,
  `val cmakePath = "${'${getSDKPath()}'}/cmake/$cmakeVersion"
val edgezCmakeBinary = System.getenv("EDGEZ_CMAKE_BINARY")?.takeIf { File(it).canExecute() }
val cmakeBinaryPath = edgezCmakeBinary ?: "${'${cmakePath}'}/bin/cmake"
val cmakeInstallCheckPath = edgezCmakeBinary?.let { File(it).parent } ?: cmakePath
val edgezCmakeVersion = System.getenv("EDGEZ_CMAKE_VERSION")`
);
replaceOnce(
  hermesGradle,
  '    else -> throw GradleException("Could not find sdkmanager executable.")',
  `    File(cmakeBinaryPath).exists() -> cmakeBinaryPath
    else -> throw GradleException("Could not find sdkmanager executable and CMake ${'${cmakeVersion}'} is not installed.")`
);
replaceOnce(
  hermesGradle,
  '      onlyIfProvidedPathDoesNotExists.set(cmakePath)',
  '      onlyIfProvidedPathDoesNotExists.set(cmakeInstallCheckPath)'
);
replaceOnce(
  hermesGradle,
  '      version = cmakeVersion',
  '      version = edgezCmakeVersion ?: cmakeVersion'
);

const marker = {
  expoCommit: EXPO_COMMIT,
  expoSdk: 57,
  reactNativeBlePlx: BLE_PLX_VERSION,
};
write('.edgez-expo-go-prepared.json', `${JSON.stringify(marker, null, 2)}\n`);
console.log(`Prepared Expo Go 57 at ${expoRoot} with react-native-ble-plx ${BLE_PLX_VERSION}`);
