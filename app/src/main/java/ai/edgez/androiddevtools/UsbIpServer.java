package ai.edgez.androiddevtools;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Userspace USB/IP server backed by Android's public USB Host APIs.
 *
 * <p>The server accepts USB/IP frames on an Android abstract Unix socket.
 * The native libp2p tap proxy routes remote target port 3240 directly to that
 * socket, so Android does not expose a TCP/IP listener.</p>
 */
final class UsbIpServer implements AutoCloseable {
    static final int ROUTE_PORT = 3240;
    private static final String SOCKET_NAME = "edgez-usbip";
    private static final String TAG = "AndroidDevTools";
    private static final String ACTION_USB_PERMISSION =
            "ai.edgez.androiddevtools.USB_IP_PERMISSION";

    private static final int USBIP_VERSION = 0x0111;
    private static final int OP_REQ_IMPORT = 0x8003;
    private static final int OP_REP_IMPORT = 0x0003;
    private static final int OP_REQ_DEVLIST = 0x8005;
    private static final int OP_REP_DEVLIST = 0x0005;
    private static final int USBIP_CMD_SUBMIT = 0x0001;
    private static final int USBIP_CMD_UNLINK = 0x0002;
    private static final int USBIP_RET_SUBMIT = 0x0003;
    private static final int USBIP_RET_UNLINK = 0x0004;
    private static final int ST_OK = 0;
    private static final int ST_NA = 1;
    private static final int USBIP_DIR_OUT = 0;
    private static final int USBIP_DIR_IN = 1;
    private static final int EIO = -5;
    private static final int ECONNRESET = -104;
    private static final int MAX_TRANSFER = 4 * 1024 * 1024;
    private static final int TRANSFER_TIMEOUT_MS = 1000;

