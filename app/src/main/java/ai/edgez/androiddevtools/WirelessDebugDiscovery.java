package ai.edgez.androiddevtools;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class WirelessDebugDiscovery {
    private static final String TAG = "AndroidDevTools";
    private static final String PAIRING_TYPE = "_adb-tls-pairing._tcp.";
    private static final String CONNECT_TYPE = "_adb-tls-connect._tcp.";

    private WirelessDebugDiscovery() {
    }

    static Endpoint discoverPairing(Context context, long timeoutMillis) {
        return discover(context, PAIRING_TYPE, timeoutMillis);
    }

    static Endpoint discoverConnect(Context context, long timeoutMillis) {
        return discover(context, CONNECT_TYPE, timeoutMillis);
    }

    private static Endpoint discover(Context context, String serviceType, long timeoutMillis) {
        NsdManager manager = (NsdManager) context.getApplicationContext()
                .getSystemService(Context.NSD_SERVICE);
        if (manager == null) {
            return null;
        }

        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean resolving = new AtomicBoolean();
        AtomicReference<Endpoint> result = new AtomicReference<>();

        NsdManager.ResolveListener resolver = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "mDNS resolve failed for " + serviceType + ": " + errorCode);
                resolving.set(false);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                int port = serviceInfo.getPort();
                String host = normalizeHost(serviceInfo.getHost());
                if (port > 0 && port <= 65535) {
                    result.set(new Endpoint(host.isEmpty() ? "127.0.0.1" : host, port));
                    done.countDown();
                } else {
                    resolving.set(false);
                }
            }
        };

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String type, int errorCode) {
                Log.w(TAG, "mDNS discovery failed for " + type + ": " + errorCode);
                done.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String type, int errorCode) {
                Log.w(TAG, "mDNS stop failed for " + type + ": " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String type) {
                Log.d(TAG, "mDNS discovery started for " + type);
            }

            @Override
            public void onDiscoveryStopped(String type) {
                Log.d(TAG, "mDNS discovery stopped for " + type);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (resolving.compareAndSet(false, true)) {
                    try {
                        manager.resolveService(serviceInfo, resolver);
                    } catch (RuntimeException exception) {
                        Log.w(TAG, "Unable to resolve " + serviceInfo.getServiceName(), exception);
                        resolving.set(false);
                    }
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "mDNS service lost: " + serviceInfo.getServiceName());
            }
        };

        boolean started = false;
        try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener);
            started = true;
            done.await(timeoutMillis, TimeUnit.MILLISECONDS);
            return result.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException exception) {
            Log.w(TAG, "mDNS discovery error for " + serviceType, exception);
            return null;
        } finally {
            if (started) {
                try {
                    manager.stopServiceDiscovery(listener);
                } catch (RuntimeException exception) {
                    Log.w(TAG, "Unable to stop mDNS discovery", exception);
                }
            }
        }
    }

    private static String normalizeHost(InetAddress address) {
        if (address == null || address.getHostAddress() == null) {
            return "";
        }
        String host = address.getHostAddress().trim();
        int zone = host.indexOf('%');
        return zone > 0 ? host.substring(0, zone) : host;
    }
}

