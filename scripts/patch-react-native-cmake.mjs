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

const basicAppIntent = `  private fun createBasicAppIntent() =
    if (sLauncherClass == null) {
      checkNotNull(
        context
          .packageManager
          .getLaunchIntentForPackage(context.packageName)
      ) { "Couldn't find the launcher class." }
    } else {
      Intent(context, sLauncherClass!!)
    }.apply { addFlags(NEW_ACTIVITY_FLAGS) }`;
const isolatedAppIntent = `  private fun createBasicAppIntent() =
    Intent()
      .setClassName(context.packageName, "\${context.packageName}.ExpoRuntimeActivity")
      .apply { addFlags(NEW_ACTIVITY_FLAGS) }`;
if (!launcher.includes(isolatedAppIntent)) {
  if (!launcher.includes(basicAppIntent)) {
    throw new Error('Unsupported Expo runtime activity configuration');
  }
  launcher = launcher.replace(basicAppIntent, isolatedAppIntent);
  fs.writeFileSync(launcherFile, launcher);
  console.log('Routed Metro bundles to the isolated Expo runtime activity.');
}

const sharedEmbeddedIntent = `      val appIntent = createAppIntent()
      val appLoader = DevLauncherEmbeddedAppLoader(appHost, context, this)`;
const homeEmbeddedIntent = `      // Keep the packaged Android DevTools bundle in MainActivity. Remote
      // bundles use createAppIntent(), which targets the isolated :expo task.
      val appIntent = mainActivity?.let { activity ->
        Intent(activity, activity::class.java).apply { addFlags(NEW_ACTIVITY_FLAGS) }
      } ?: createAppIntent()
      val appLoader = DevLauncherEmbeddedAppLoader(appHost, context, this)`;
if (!launcher.includes(homeEmbeddedIntent)) {
  if (!launcher.includes(sharedEmbeddedIntent)) {
    throw new Error('Unsupported Expo embedded bundle intent configuration');
  }
  launcher = launcher.replace(sharedEmbeddedIntent, homeEmbeddedIntent);
  fs.writeFileSync(launcherFile, launcher);
  console.log('Kept the embedded Android DevTools home in MainActivity.');
}

const contextImport = 'import androidx.compose.ui.platform.LocalContext\n';
if (!bottomTabs.includes(contextImport)) {
  bottomTabs = bottomTabs
    .replace(
      'package expo.modules.devlauncher.compose.ui\n',
      'package expo.modules.devlauncher.compose.ui\n\nimport android.content.Intent\n',
    )
    .replace(
      'import androidx.compose.ui.graphics.Color\n',
      `import androidx.compose.ui.graphics.Color\n${contextImport}`,
    );
}
const tabContextAnchor = `fun BottomTabBar(
  navController: NavController
) {`;
const tabContext = `${tabContextAnchor}
  val context = LocalContext.current`;
if (!bottomTabs.includes(tabContext)) {
  bottomTabs = bottomTabs.replace(tabContextAnchor, tabContext);
}
const expoHomeTab = `      button(
        "Home",
        { size, tint -> LauncherIcons.Home(size, tint) },
        Routes.Home
      )`;
const rootTabs = `      BottomTabButton(
        label = "Android DevTools",
        icon = { size, tint -> LauncherIcons.Home(size, tint) },
        modifier = Modifier.weight(1f).fillMaxHeight(),
        isSelected = false,
        onClick = {
          context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(it)
          }
        }
      )

      button(
        "Expo",
        { size, tint -> LauncherIcons.Home(size, tint) },
        Routes.Home
      )`;
if (!bottomTabs.includes(rootTabs)) {
  if (!bottomTabs.includes(expoHomeTab)) {
    throw new Error('Unsupported Expo bottom navigation configuration');
  }
  bottomTabs = bottomTabs.replace(expoHomeTab, rootTabs);
}
fs.writeFileSync(bottomTabsFile, bottomTabs);

