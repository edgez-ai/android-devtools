#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
expo_root="${EXPO_GO_SOURCE_DIR:-${repo_root}/.expo-go-sdk-57}"
expo_commit="2f42cf5057404fd1a07d09a0f245018d5f056236"
export ORG_GRADLE_PROJECT_edgezRoot="${repo_root}"
export COREPACK_HOME="${COREPACK_HOME:-${repo_root}/.corepack-cache}"
export NODE_ENV="${NODE_ENV:-development}"

android_sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -n "${android_sdk_root}" && ! -x "${android_sdk_root}/cmake/3.30.5/bin/cmake" ]]; then
  system_cmake="$(command -v cmake || true)"
  if [[ -n "${system_cmake}" ]]; then
    export EDGEZ_CMAKE_BINARY="${EDGEZ_CMAKE_BINARY:-${system_cmake}}"
    read -r _ _ detected_cmake_version < <("${EDGEZ_CMAKE_BINARY}" --version)
    export EDGEZ_CMAKE_VERSION="${EDGEZ_CMAKE_VERSION:-${detected_cmake_version}}"
    export CMAKE_VERSION="${CMAKE_VERSION:-${EDGEZ_CMAKE_VERSION}}"
  fi
fi

if [[ ! -d "${expo_root}/.git" ]]; then
  git clone --filter=blob:none --no-checkout https://github.com/expo/expo.git "${expo_root}"
  git -C "${expo_root}" checkout --detach "${expo_commit}"
  git -C "${expo_root}" \
    -c url.https://github.com/.insteadOf=git@github.com: \
    submodule update --init --recursive --depth 1
fi

if [[ ! -f "${expo_root}/react-native-lab/react-native/package.json" ]]; then
  git -C "${expo_root}" \
    -c url.https://github.com/.insteadOf=git@github.com: \
    submodule update --init --recursive --depth 1
fi

actual_commit="$(git -C "${expo_root}" rev-parse HEAD)"
if [[ "${actual_commit}" != "${expo_commit}" ]]; then
  echo "Expo source at ${expo_root} is ${actual_commit}; expected ${expo_commit}." >&2
  echo "Choose an empty EXPO_GO_SOURCE_DIR or move the existing cache aside." >&2
  exit 1
fi

node "${repo_root}/scripts/prepare-expo-go-57.mjs" "${expo_root}"

cd "${expo_root}"
pnpm install --no-frozen-lockfile
git lfs pull
pnpm --dir tools et android-generate-dynamic-macros --configuration mobileDebug
pnpm --dir packages/expo build
corepack yarn --cwd "${expo_root}/react-native-lab/react-native" install --frozen-lockfile

cd "${expo_root}/apps/expo-go/android"
./gradlew :app:assembleMobileDebug \
  -PedgezRoot="${repo_root}" \
  -PreactNativeArchitectures=arm64-v8a

source_apk="${expo_root}/apps/expo-go/android/app/build/outputs/apk/mobile/debug/app-mobile-debug.apk"
output_dir="${repo_root}/app/build/outputs/apk/expoGo57"
output_apk="${output_dir}/android-devtools-expo-go-57-ble-debug.apk"
mkdir -p "${output_dir}"
cp "${source_apk}" "${output_apk}"

echo "Custom Expo Go 57 APK: ${output_apk}"
