import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const pluginRoot = path.join(
  rootDir,
  "node_modules/expo-modules-autolinking/android/expo-gradle-plugin",
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

console.log(`Configured Expo Gradle autolinking to use ${process.execPath}`);
