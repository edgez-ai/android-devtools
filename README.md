# Android DevTools

Android companion for using a physical device from a remote Flutter/JupyterHub
workspace through [`adb-sidecar`](../adb-sidecar).

The app runs the same native libp2p ADB proxy used by the AutoJs6 `libp2p`
branch, without the AutoJs runtime. It:

- joins the EdgeZ libp2p network and persists the device identity;
- discovers Android Wireless Debugging pairing/connect ports with mDNS;
- pairs the native ADB client with the device;
- exposes the device's local `adbd` through
  `/gvisor/libp2p-tap-tcp/1.0.0`;
- exports permitted USB host/OTG devices as USB/IP frames directly over a
  libp2p stream selected by target port `3240` (there is no Android TCP
  listener);
- supplies AutoJs6's scrcpy server asset to the native client and lazily
  bootstraps it when libp2p target port `8886` is requested;
- keeps the proxy alive in a foreground service and restarts it after boot.

Like AutoJs6, the app starts the libp2p foreground client automatically whenever
stored join configuration exists. Libp2p startup does not wait for Wireless
Debugging discovery; the local ADB target is discovered and refreshed
independently.

## Build

The native `libedgejoin.so` files are copied from AutoJs6 commit
`042d4bc0bee8ade160ac9d659b48e2dfe15b09ae`. The corresponding source is
`jasonhao518/autojs6-libp2p` commit
`c6269288d5a36a2538115082b52469cec26210c5`.

The bundled `app/src/main/assets/scrcpy/scrcpy-server.jar` is copied byte for
byte from the same AutoJs6 `origin/libp2p` revision. Its SHA-256 is
`a2223a3a4249822187906e0fa8b147eb5a9ed94e47a9e8e8b3f07da651149806`.

```sh
git submodule update --init --recursive
./gradlew assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Rebuild the native libp2p client

The source is pinned as the `third_party/libp2p-go` Git submodule. To rebuild
all four `libedgejoin.so` files with the Android NDK selected by Gradle:

```sh
./gradlew buildNative
./gradlew assembleDebug
```

To move to a newer native revision:

```sh
git -C third_party/libp2p-go fetch
git -C third_party/libp2p-go checkout <tested-commit>
./gradlew buildNative assembleDebug
```

Commit both the updated submodule pointer and generated libraries. Keeping the
generated libraries in this repository lets normal Android builds work without
requiring Go, while `buildNative` makes their provenance reproducible.

### GitHub release builds

The **Build Android release** workflow runs manually or for tags matching
`v*`. It builds and uploads:

- `android-devtools-release-unsigned.apk`
- `android-devtools-release-signed.apk`

Configure these repository secrets before running it:

- `ANDROID_KEYSTORE_BASE64`: the release JKS encoded as a single base64 string
- `ANDROID_KEYSTORE_PASSWORD`: the store and key password

The keystore must contain the `edgez-android-release` alias, matching the
Flutter SDK release workflow. Tag builds also attach both APKs to the GitHub
Release.

To create a new Android signing identity, a strong random password, and a
P-256 SDK signing private scalar, then print the three GitHub Actions
`NAME=value` entries:

```sh
./scripts/generate-release-secrets.sh
```

This creates the following local, Git-ignored files with owner-only
permissions:

```text
.local-secrets/android-release-keystore.jks
.local-secrets/github-actions-secrets.env
.local-secrets/keystore-info.txt
```

The environment file contains `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, and `EDGEZ_SDK_SIGNING_PRIVATE_KEY_HEX`. Running
the script again prints the existing values instead of silently replacing the
signing identity. With the GitHub CLI authenticated, install them into a
repository with:

```sh
gh secret set -f .local-secrets/github-actions-secrets.env
```

Alternatively, open **Settings → Secrets and variables → Actions** in the
GitHub repository, choose **New repository secret**, and copy each value from
`.local-secrets/github-actions-secrets.env`. Android DevTools uses the two
`ANDROID_*` secrets; `EDGEZ_SDK_SIGNING_PRIVATE_KEY_HEX` is used by the Flutter
SDK credential workflow.

Back up `.local-secrets` in a secure password manager or encrypted vault.
GitHub does not allow secret values to be downloaded later. Never regenerate
or replace the Android signing key after distributing an APK signed with it;
future upgrades must use the same keystore.

## Device setup

1. Install and open Android DevTools.
2. Grant notification and Nearby Wi-Fi permissions, then allow unrestricted
   battery use when Android prompts. These keep discovery and the foreground
   relay connection reliable.
