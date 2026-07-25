#!/usr/bin/env bash

set -euo pipefail
umask 077

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd -- "$script_dir/.." && pwd)"
output_dir="${EDGEZ_SECRETS_OUTPUT_DIR:-$repository_root/.local-secrets}"
secrets_file="$output_dir/github-actions-secrets.env"
keystore_file="$output_dir/android-release-keystore.jks"
keystore_info_file="$output_dir/keystore-info.txt"
keystore_alias="edgez-android-release"
temporary_sdk_key=""

cleanup() {
  if [[ -n "$temporary_sdk_key" && -f "$temporary_sdk_key" ]]; then
    rm -f -- "$temporary_sdk_key"
  fi
}
trap cleanup EXIT INT TERM

if [[ ! -f "$secrets_file" || ! -f "$keystore_file" ]]; then
  if [[ -e "$output_dir" ]]; then
    printf 'Refusing to overwrite incomplete secret material in %s\n' "$output_dir" >&2
    printf 'Move or back up that directory, then run this script again.\n' >&2
    exit 1
  fi

  command -v openssl >/dev/null 2>&1 || {
    printf 'openssl is required to generate cryptographically secure values.\n' >&2
    exit 1
  }
  command -v keytool >/dev/null 2>&1 || {
    printf 'keytool is required; install a JDK and run this script again.\n' >&2
    exit 1
  }

  mkdir -m 700 "$output_dir"

  android_keystore_password="$(openssl rand -hex 32)"
  temporary_sdk_key="$(mktemp "${TMPDIR:-/tmp}/edgez-sdk-signing-key.XXXXXX")"
  openssl ecparam \
    -name prime256v1 \
    -genkey \
    -noout \
    -out "$temporary_sdk_key"

  sdk_signing_private_key_hex="$(
    LC_ALL=C openssl ec -in "$temporary_sdk_key" -text -noout 2>/dev/null |
      awk '
        /^priv:/ { in_private = 1; next }
        /^pub:/ { in_private = 0 }
        in_private {
          gsub(/[^0-9a-fA-F]/, "")
          printf "%s", $0
        }
      '
  )"
  if [[ ! "$sdk_signing_private_key_hex" =~ ^[0-9a-fA-F]{64}$ ]]; then
    printf 'Failed to extract a valid 32-byte P-256 private scalar.\n' >&2
    exit 1
  fi
  sdk_signing_private_key_hex="$(
    printf '%s' "$sdk_signing_private_key_hex" | tr 'A-F' 'a-f'
  )"

  keytool \
    -genkeypair \
    -noprompt \
    -keystore "$keystore_file" \
    -storetype JKS \
    -storepass "$android_keystore_password" \
    -keypass "$android_keystore_password" \
    -alias "$keystore_alias" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -dname "CN=EdgeZ Android Release, O=EdgeZ AI, C=SE" \
    >/dev/null

  android_keystore_base64="$(openssl base64 -A < "$keystore_file")"
  printf '%s\n' \
    "ANDROID_KEYSTORE_BASE64=$android_keystore_base64" \
    "ANDROID_KEYSTORE_PASSWORD=$android_keystore_password" \
    "EDGEZ_SDK_SIGNING_PRIVATE_KEY_HEX=$sdk_signing_private_key_hex" \
    > "$secrets_file"

  keytool \
    -list \
    -v \
    -keystore "$keystore_file" \
    -storepass "$android_keystore_password" \
    -alias "$keystore_alias" \
    > "$keystore_info_file"

  printf 'Generated new release secrets in %s\n' "$output_dir" >&2
fi

chmod 700 "$output_dir"
chmod 600 "$secrets_file" "$keystore_file"
if [[ -f "$keystore_info_file" ]]; then
  chmod 600 "$keystore_info_file"
fi

printf '\nUpload these three NAME=value entries as GitHub Actions repository secrets:\n' >&2
printf 'Settings -> Secrets and variables -> Actions -> New repository secret\n\n' >&2
printf 'WARNING: Keep this output private and securely back up %s\n\n' "$output_dir" >&2

awk '
  /^(ANDROID_KEYSTORE_BASE64|ANDROID_KEYSTORE_PASSWORD|EDGEZ_SDK_SIGNING_PRIVATE_KEY_HEX)=/ {
    print
    found++
  }
  END {
    if (found != 3) {
      print "Expected exactly three release secrets, found " found > "/dev/stderr"
      exit 1
    }
  }
' "$secrets_file"
