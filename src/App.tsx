import { openMenu } from 'expo-dev-client';
import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  AppState,
  PermissionsAndroid,
  Platform,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import {
  EdgezStatus,
  edgezEvents,
  requireEdgezNative,
} from './native/EdgezNative';

const colors = {
  blue: '#1859C9',
  blueDark: '#0A2F73',
  blueSoft: '#EAF1FF',
  background: '#F4F7FC',
  surface: '#FFFFFF',
  text: '#13213C',
  muted: '#60708F',
  success: '#18794E',
  warning: '#A15C00',
  error: '#C73535',
  connecting: '#3478E5',
  offline: '#8C98AD',
  statusBackground: '#EEF4FF',
};

const initialStatus: EdgezStatus = {
  configured: false,
  peerId: null,
  proxyState: 'disconnected',
  proxyDetail: '',
  adbPaired: false,
  notificationsGranted: Number(Platform.Version) < 33,
  nearbyWifiGranted: Number(Platform.Version) < 33,
  batteryUnrestricted: false,
  usbAttached: 0,
  usbPermitted: 0,
};

const proxyPresentation: Record<
  string,
  { title: string; detail: string; color: string }
> = {
  disconnected: {
    title: 'Proxy disconnected',
    detail: 'Join the EdgeZ mesh, then start the proxy.',
    color: colors.offline,
  },
  connecting: {
    title: 'Connecting to EdgeZ',
    detail: 'Starting the libp2p client and discovering local ADB.',
    color: colors.connecting,
  },
  mesh_online: {
    title: 'Mesh online',
    detail: 'libp2p is connected. Enable Wireless Debugging to expose ADB.',
    color: colors.success,
  },
  adb_online: {
    title: 'ADB proxy online',
    detail: 'The cloud workspace can reach this device through libp2p.',
    color: colors.success,
  },
  stopping: {
    title: 'Stopping proxy',
    detail: 'Closing the ADB route and libp2p connection.',
    color: colors.warning,
  },
  error: {
    title: 'Proxy needs attention',
    detail: 'The proxy could not start. Review the detail below and try again.',
    color: colors.error,
  },
};

const runningStates = new Set(['connecting', 'mesh_online', 'adb_online']);

