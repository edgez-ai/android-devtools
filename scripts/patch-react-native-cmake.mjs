import fs from 'node:fs';
import path from 'node:path';

const gradleFile = path.resolve(
  'node_modules/react-native/ReactAndroid/build.gradle.kts',
);

if (!fs.existsSync(gradleFile)) {
  throw new Error(`React Native Gradle file not found: ${gradleFile}`);
}

let original = fs.readFileSync(gradleFile, 'utf8');
const source = 'val cmakeVersion = System.getenv("CMAKE_VERSION") ?: "3.30.5"';
const replacement = `val cmakeVersion =
    System.getenv("CMAKE_VERSION")
        ?: rootProject.findProperty("edgezCmakeVersion")?.toString()
        ?: "3.30.5"`;

if (!original.includes(replacement)) {
  if (!original.includes(source)) {
    throw new Error('Unsupported React Native CMake configuration; update the patch script');
  }
  original = original.replace(source, replacement);
  fs.writeFileSync(gradleFile, original);
  console.log('Patched React Native to use the project CMake version when configured.');
}

const launcherFile = path.resolve(
  'node_modules/expo-dev-launcher/android/src/debug/java/expo/modules/devlauncher/DevLauncherController.kt',
);
if (!fs.existsSync(launcherFile)) {
  throw new Error(`Expo development launcher file not found: ${launcherFile}`);
}

let launcher = fs.readFileSync(launcherFile, 'utf8');
const launcherAnchor = `      val shouldTryToLaunchLastOpenedBundle =
        DependencyInjection.devMenuPreferences?.tryToLaunchLastBundle ?: true`;
const embeddedHome = `      // EdgeZ DevTools owns the app home. Prefer its packaged React Native
      // bundle for normal launcher intents; Metro URLs are still handled above.
      if (hasEmbeddedBundle()) {
        coroutineScope.launch {
          try {
            loadEmbeddedBundle(activityToBeInvalidated)
          } catch (_: Throwable) {
            navigateToLauncher()
          }
        }
        return true
      }

${launcherAnchor}`;

if (!launcher.includes(embeddedHome)) {
  if (!launcher.includes(launcherAnchor)) {
    throw new Error('Unsupported Expo launcher configuration; update the patch script');
  }
  launcher = launcher.replace(launcherAnchor, embeddedHome);
  fs.writeFileSync(launcherFile, launcher);
  console.log('Patched Expo launcher to use the embedded EdgeZ home by default.');
}

const bottomTabsFile = path.resolve(
  'node_modules/expo-dev-launcher/android/src/debug/java/expo/modules/devlauncher/compose/ui/BottomTabBar.kt',
);
let bottomTabs = fs.readFileSync(bottomTabsFile, 'utf8');
const updatesTab = `
      button(
        "Updates",
        { size, tint -> LauncherIcons.UpdatesNav(size, tint) },
        Routes.Updates
      )
`;
if (bottomTabs.includes(updatesTab)) {
  bottomTabs = bottomTabs.replace(updatesTab, '\n');
  fs.writeFileSync(bottomTabsFile, bottomTabs);
  console.log('Removed the unused Updates tab from the Expo project launcher.');
}

const navigatorFile = path.resolve(
  'node_modules/expo-dev-launcher/android/src/debug/java/expo/modules/devlauncher/compose/DevLauncherBottomTabsNavigator.kt',
);
let navigator = fs.readFileSync(navigatorFile, 'utf8');
const updatesImport =
  'import expo.modules.devlauncher.compose.routes.UpdatesRoute\n';
const updatesRoute = `          composable<Routes.Updates> {
            UpdatesRoute(onProfileClick = navigateToProfile)
          }
`;
if (navigator.includes(updatesRoute)) {
  navigator = navigator
    .replace(updatesImport, '')
    .replace(updatesRoute, '');
  fs.writeFileSync(navigatorFile, navigator);
  console.log('Removed the unused Updates route from the Expo project launcher.');
}
