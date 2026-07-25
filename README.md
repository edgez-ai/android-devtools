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
- keeps the proxy alive in a foreground service and restarts it after boot.

## Build

The native `libedgejoin.so` files are copied from AutoJs6 commit
`042d4bc0bee8ade160ac9d659b48e2dfe15b09ae`. The corresponding source is
`jasonhao518/autojs6-libp2p` commit
`c6269288d5a36a2538115082b52469cec26210c5`.

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

## Device setup

1. Install and open Android DevTools.
2. Enter the join endpoint, serial/endpoint name, and join key, then tap
   **Join network**.
3. Open **Wireless debugging** and choose **Pair device with pairing code**.
4. Enter the six-digit code in Android DevTools and tap
   **Discover, pair & start**. Keep the system pairing dialog open while
   discovery runs.
5. Copy the peer ID shown by the app into the JupyterHub deployment as
   `LIBP2P_AGENT_MOBILE_PEER_ID`.

The app restarts the tunnel after boot, but Android's Wireless Debugging switch
may need to be enabled again after a reboot. Open the app and tap
**Refresh ADB & start** after enabling it.

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

## Security notes

- Join configuration and identity keys are kept in app-private preferences.
- The sidecar binds the proxied ADB endpoint to Pod loopback by default.
- Anyone with the device peer ID and a valid identity on the same relay fabric
  may attempt to open the tap protocol. Relay/network admission must therefore
  be treated as the authorization boundary.