export default function App() {
  const [status, setStatus] = useState(initialStatus);
  const [selectedStep, setSelectedStep] = useState(0);
  const [statusMessage, setStatusMessage] = useState(
    'Ready. Your cloud workspace connects to adb-sidecar at 127.0.0.1:5555.',
  );
  const [busy, setBusy] = useState(false);

  const refreshStatus = useCallback(async () => {
    try {
      setStatus(await requireEdgezNative().getStatus());
    } catch (error) {
      setStatusMessage(error instanceof Error ? error.message : String(error));
    }
  }, []);

  useEffect(() => {
    const edgezSubscription = edgezEvents?.addListener(
      'EdgezStatusChanged',
      (nextStatus: EdgezStatus) => setStatus(nextStatus),
    );
    const appStateSubscription = AppState.addEventListener('change', state => {
      if (state === 'active') void refreshStatus();
    });
    void refreshStatus();
    return () => {
      edgezSubscription?.remove();
      appStateSubscription.remove();
    };
  }, [refreshStatus]);

  const run = useCallback(
    async (
      initialMessage: string,
      action: () => Promise<void>,
      successMessage?: string,
    ) => {
      setBusy(true);
      setStatusMessage(initialMessage);
      try {
        await action();
        if (successMessage) setStatusMessage(successMessage);
        await refreshStatus();
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setStatusMessage(`Error: ${message}`);
        Alert.alert('EdgeZ DevTools', message);
      } finally {
        setBusy(false);
      }
    },
    [refreshStatus],
  );

  const requestRequiredPermissions = useCallback(async () => {
    setBusy(true);
    try {
      if (Platform.OS === 'android' && Platform.Version >= 33) {
        const result = await PermissionsAndroid.requestMultiple([
          PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
          PermissionsAndroid.PERMISSIONS.NEARBY_WIFI_DEVICES,
        ]);
        const granted = Object.values(result).every(
          value => value === PermissionsAndroid.RESULTS.GRANTED,
        );
        if (!granted) {
          setStatusMessage(
            'Nearby Wi-Fi and notification permissions are needed for discovery and reliable background status.',
          );
          await refreshStatus();
          return;
        }
      }
      setStatusMessage(
        'Pairing permissions granted. Allow unrestricted battery use next.',
      );
      await requireEdgezNative().requestBatteryExemption();
      await refreshStatus();
    } catch (error) {
      setStatusMessage(`Error: ${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setBusy(false);
    }
  }, [refreshStatus]);

  const scanAndJoin = useCallback(async () => {
    setBusy(true);
    setStatusMessage('Point the camera at the pairing QR code shown on edgez.ai.');
    try {
      const peerId = await requireEdgezNative().scanAndJoin();
      setStatusMessage(
        `Joined successfully. The mesh connection is starting in the background. Peer ID: ${peerId}`,
      );
      setSelectedStep(2);
      await refreshStatus();
    } catch (error) {
      setStatusMessage(`Error: ${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setBusy(false);
    }
  }, [refreshStatus]);

  const pairWirelessDebugging = useCallback(
    () =>
      run(
        'Starting Wireless Debugging pairing…',
        () => requireEdgezNative().beginPairing(),
        'Pairing search started. Open “Pair device with pairing code”, then reply to the EdgeZ DevTools notification.',
      ),
    [run],
  );

  const toggleProxy = useCallback(() => {
    const running = runningStates.has(status.proxyState);
    return run(
      running ? 'Proxy stop requested.' : 'Proxy start requested…',
      () =>
        running
          ? requireEdgezNative().stopProxy()
          : requireEdgezNative().startProxy(),
      running
        ? 'Proxy stop requested.'
        : 'Proxy start requested. Check the persistent notification for status.',
    );
  }, [run, status.proxyState]);

  const proxy =
    proxyPresentation[status.proxyState] ?? proxyPresentation.disconnected!;
  const allPermissions =
    status.notificationsGranted &&
    status.nearbyWifiGranted &&
    status.batteryUnrestricted;
  const stepComplete = [allPermissions, status.configured, status.adbPaired];
  const usbLabel =
    status.usbPermitted > 0
      ? `USB ready · ${status.usbPermitted}`
      : status.usbAttached > 0
        ? 'USB permission required'
        : 'No USB device attached';
  const usbColor =
    status.usbPermitted > 0
      ? colors.success
      : status.usbAttached > 0
        ? colors.warning
        : colors.offline;
  const proxyRunning = runningStates.has(status.proxyState);
  const proxyButtonLabel =
    status.proxyState === 'stopping'
      ? 'Stopping…'
      : proxyRunning
        ? 'Stop proxy'
        : status.proxyState === 'error'
          ? 'Retry proxy'
          : 'Start proxy';

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar style="light" />
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.hero}>
          <Text style={styles.heroTitle}>EdgeZ DevTools</Text>
          <Text style={styles.heroSubtitle}>
            Connect a physical Android device to your cloud workspace through the EdgeZ mesh.
          </Text>
        </View>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>Connection status</Text>
          <Text selectable style={styles.peerId}>
            {status.peerId ? `Peer ID: ${status.peerId}` : 'Not connected to EdgeZ'}
          </Text>
          <View style={styles.statusRow}>
            <View style={[styles.proxyDot, { backgroundColor: proxy.color }]} />
            <View style={styles.statusCopy}>
              <Text style={styles.proxyTitle}>{proxy.title}</Text>
              <Text style={styles.description}>
                {proxy.detail}
                {status.proxyDetail ? `\n${status.proxyDetail}` : ''}
              </Text>
            </View>
          </View>
          <View style={styles.usbRow}>
            <View style={[styles.usbDot, { backgroundColor: usbColor }]} />
            <Text style={styles.description}>{usbLabel}</Text>
          </View>
          <ActionButton
            label={proxyButtonLabel}
            primary={!proxyRunning}
            disabled={busy || status.proxyState === 'stopping'}
            onPress={toggleProxy}
          />
          <ActionButton
            label="Open developer menu"
            onPress={() => {
              setStatusMessage('Opening the Expo development-client menu.');
              openMenu();
            }}
          />
        </View>

        <View style={styles.tabs}>
          {['Permissions', 'Join', 'Pair ADB'].map((label, index) => (
            <Pressable
              key={label}
              onPress={() => setSelectedStep(index)}
              style={[
                styles.tab,
                selectedStep === index ? styles.tabSelected : styles.tabIdle,
              ]}>
              <Text
                style={[
                  styles.tabText,
                  selectedStep === index ? styles.tabTextSelected : styles.tabTextIdle,
                ]}>
                {index + 1}{stepComplete[index] ? ' ✅' : ''}{'\n'}{label}
              </Text>
            </Pressable>
          ))}
        </View>

        {selectedStep === 0 && (
          <StepCard number="1" title="Allow background discovery">
            <Text style={styles.stepDescription}>
              Notifications show tunnel health, Nearby Wi-Fi finds Wireless Debugging, and unrestricted battery access keeps the relay online.
            </Text>
            <View style={styles.permissionList}>
              <PermissionLine label="Notifications" granted={status.notificationsGranted} />
              <PermissionLine label="Nearby Wi-Fi" granted={status.nearbyWifiGranted} />
              <PermissionLine label="Battery unrestricted" granted={status.batteryUnrestricted} />
            </View>
            <ActionButton
              label="Grant required permissions"
              disabled={busy}
              onPress={requestRequiredPermissions}
            />
          </StepCard>
        )}

        {selectedStep === 1 && (
          <StepCard number="2" title="Join your EdgeZ project">
            <Text style={styles.stepDescription}>
              Generate a join key on the device detail page, then scan its QR code. The serial and key are validated automatically.
            </Text>
            <ActionButton
              label="Scan QR code & join"
              primary
              disabled={busy}
              onPress={scanAndJoin}
            />
          </StepCard>
        )}

        {selectedStep === 2 && (
          <StepCard number="3" title="Pair Wireless Debugging">
            <Text style={styles.stepDescription}>
              Open “Pair device with pairing code” in Android settings, then enter the six-digit code from the EdgeZ DevTools notification.
            </Text>
            <ActionButton
              label="Pair from notification"
              disabled={busy || !status.configured}
              onPress={pairWirelessDebugging}
            />
          </StepCard>
        )}

        <Text selectable style={styles.statusMessage}>{statusMessage}</Text>
      </ScrollView>
    </SafeAreaView>
  );
}

