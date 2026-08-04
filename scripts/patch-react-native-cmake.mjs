import fs from 'node:fs';
import path from 'node:path';

const gradleFile = path.resolve(
  'node_modules/react-native/ReactAndroid/build.gradle.kts',
);

if (!fs.existsSync(gradleFile)) {
  throw new Error(`React Native Gradle file not found: ${gradleFile}`);
}

const original = fs.readFileSync(gradleFile, 'utf8');
const source = 'val cmakeVersion = System.getenv("CMAKE_VERSION") ?: "3.30.5"';
const replacement = `val cmakeVersion =
    System.getenv("CMAKE_VERSION")
        ?: rootProject.findProperty("edgezCmakeVersion")?.toString()
        ?: "3.30.5"`;

if (original.includes(replacement)) {
  process.exit(0);
}
if (!original.includes(source)) {
  throw new Error('Unsupported React Native CMake configuration; update the patch script');
}

fs.writeFileSync(gradleFile, original.replace(source, replacement));
console.log('Patched React Native to use the project CMake version when configured.');
