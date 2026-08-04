import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const pluginRoot = path.join(
  rootDir,
  "node_modules/expo-modules-autolinking/android/expo-gradle-plugin",
);
const modulesCorePluginRoot = path.join(
  rootDir,
  "node_modules/expo-modules-core/expo-module-gradle-plugin",
);
const nodeLiteral = JSON.stringify(process.execPath);

const patches = [
  {
    file: "expo-autolinking-settings-plugin/src/main/kotlin/expo/modules/plugin/ExpoAutolinkingSettingsPlugin.kt",
    pattern: /env\.commandLine\("[^"]+", "--print", "require\.resolve\('expo-modules-autolinking\/package\.json'/g,
    replacement: `env.commandLine(${nodeLiteral}, "--print", "require.resolve('expo-modules-autolinking/package.json'`,
    expected: 1,
  },
  {
    file: "expo-autolinking-settings-plugin/src/main/kotlin/expo/modules/plugin/ExpoAutolinkingSettingsExtension.kt",
    pattern: /env\.commandLine\("[^"]+", "--print"/g,
    replacement: `env.commandLine(${nodeLiteral}, "--print"`,
    expected: 2,
  },
  {
    file: "expo-autolinking-plugin-shared/src/main/kotlin/expo/modules/plugin/AutolinkingCommandBuilder.kt",
    pattern: /(private val baseCommand = listOf\(\s*)"[^"]+",/g,
    replacement: `$1${nodeLiteral},`,
    expected: 1,
  },
  {
    file: "expo-autolinking-plugin/src/main/kotlin/expo/modules/plugin/ExpoAutolinkingPlugin.kt",
    pattern: /(spec\.commandLine\(\s*)"[^"]+",/g,
    replacement: `$1${nodeLiteral},`,
    expected: 1,
  },
];

for (const patch of patches) {
  const target = path.join(pluginRoot, patch.file);
  const source = readFileSync(target, "utf8");
  const matches = source.match(patch.pattern)?.length || 0;
  if (matches !== patch.expected) {
    throw new Error(`Expected ${patch.expected} Node launch point(s) in ${patch.file}, found ${matches}`);
  }
  writeFileSync(target, source.replace(patch.pattern, patch.replacement));
}

const directNodePatches = [
  {
    target: path.join(
      rootDir,
      "node_modules/expo/node_modules/expo-constants/scripts/get-app-config-android.gradle",
    ),
    replacements: [
      {
        pattern: /commandLine\("[^"]+", "-e", "console\.log\(require\('path'\)\.dirname\(require\.resolve\('expo-constants\/package\.json'\)\)\);"\)/g,
        replacement: `commandLine(${nodeLiteral}, "-e", "console.log(require('path').dirname(require.resolve('expo-constants/package.json')));")`,
      },
      {
        pattern: /config\.nodeExecutableAndArgs \?: \["[^"]+"\]/g,
        replacement: `config.nodeExecutableAndArgs ?: [${nodeLiteral}]`,
      },
    ],
  },
  {
    target: path.join(
      modulesCorePluginRoot,
      "src/main/kotlin/expo/modules/plugin/gradle/ExpoGradleHelperExtension.kt",
    ),
    replacements: [
      {
        pattern: /env\.commandLine\("[^"]+", "--print", "require\.resolve\('react-native\/package\.json'\)"\)/g,
        replacement: `env.commandLine(${nodeLiteral}, "--print", "require.resolve('react-native/package.json')")`,
      },
    ],
  },
  {
    target: path.join(
      rootDir,
      "node_modules/@react-native/gradle-plugin/react-native-gradle-plugin/src/main/kotlin/com/facebook/react/utils/PathUtils.kt",
    ),
    replacements: [
      {
        pattern: /exec\.commandLine\("[^"]+", "--print", "require\.resolve\('react-native\/cli'\);"\)/g,
        replacement: `exec.commandLine(${nodeLiteral}, "--print", "require.resolve('react-native/cli');")`,
      },
    ],
  },
];

for (const patch of directNodePatches) {
  let source = readFileSync(patch.target, "utf8");
  for (const replacement of patch.replacements) {
    const matches = source.match(replacement.pattern)?.length || 0;
    if (matches !== 1) {
      throw new Error(`Expected one Node launch point in ${patch.target}, found ${matches}`);
    }
    source = source.replace(replacement.pattern, replacement.replacement);
  }
  writeFileSync(patch.target, source);
}

const publishingConfiguration = path.join(
  modulesCorePluginRoot,
  "src/main/kotlin/expo/modules/plugin/ProjectConfiguration.kt",
);
const publishingSource = readFileSync(publishingConfiguration, "utf8");
const eagerPublishing = `  afterEvaluate {
    val publicationInfo = PublicationInfo(this)

    publishingExtension()
      .publications
      .createReleasePublication(
        publicationInfo,
        expoModulesExtension.pomConfigurator
      )

    createExpoPublishToMavenLocalTask(publicationInfo, expoModulesExtension)

    val npmLocalRepositoryRelativePath = "local-maven-repo"
    val npmLocalRepository = File("\${project.projectDir.parentFile}/\${npmLocalRepositoryRelativePath}").toURI()
    publishingExtension().repositories.mavenLocal { mavenRepo ->
      mavenRepo.name = "NPMPackage"
      mavenRepo.url = npmLocalRepository
    }

    createExpoPublishTask(publicationInfo, expoModulesExtension, npmLocalRepositoryRelativePath)
  }`;
const deferredPublishing = `  afterEvaluate {
    val project = this
    // The release component can be registered after project evaluation. React
    // when it becomes available instead of reading it eagerly.
    components.matching { it.name == "release" }.all {
      val publicationInfo = PublicationInfo(project)

      project.publishingExtension()
        .publications
        .createReleasePublication(
          publicationInfo,
          expoModulesExtension.pomConfigurator
        )

      project.createExpoPublishToMavenLocalTask(publicationInfo, expoModulesExtension)

      val npmLocalRepositoryRelativePath = "local-maven-repo"
      val npmLocalRepository = File("\${project.projectDir.parentFile}/\${npmLocalRepositoryRelativePath}").toURI()
      project.publishingExtension().repositories.mavenLocal { mavenRepo ->
        mavenRepo.name = "NPMPackage"
        mavenRepo.url = npmLocalRepository
      }

      project.createExpoPublishTask(publicationInfo, expoModulesExtension, npmLocalRepositoryRelativePath)
    }
  }`;

if (publishingSource.includes(eagerPublishing)) {
  writeFileSync(
    publishingConfiguration,
    publishingSource.replace(eagerPublishing, deferredPublishing),
  );
} else if (!publishingSource.includes(deferredPublishing)) {
  throw new Error("Expo publication lifecycle no longer matches the expected SDK 57 source");
}

console.log(`Configured Expo and React Native Gradle plugins to use ${process.execPath}`);
console.log("Configured Expo publication tasks to wait for the Android release component");