function StepCard({
  number,
  title,
  children,
}: {
  number: string;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <View style={styles.card}>
      <View style={styles.stepTitleRow}>
        <View style={styles.stepBadge}><Text style={styles.stepBadgeText}>{number}</Text></View>
        <Text style={styles.cardTitle}>{title}</Text>
      </View>
      {children}
    </View>
  );
}

function PermissionLine({ label, granted }: { label: string; granted: boolean }) {
  return (
    <Text style={[styles.permissionLine, { color: granted ? colors.success : colors.warning }]}>
      {granted ? '✓' : '○'} {label}
    </Text>
  );
}

function ActionButton({
  label,
  onPress,
  disabled = false,
  primary = false,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  primary?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        primary ? styles.buttonPrimary : styles.buttonSecondary,
        pressed && styles.buttonPressed,
        disabled && styles.buttonDisabled,
      ]}>
      <Text style={primary ? styles.buttonTextPrimary : styles.buttonTextSecondary}>
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  safeArea: { flex: 1, backgroundColor: colors.blueDark },
  content: { padding: 16, paddingTop: 12, paddingBottom: 24, gap: 12, backgroundColor: colors.background, minHeight: '100%' },
  hero: { padding: 16, borderRadius: 18, backgroundColor: colors.blueDark, elevation: 2 },
  heroTitle: { color: colors.surface, fontSize: 24, fontWeight: '800' },
  heroSubtitle: { color: '#E4EEFF', fontSize: 13, lineHeight: 18, marginTop: 4 },
  card: { padding: 18, borderRadius: 20, backgroundColor: colors.surface, elevation: 2, gap: 10 },
  cardTitle: { color: colors.text, fontSize: 19, fontWeight: '800', flexShrink: 1 },
  peerId: { color: colors.muted, fontSize: 13, marginTop: -4, marginBottom: 4 },
  statusRow: { flexDirection: 'row', alignItems: 'center' },
  statusCopy: { flex: 1 },
  proxyDot: { width: 12, height: 12, borderRadius: 6, marginRight: 10 },
  proxyTitle: { color: colors.text, fontSize: 16, fontWeight: '800' },
  description: { color: colors.muted, fontSize: 13, lineHeight: 18, marginTop: 2 },
  usbRow: { flexDirection: 'row', alignItems: 'center', marginTop: 2 },
  usbDot: { width: 8, height: 8, borderRadius: 4, marginHorizontal: 2, marginRight: 12 },
  tabs: { flexDirection: 'row', gap: 8 },
  tab: { flex: 1, minHeight: 58, borderRadius: 10, justifyContent: 'center', paddingHorizontal: 6, paddingVertical: 7 },
  tabSelected: { backgroundColor: colors.blue },
  tabIdle: { backgroundColor: colors.blueSoft },
  tabText: { fontSize: 12, fontWeight: '800', textAlign: 'center', lineHeight: 17 },
  tabTextSelected: { color: colors.surface },
  tabTextIdle: { color: colors.blue },
  stepTitleRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  stepBadge: { width: 28, height: 28, borderRadius: 14, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.blue },
  stepBadgeText: { color: colors.surface, fontSize: 13, fontWeight: '800' },
  stepDescription: { color: colors.muted, fontSize: 14, lineHeight: 20 },
  permissionList: { marginTop: 2, marginBottom: -2 },
  permissionLine: { fontSize: 13, lineHeight: 21, fontWeight: '600' },
  button: { minHeight: 46, borderRadius: 10, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16, marginTop: 2 },
  buttonPrimary: { backgroundColor: colors.blue },
  buttonSecondary: { backgroundColor: colors.blueSoft },
  buttonPressed: { opacity: 0.82 },
  buttonDisabled: { opacity: 0.42 },
  buttonTextPrimary: { color: colors.surface, fontSize: 14, fontWeight: '800' },
  buttonTextSecondary: { color: colors.blue, fontSize: 14, fontWeight: '800' },
  statusMessage: { color: colors.text, backgroundColor: colors.statusBackground, borderRadius: 14, paddingHorizontal: 14, paddingVertical: 13, fontSize: 13, lineHeight: 18 },
});