3. Enter only the device serial and join key, then tap **Join network**. The
   join endpoint and device metadata are supplied internally. Tap **How to get
   the serial and join key** for an in-app walkthrough of the edgez.ai Devices
   page, including the one-time join-key warning.
4. Tap **Pair from notification**, then choose **Pair device with pairing
   code** in Wireless Debugging settings.
5. Expand the Android DevTools notification and enter the six-digit code using
   its inline reply action. Pairing codes are never entered in the activity.
6. Copy the peer ID shown by the app into the JupyterHub deployment as
   `LIBP2P_AGENT_MOBILE_PEER_ID`.

The app restarts the tunnel after boot, but Android's Wireless Debugging switch
may need to be enabled again after a reboot. Open the app and tap
**Refresh ADB & start** after enabling it.

### USB host / OTG setup

1. Connect the ESP32, nRF54 development board, CMSIS-DAP probe, J-Link, or
   other target through a powered OTG adapter or hub.
2. Accept Android's USB access prompt for Android DevTools. Only devices with
   granted permission are included in the USB/IP device list.
3. Configure `adb-sidecar` with `USBIP_REMOTE_BUSIDS=all`, or use the bus ID
   reported in logcat (for example `1-2`) when only one device should attach.
4. Run the programmer/debugger in Jupyter exactly as for a locally connected
   USB device.

Android's USB Host API feeds USB/IP frames to the native client over an
abstract Unix socket; the native client carries those frames on the raw
libp2p stream. Only `adb-sidecar` provides a loopback TCP endpoint, because
the Linux `usbip` client expects TCP.

The server implements USB/IP control, bulk, and interrupt transfers, which
covers CDC/USB serial and common debug probes. Isochronous transfers are
rejected. Flashing and debugging are latency-sensitive, so use conservative
adapter speeds and longer OpenOCD timeouts over high-latency relay paths.

The protocol design was checked against
[`cgutman/USBIPServerForAndroid`](https://github.com/cgutman/USBIPServerForAndroid)
and [`jiegec/usbip`](https://github.com/jiegec/usbip). The Android server in
this repository is an independent implementation using Android's public USB
Host API.

## JupyterHub / adb-sidecar

Run `adb-sidecar` in the same Kubernetes Pod as the Flutter/Jupyter container:

```yaml
- name: adb-sidecar
  image: jasonhao123/adb-sidecar:v0.1.0
  env:
    - name: EDGEZ_LIBP2P_CONFIG_JSON
      valueFrom:
        secretKeyRef:
          name: edgez-libp2p
          key: config.json
    - name: LIBP2P_AGENT_MOBILE_PEER_ID
      value: "12D3KooW..."
    - name: LIBP2P_AGENT_MOBILE_ADB_PORT
      value: "5555"
    - name: LIBP2P_TUNNEL_LOCAL_PORT
      value: "5555"
    - name: USBIP_REMOTE_BUSIDS
      value: "all"
```

In the Jupyter/Flutter container:

```sh
adb connect 127.0.0.1:5555
adb devices
flutter run -d 127.0.0.1:5555
```

Once `flutter run` is attached, use `r` for hot reload and `R` for hot
restart. The VM service and all forwarded ports travel inside the same ADB
connection, so no extra libp2p ports are required.

For USB, the sidecar—not Jupyter—runs `usbip attach` against the local
`127.0.0.1:3240` libp2p bridge. The Kubernetes node must provide `vhci-hcd`,
and the Jupyter hardware profile must mount the node's `/dev/bus/usb` with a
device-cgroup policy that permits USB access. See the `adb-sidecar` README for
the complete hardware-profile example and isolation caveat.

For the custom scrcpy consumer used with AutoJs6, open a libp2p tunnel whose
remote target is `8886`. The first connection makes the Android native client
push `/data/local/tmp/scrcpy-server.jar`, launch
`com.genymobile.scrcpy.Server`, and proxy its local port `8886`.

## Security notes

- Join configuration and identity keys are kept in app-private preferences.
- The Wireless Debugging RSA key and TLS certificate are stored under the
  app-private `files/adb/adbkey` path; no shared-storage permission is needed.
- Preferences use the same `edgejoin` file and keys as the AutoJs6 client:
  `config`, `peer_id`, `private_key`, `public_key`, `join_response`,
  `join_key`, `serial_number`, `adb_proxy_host`, and `adb_proxy_port`.
- The sidecar binds the proxied ADB endpoint to Pod loopback by default.
- Anyone with the device peer ID and a valid identity on the same relay fabric
  may attempt to open the tap protocol. Relay/network admission must therefore
  be treated as the authorization boundary.
