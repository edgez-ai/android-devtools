#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUBMODULE_DIR="${ROOT_DIR}/third_party/libp2p-go"

if [[ ! -f "${SUBMODULE_DIR}/build-android.sh" ]]; then
  echo "libp2p submodule is missing. Run:" >&2
  echo "  git submodule update --init --recursive" >&2
  exit 2
fi

if [[ -z "${ANDROID_NDK_HOME:-}" && -z "${ANDROID_NDK_ROOT:-}" ]]; then
  echo "ANDROID_NDK_HOME or ANDROID_NDK_ROOT must point to Android NDK 26+" >&2
  exit 2
fi

echo "Building native client from:"
git -C "${SUBMODULE_DIR}" rev-parse HEAD

"${SUBMODULE_DIR}/build-android.sh"

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  library="${ROOT_DIR}/app/src/main/jniLibs/${abi}/libedgejoin.so"
  if [[ ! -s "${library}" ]]; then
    echo "Expected native library was not produced: ${library}" >&2
    exit 1
  fi
done

echo "Native libraries updated successfully."
