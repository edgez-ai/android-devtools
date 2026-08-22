import {StatusBar} from 'expo-status-bar';
import React, {useMemo, useRef, useState} from 'react';
import {NativeModules, Pressable, StyleSheet, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {
  EdgezOrganicMap,
  type EdgezMapCamera,
  type EdgezOrganicMapRef,
} from '@edgez/react-native-sdk';

const nodes = [
  {id: 'stockholm-base', label: 'Stockholm base', latitude: 59.3293, longitude: 18.0686, marker: 'blue'},
  {id: 'vaxholm-relay', label: 'Vaxholm relay', latitude: 59.4022, longitude: 18.3532, marker: 'green'},
  {id: 'grinda-sensor', label: 'Grinda sensor', latitude: 59.4114, longitude: 18.5586, marker: 'orange'},
];

export default function App() {
  const map = useRef<EdgezOrganicMapRef>(null);
  const [ready, setReady] = useState(false);
  const [night, setNight] = useState(false);
  const [perspective3d, setPerspective3d] = useState(false);
  const [camera, setCamera] = useState<EdgezMapCamera>();
  const [region, setRegion] = useState<string>();
  const [downloadStatus, setDownloadStatus] = useState('');
  const [error, setError] = useState('');
  const bridgeReady = useMemo(() => Boolean(NativeModules.EdgezReactNativeSdk), []);

  const toggleNight = () => {
    const next = !night;
    setNight(next);
    map.current?.setMapTheme(next ? 'night' : 'day');
  };

  const toggle3d = () => {
    const next = !perspective3d;
    setPerspective3d(next);
    map.current?.setPerspective3d(next);
  };

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <StatusBar style="light" />
      <EdgezOrganicMap
        ref={map}
        nodes={nodes}
        centerLatitude={59.38}
        centerLongitude={18.3}
        zoom={9}
        enableMapDownloads
        style={StyleSheet.absoluteFill}
        onMapReady={() => setReady(true)}
        onCameraChanged={setCamera}
        onMapRegionAvailable={setRegion}
        onMapDownloadUpdate={update => {
          setRegion(undefined);
          setDownloadStatus(update.status);
        }}
        onMapError={setError}
      />

      <View style={styles.header} pointerEvents="none">
        <Text style={styles.title}>EdgeZ off-grid map</Text>
        <Text style={styles.subtitle}>Embedded Expo bundle · {nodes.length} simulated mesh nodes</Text>
        <View style={styles.healthRow}>
          <HealthBadge label="SDK bridge" healthy={bridgeReady} />
          <HealthBadge label="Map renderer" healthy={ready} />
        </View>
      </View>

      <View style={styles.controls}>
        <MapButton label={night ? 'Night' : 'Day'} active={night} onPress={toggleNight} />
        <MapButton label={perspective3d ? '3D' : '2D'} active={perspective3d} onPress={toggle3d} />
        <MapButton label="Center" onPress={() => map.current?.setCamera({latitude: 59.38, longitude: 18.3, zoom: 9})} />
      </View>

      {region ? (
        <View style={styles.downloadCard}>
          <Text style={styles.downloadTitle}>Make {region} available offline?</Text>
          <Text style={styles.downloadText}>The map will remain available when the device has no internet connection.</Text>
          <View style={styles.downloadActions}>
            <MapButton label="Download" active onPress={() => {
              map.current?.downloadRegion(region);
              setRegion(undefined);
            }} />
            <MapButton label="Not now" onPress={() => {
              map.current?.dismissDownloadRegion(region);
              setRegion(undefined);
            }} />
          </View>
        </View>
      ) : null}

      {downloadStatus || error ? (
        <View style={[styles.notice, error ? styles.errorNotice : undefined]}>
          <Text style={styles.noticeText}>{error || downloadStatus}</Text>
        </View>
      ) : null}

      <View style={styles.footer} pointerEvents="none">
        <Text style={styles.footerText}>
          {camera
            ? `${camera.latitude.toFixed(4)}, ${camera.longitude.toFixed(4)} · zoom ${camera.zoom.toFixed(1)}`
            : 'Move and zoom the map to validate the native renderer'}
        </Text>
        <Text style={styles.attribution}>Map data © OpenStreetMap contributors</Text>
      </View>
    </SafeAreaView>
  );
}

function HealthBadge({label, healthy}: {label: string; healthy: boolean}) {
  return (
    <View style={[styles.healthBadge, healthy ? styles.healthy : styles.pending]}>
      <Text style={styles.healthText}>{healthy ? '✓' : '…'} {label}</Text>
    </View>
  );
}

function MapButton({label, active = false, onPress}: {label: string; active?: boolean; onPress: () => void}) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({pressed}) => [styles.button, active && styles.buttonActive, pressed && styles.buttonPressed]}>
      <Text style={styles.buttonText}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1, backgroundColor: '#DCE4EA'},
  header: {position: 'absolute', left: 12, right: 86, top: 12, padding: 14, borderRadius: 16, backgroundColor: '#101C2DEB'},
  title: {color: '#FFFFFF', fontSize: 19, fontWeight: '900'},
  subtitle: {color: '#B7C6D9', fontSize: 12, marginTop: 3},
  healthRow: {flexDirection: 'row', gap: 7, marginTop: 10},
  healthBadge: {borderRadius: 20, paddingHorizontal: 9, paddingVertical: 5},
  healthy: {backgroundColor: '#116B55'},
  pending: {backgroundColor: '#735C18'},
  healthText: {color: '#FFFFFF', fontSize: 11, fontWeight: '800'},
  controls: {position: 'absolute', right: 12, top: 12, gap: 8},
  button: {minWidth: 66, borderRadius: 11, borderWidth: 1, borderColor: '#4A617D', backgroundColor: '#17263AEE', paddingHorizontal: 12, paddingVertical: 10, alignItems: 'center'},
  buttonActive: {backgroundColor: '#0B746C', borderColor: '#45D9C9'},
  buttonPressed: {opacity: 0.78},
  buttonText: {color: '#FFFFFF', fontSize: 12, fontWeight: '900'},
  downloadCard: {position: 'absolute', left: 12, right: 12, bottom: 74, borderRadius: 16, backgroundColor: '#101C2DF2', padding: 14},
  downloadTitle: {color: '#FFFFFF', fontSize: 15, fontWeight: '900'},
  downloadText: {color: '#B7C6D9', fontSize: 12, lineHeight: 17, marginTop: 4},
  downloadActions: {flexDirection: 'row', gap: 8, marginTop: 11},
  notice: {position: 'absolute', left: 12, right: 12, bottom: 74, padding: 11, borderRadius: 12, backgroundColor: '#116B55EE'},
  errorNotice: {backgroundColor: '#8B2635EE'},
  noticeText: {color: '#FFFFFF', fontSize: 12, fontWeight: '700'},
  footer: {position: 'absolute', left: 10, right: 10, bottom: 8, borderRadius: 9, paddingHorizontal: 10, paddingVertical: 7, backgroundColor: '#FFFFFFD9'},
  footerText: {color: '#17263A', fontSize: 11, fontWeight: '700'},
  attribution: {color: '#52667D', fontSize: 9, marginTop: 2},
});
