import { NativeEventEmitter, NativeModules } from 'react-native';

export type EdgezStatus = {
  configured: boolean;
  peerId: string | null;
  proxyState: string;
  proxyDetail: string;
  adbPaired: boolean;
  notificationsGranted: boolean;
  nearbyWifiGranted: boolean;
  batteryUnrestricted: boolean;
  usbAttached: number;
  usbPermitted: number;
};

type EdgezNativeModule = {
  getStatus(): Promise<EdgezStatus>;
  scanAndJoin(): Promise<string>;
  beginPairing(): Promise<void>;
  requestBatteryExemption(): Promise<void>;
  startProxy(): Promise<void>;
  stopProxy(): Promise<void>;
};

const nativeModule = NativeModules.EdgezNative as EdgezNativeModule | undefined;

export function requireEdgezNative(): EdgezNativeModule {
  if (!nativeModule) {
    throw new Error('EdgezNative is not linked. Rebuild the development client.');
  }
  return nativeModule;
}

export const edgezEvents = nativeModule
  ? new NativeEventEmitter(NativeModules.EdgezNative)
  : null;