    private final Context context;
    private final UsbManager usbManager;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final Set<LocalSocket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile LocalServerSocket listener;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = usbDeviceExtra(intent);
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Log.i(TAG, "USB/IP permission device=" + deviceLabel(device)
                        + " granted=" + granted);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                requestPermission(device);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.i(TAG, "USB/IP device detached: " + deviceLabel(device));
            }
        }
    };

    UsbIpServer(Context context) {
        this.context = context.getApplicationContext();
        usbManager = this.context.getSystemService(UsbManager.class);
    }

    void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            requestPermission(device);
        }

        listener = new LocalServerSocket(SOCKET_NAME);
        clients.execute(this::acceptLoop);
        Log.i(TAG, "USB/IP server listening on abstract socket @" + SOCKET_NAME);
    }

    List<String> exportedDevices() {
        List<String> devices = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (usbManager.hasPermission(device)) {
                devices.add(busId(device) + "=" + deviceLabel(device));
            }
        }
        return devices;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                LocalSocket socket = listener.accept();
                sockets.add(socket);
                clients.execute(() -> handleClient(socket));
            } catch (IOException exception) {
                if (running.get()) {
                    Log.w(TAG, "USB/IP accept failed", exception);
                }
            }
        }
    }

    private void handleClient(LocalSocket socket) {
        try (DataInputStream input = new DataInputStream(socket.getInputStream());
             DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            while (running.get()) {
                int version;
                try {
                    version = input.readUnsignedShort();
                } catch (EOFException eof) {
                    return;
                }
                int opcode = input.readUnsignedShort();
                input.readInt(); // request status is reserved
                if (version < 0x0106 || version > USBIP_VERSION) {
                    throw new IOException("unsupported USB/IP version 0x"
                            + Integer.toHexString(version));
                }
                if (opcode == OP_REQ_DEVLIST) {
                    writeDeviceList(output, version);
                } else if (opcode == OP_REQ_IMPORT) {
                    byte[] rawBusId = new byte[32];
                    input.readFully(rawBusId);
                    String requestedBusId = cString(rawBusId);
                    UsbDevice device = findDevice(requestedBusId);
                    if (device == null || !usbManager.hasPermission(device)) {
                        writeCommon(output, version, OP_REP_IMPORT, ST_NA);
                        continue;
                    }
                    UsbDeviceConnection connection = usbManager.openDevice(device);
                    if (connection == null) {
                        writeCommon(output, version, OP_REP_IMPORT, ST_NA);
                        continue;
                    }
                    DeviceSession session = new DeviceSession(
                            socket, input, output, device, connection);
                    if (!session.prepare()) {
                        connection.close();
                        writeCommon(output, version, OP_REP_IMPORT, ST_NA);
                        continue;
                    }
                    writeCommon(output, version, OP_REP_IMPORT, ST_OK);
                    writeDevice(output, device, connection, false);
                    output.flush();
                    session.run();
                    return;
                } else {
                    throw new IOException("unsupported USB/IP opcode 0x"
                            + Integer.toHexString(opcode));
                }
            }
        } catch (IOException exception) {
            if (running.get()) {
                Log.i(TAG, "USB/IP client ended: " + exception.getMessage());
            }
        } finally {
            sockets.remove(socket);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void writeDeviceList(DataOutputStream output, int version) throws IOException {
        List<UsbDevice> devices = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (usbManager.hasPermission(device)) {
                devices.add(device);
            }
        }
        writeCommon(output, version, OP_REP_DEVLIST, ST_OK);
        output.writeInt(devices.size());
        for (UsbDevice device : devices) {
            UsbDeviceConnection connection = usbManager.openDevice(device);
            try {
                writeDevice(output, device, connection, true);
            } finally {
                if (connection != null) {
                    connection.close();
                }
            }
        }
        output.flush();
    }

    private static void writeCommon(
            DataOutputStream output, int version, int opcode, int status) throws IOException {
        output.writeShort(version);
        output.writeShort(opcode);
        output.writeInt(status);
    }

    private static void writeDevice(
            DataOutputStream output,
            UsbDevice device,
            UsbDeviceConnection connection,
            boolean includeInterfaces) throws IOException {
        writeFixedString(output, device.getDeviceName(), 256);
        writeFixedString(output, busId(device), 32);
        output.writeInt(busNumber(device));
        output.writeInt(deviceNumber(device));
        output.writeInt(detectSpeed(device));
        output.writeShort(device.getVendorId());
        output.writeShort(device.getProductId());

        byte[] descriptors = connection == null ? null : connection.getRawDescriptors();
        int bcdDevice = descriptors != null && descriptors.length >= 14
                ? (descriptors[12] & 0xff) | ((descriptors[13] & 0xff) << 8) : 0;
        output.writeShort(bcdDevice);
        output.writeByte(device.getDeviceClass());
        output.writeByte(device.getDeviceSubclass());
        output.writeByte(device.getDeviceProtocol());
        output.writeByte(device.getConfigurationCount() == 0
                ? 0 : device.getConfiguration(0).getId());
        output.writeByte(device.getConfigurationCount());
        output.writeByte(device.getInterfaceCount());
        if (includeInterfaces) {
            for (int index = 0; index < device.getInterfaceCount(); index++) {
                UsbInterface usbInterface = device.getInterface(index);
                output.writeByte(usbInterface.getInterfaceClass());
                output.writeByte(usbInterface.getInterfaceSubclass());
                output.writeByte(usbInterface.getInterfaceProtocol());
                output.writeByte(0);
            }
        }
    }

    private final class DeviceSession implements AutoCloseable {
        private final LocalSocket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final UsbDevice device;
        private final UsbDeviceConnection connection;
        private final ExecutorService transfers = Executors.newFixedThreadPool(16);
        private final Map<Integer, Future<?>> active = new ConcurrentHashMap<>();
        private final Map<Integer, UsbEndpoint> endpoints = new ConcurrentHashMap<>();
        private final AtomicBoolean open = new AtomicBoolean(true);

        DeviceSession(
                LocalSocket socket,
                DataInputStream input,
                DataOutputStream output,
                UsbDevice device,
                UsbDeviceConnection connection) {
            this.socket = socket;
            this.input = input;
            this.output = output;
            this.device = device;
            this.connection = connection;
        }

        boolean prepare() {
            UsbConfiguration configuration = device.getConfigurationCount() == 0
                    ? null : device.getConfiguration(0);
            if (configuration != null) {
                connection.setConfiguration(configuration);
            }
            boolean claimedAny = false;
            for (int index = 0; index < device.getInterfaceCount(); index++) {
                UsbInterface usbInterface = device.getInterface(index);
                if (connection.claimInterface(usbInterface, true)) {
                    claimedAny = true;
                }
                for (int endpointIndex = 0;
                     endpointIndex < usbInterface.getEndpointCount();
                     endpointIndex++) {
                    UsbEndpoint endpoint = usbInterface.getEndpoint(endpointIndex);
                    endpoints.put(endpoint.getAddress(), endpoint);
                }
            }
            return claimedAny || device.getInterfaceCount() == 0;
        }

        void run() throws IOException {
            try {
                while (open.get()) {
                    byte[] headerBytes = new byte[48];
                    input.readFully(headerBytes);
                    ByteBuffer header = ByteBuffer.wrap(headerBytes)
                            .order(ByteOrder.BIG_ENDIAN);
                    int command = header.getInt();
                    int sequence = header.getInt();
                    int deviceId = header.getInt();
                    int direction = header.getInt();
                    int endpoint = header.getInt();
                    if (command == USBIP_CMD_SUBMIT) {
                        int transferFlags = header.getInt();
                        int transferLength = header.getInt();
                        int startFrame = header.getInt();
                        int packetCount = header.getInt();
                        int interval = header.getInt();
                        byte[] setup = new byte[8];
                        header.get(setup);
                        if (transferLength < 0 || transferLength > MAX_TRANSFER) {
                            throw new IOException("invalid USB/IP transfer length "
                                    + transferLength);
                        }
                        byte[] outData = direction == USBIP_DIR_OUT
                                ? readBytes(input, transferLength) : new byte[0];
                        if (packetCount != 0 && packetCount != -1) {
                            int descriptorBytes = Math.multiplyExact(packetCount, 16);
                            readBytes(input, descriptorBytes);
                            writeSubmitReply(
                                    sequence, deviceId, direction, endpoint,
                                    EIO, 0, new byte[0]);
                            continue;
                        }
                        Submit submit = new Submit(
                                sequence, deviceId, direction, endpoint,
                                transferFlags, transferLength, startFrame,
                                interval, setup, outData);
                        FutureTask<Void> task = new FutureTask<>(() -> {
                            execute(submit);
                            return null;
                        });
                        active.put(sequence, task);
                        transfers.execute(task);
                    } else if (command == USBIP_CMD_UNLINK) {
                        int unlinkSequence = header.getInt();
                        Future<?> future = active.remove(unlinkSequence);
                        if (future != null) {
                            future.cancel(true);
                        }
                        writeUnlinkReply(
                                sequence, deviceId, direction, endpoint,
                                future == null ? ECONNRESET : ST_OK);
                    } else {
                        throw new IOException("unsupported USB/IP command " + command);
                    }
                }
            } finally {
                close();
            }
        }

        private void execute(Submit submit) {
            int status = ST_OK;
            int actualLength = 0;
            byte[] response = new byte[0];
            try {
                if (submit.endpoint == 0) {
                    TransferResult result = executeControl(submit);
                    status = result.status;
                    actualLength = result.actualLength;
                    response = result.data;
                } else {
                    UsbEndpoint endpoint = endpoints.get(
                            submit.endpoint | (submit.direction == USBIP_DIR_IN ? 0x80 : 0));
                    if (endpoint == null) {
                        status = EIO;
                    } else {
                        byte[] buffer = submit.direction == USBIP_DIR_IN
                                ? new byte[submit.transferLength] : submit.outData;
                        int result;
                        do {
                            result = connection.bulkTransfer(
                                    endpoint, buffer, buffer.length, TRANSFER_TIMEOUT_MS);
                        } while (result < 0
                                && open.get()
                                && !Thread.currentThread().isInterrupted()
                                && submit.direction == USBIP_DIR_IN);
                        if (Thread.currentThread().isInterrupted()) {
                            return;
                        }
                        if (result < 0) {
                            status = EIO;
                        } else {
                            actualLength = result;
                            if (submit.direction == USBIP_DIR_IN) {
                                response = Arrays.copyOf(buffer, result);
                            }
                        }
                    }
                }
                if (active.remove(submit.sequence) == null
                        || Thread.currentThread().isInterrupted()) {
                    return;
                }
                writeSubmitReply(
                        submit.sequence, submit.deviceId, submit.direction,
                        submit.endpoint, status, actualLength, response);
            } catch (Throwable throwable) {
                active.remove(submit.sequence);
                if (open.get() && !Thread.currentThread().isInterrupted()) {
                    Log.w(TAG, "USB/IP transfer failed", throwable);
                    try {
                        writeSubmitReply(
                                submit.sequence, submit.deviceId, submit.direction,
                                submit.endpoint, EIO, 0, new byte[0]);
                    } catch (IOException ignored) {
                        close();
                    }
                }
            }
        }

        private TransferResult executeControl(Submit submit) {
            ByteBuffer setup = ByteBuffer.wrap(submit.setup).order(ByteOrder.LITTLE_ENDIAN);
            int requestType = setup.get() & 0xff;
            int request = setup.get() & 0xff;
            int value = setup.getShort() & 0xffff;
            int index = setup.getShort() & 0xffff;
            int length = setup.getShort() & 0xffff;
            int boundedLength = Math.min(length, submit.transferLength);

            if (requestType == 0 && request == 9) {
                return new TransferResult(
                        setConfiguration(value) ? ST_OK : EIO, 0, new byte[0]);
            }
            if (requestType == 1 && request == 11) {
                return new TransferResult(
                        setInterface(index, value) ? ST_OK : EIO, 0, new byte[0]);
            }

            boolean inputTransfer = (requestType & UsbConstants.USB_DIR_IN) != 0;
            byte[] data = inputTransfer
                    ? new byte[boundedLength]
                    : Arrays.copyOf(submit.outData, boundedLength);
            int result = connection.controlTransfer(
                    requestType, request, value, index, data, boundedLength,
                    TRANSFER_TIMEOUT_MS);
            if (result < 0) {
                return new TransferResult(EIO, 0, new byte[0]);
            }
            return new TransferResult(
                    ST_OK,
                    result,
                    inputTransfer ? Arrays.copyOf(data, result) : new byte[0]);
        }

        private boolean setConfiguration(int id) {
            for (int index = 0; index < device.getConfigurationCount(); index++) {
                UsbConfiguration configuration = device.getConfiguration(index);
                if (configuration.getId() == id) {
                    connection.setConfiguration(configuration);
                    return true;
                }
            }
            return false;
        }

        private boolean setInterface(int interfaceId, int alternateSetting) {
            for (int index = 0; index < device.getInterfaceCount(); index++) {
                UsbInterface usbInterface = device.getInterface(index);
                if (usbInterface.getId() == interfaceId
                        && usbInterface.getAlternateSetting() == alternateSetting) {
                    return connection.setInterface(usbInterface);
                }
            }
            return false;
        }

        private void writeSubmitReply(
                int sequence,
                int deviceId,
                int direction,
                int endpoint,
                int status,
                int actualLength,
                byte[] data) throws IOException {
            synchronized (output) {
                writeDeviceHeader(
                        output, USBIP_RET_SUBMIT, sequence, deviceId, direction, endpoint);
                output.writeInt(status);
                output.writeInt(actualLength);
                output.writeInt(0);
                output.writeInt(0);
                output.writeInt(0);
                output.writeLong(0);
                if (direction == USBIP_DIR_IN && actualLength > 0) {
                    output.write(data, 0, actualLength);
                }
                output.flush();
            }
        }

        private void writeUnlinkReply(
                int sequence,
                int deviceId,
                int direction,
                int endpoint,
                int status) throws IOException {
            synchronized (output) {
                writeDeviceHeader(
                        output, USBIP_RET_UNLINK, sequence, deviceId, direction, endpoint);
                output.writeInt(status);
                output.write(new byte[24]);
                output.flush();
            }
        }

        @Override
        public void close() {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            for (Future<?> future : active.values()) {
                future.cancel(true);
            }
            active.clear();
            transfers.shutdownNow();
            for (int index = 0; index < device.getInterfaceCount(); index++) {
                connection.releaseInterface(device.getInterface(index));
            }
            connection.close();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void writeDeviceHeader(
            DataOutputStream output,
            int command,
            int sequence,
            int deviceId,
            int direction,
            int endpoint) throws IOException {
        output.writeInt(command);
        output.writeInt(sequence);
        output.writeInt(deviceId);
        output.writeInt(direction);
        output.writeInt(endpoint);
    }

    private void requestPermission(UsbDevice device) {
        if (device == null || usbManager.hasPermission(device)) {
            return;
        }
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                context, device.getDeviceId(), intent, flags);
        usbManager.requestPermission(device, permissionIntent);
    }

    private UsbDevice findDevice(String requestedBusId) {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (busId(device).equals(requestedBusId)) {
                return device;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice usbDeviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private static int busNumber(UsbDevice device) {
        return device.getDeviceId() / 1000;
    }

    private static int deviceNumber(UsbDevice device) {
        return device.getDeviceId() % 1000;
    }

    private static String busId(UsbDevice device) {
        return busNumber(device) + "-" + deviceNumber(device);
    }

    private static String deviceLabel(UsbDevice device) {
        if (device == null) {
            return "unknown";
        }
        String product = device.getProductName();
        return (product == null ? "USB device" : product)
                + " [" + String.format("%04x:%04x",
                device.getVendorId(), device.getProductId()) + "]"
                + " busid=" + busId(device);
    }

    private static int detectSpeed(UsbDevice device) {
        int speed = 2; // full speed
        for (int interfaceIndex = 0;
             interfaceIndex < device.getInterfaceCount();
             interfaceIndex++) {
            UsbInterface usbInterface = device.getInterface(interfaceIndex);
            for (int endpointIndex = 0;
                 endpointIndex < usbInterface.getEndpointCount();
                 endpointIndex++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(endpointIndex);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue;
                }
                if (endpoint.getMaxPacketSize() >= 1024) {
                    return 5; // super speed
                }
                if (endpoint.getMaxPacketSize() >= 512) {
                    speed = 3; // high speed
                }
            }
        }
        return speed;
    }

    private static String cString(byte[] value) {
        int length = 0;
        while (length < value.length && value[length] != 0) {
            length++;
        }
        return new String(value, 0, length, StandardCharsets.US_ASCII);
    }

    private static byte[] readBytes(DataInputStream input, int length) throws IOException {
        byte[] value = new byte[length];
        input.readFully(value);
        return value;
    }

    private static void writeFixedString(
            DataOutputStream output, String value, int length) throws IOException {
        byte[] encoded = value == null
                ? new byte[0] : value.getBytes(StandardCharsets.US_ASCII);
        int count = Math.min(encoded.length, length - 1);
        output.write(encoded, 0, count);
        output.write(new byte[length - count]);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        LocalServerSocket currentListener = listener;
        listener = null;
        if (currentListener != null) {
            try {
                currentListener.close();
            } catch (IOException ignored) {
            }
        }
        for (LocalSocket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        sockets.clear();
        clients.shutdownNow();
    }

    private static final class Submit {
        final int sequence;
        final int deviceId;
        final int direction;
        final int endpoint;
        final int transferFlags;
        final int transferLength;
        final int startFrame;
        final int interval;
        final byte[] setup;
        final byte[] outData;

        Submit(
                int sequence,
                int deviceId,
                int direction,
                int endpoint,
                int transferFlags,
                int transferLength,
                int startFrame,
                int interval,
                byte[] setup,
                byte[] outData) {
            this.sequence = sequence;
            this.deviceId = deviceId;
            this.direction = direction;
            this.endpoint = endpoint;
            this.transferFlags = transferFlags;
            this.transferLength = transferLength;
            this.startFrame = startFrame;
            this.interval = interval;
            this.setup = setup;
            this.outData = outData;
        }
    }

    private static final class TransferResult {
        final int status;
        final int actualLength;
        final byte[] data;

        TransferResult(int status, int actualLength, byte[] data) {
            this.status = status;
            this.actualLength = actualLength;
            this.data = data;
        }
    }
}