const navigatorSignature = 'fun DevLauncherBottomTabsNavigator() {';
const routedNavigatorSignature =
  'fun DevLauncherBottomTabsNavigator(initialTab: String = "home") {';
if (navigator.includes(navigatorSignature)) {
  navigator = navigator.replace(navigatorSignature, routedNavigatorSignature);
}
const defaultStart = '          startDestination = Routes.Home,';
const routedStart =
  '          startDestination = if (initialTab == "settings") Routes.Settings else Routes.Home,';
if (navigator.includes(defaultStart)) {
  navigator = navigator.replace(defaultStart, routedStart);
}
fs.writeFileSync(navigatorFile, navigator);

const bindingFile = path.resolve(
  'node_modules/expo-dev-launcher/android/src/debug/java/expo/modules/devlauncher/compose/BindingView.kt',
);
let binding = fs.readFileSync(bindingFile, 'utf8');
binding = binding
  .replace(
    'class BindingView(context: Context) : LinearLayout(context) {',
    'class BindingView(context: Context, initialTab: String = "home") : LinearLayout(context) {',
  )
  .replace(
    'DevLauncherBottomTabsNavigator()',
    'DevLauncherBottomTabsNavigator(initialTab)',
  );
fs.writeFileSync(bindingFile, binding);

const activityFile = path.resolve(
  'node_modules/expo-dev-launcher/android/src/debug/java/expo/modules/devlauncher/launcher/DevLauncherActivity.kt',
);
let activity = fs.readFileSync(activityFile, 'utf8');
const oldActivityBody = `    setContentView(
      BindingView(this)
    )`;
const routedActivityBody = `    showRootTab(intent?.getStringExtra("edgez.rootTab"))`;
if (activity.includes(oldActivityBody)) {
  activity = activity.replace(oldActivityBody, routedActivityBody);
}
const pauseAnchor = `  override fun onPause() {`;
const routeHandling = `  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    showRootTab(intent.getStringExtra("edgez.rootTab"))
  }

  private fun showRootTab(tab: String?) {
    setContentView(BindingView(this, tab ?: "home"))
  }

${pauseAnchor}`;
if (!activity.includes('private fun showRootTab')) {
  activity = activity.replace(pauseAnchor, routeHandling);
}
fs.writeFileSync(activityFile, activity);

const packageDelegateFile = path.resolve(
  'node_modules/expo-dev-launcher/android/src/debug/java/expo/modules/devlauncher/DevLauncherPackageDelegate.kt',
);
let packageDelegate = fs.readFileSync(packageDelegateFile, 'utf8');
const expoGoHome =
  '            goToHomeAction = { DevLauncherController.instance.navigateToLauncher() },';
const edgezGoHome = `            goToHomeAction = {
              currentActivity?.let { activity ->
                activity.packageManager.getLaunchIntentForPackage(activity.packageName)?.let { intent ->
                  intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                  activity.startActivity(intent)
                }
              }
            },`;
if (packageDelegate.includes(expoGoHome)) {
  packageDelegate = packageDelegate.replace(expoGoHome, edgezGoHome);
  fs.writeFileSync(packageDelegateFile, packageDelegate);
  console.log('Routed the Expo dev-menu Home action to Android DevTools.');
}

const homeScreenFile = path.resolve(
  'node_modules/expo-dev-launcher/android/src/debug/java/expo/modules/devlauncher/compose/screens/HomeScreen.kt',
);
let homeScreen = fs.readFileSync(homeScreenFile, 'utf8');
homeScreen = homeScreen
  .replace('import expo.modules.devlauncher.compose.ui.EmbeddedBundleButton\n', '')
  .replace(`          if (state.hasEmbeddedBundle) {
            Spacer(NewAppTheme.spacing.\`3\`)
            EmbeddedBundleButton(onAction)
          }

`, '');
fs.writeFileSync(homeScreenFile, homeScreen);
