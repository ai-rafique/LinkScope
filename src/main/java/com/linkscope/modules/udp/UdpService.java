package com.linkscope.modules.udp;

import com.linkscope.core.AbstractService;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleStatus;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;

/**
 * UDP: bound listener (receive), fire-and-forget send, optional broadcast, and a
 * "reply to last sender" helper. Sending works without listening — a temporary
 * socket is used, with a one-time hint that replies won't be captured.
 */
public final class UdpService extends AbstractService {
    public static final String TAG = "UDP";
    private static final int MAX_DATAGRAM = 65_535;

    private volatile String bindAddress = "0.0.0.0";
    private volatile int bindPort = 5000;
    private volatile boolean broadcast;
    private volatile String targetHost = "127.0.0.1";
    private volatile int targetPort = 5000;

    private volatile DatagramSocket socket;
    private volatile boolean running;
    private volatile int localPort = -1;
    private volatile InetSocketAddress lastSender;
    private volatile boolean hintedTransientSend;
    private final ReadOnlyObjectWrapper<InetSocketAddress> lastSenderProperty = new ReadOnlyObjectWrapper<>();

    @Override
    public String moduleName() {
        return TAG;
    }

    // --- configuration (set before start / send) ----------------------------------

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress == null || bindAddress.isBlank() ? "0.0.0.0" : bindAddress.trim();
    }

    public void setBindPort(int bindPort) {
        this.bindPort = requirePort(bindPort);
    }

    public void setBroadcast(boolean broadcast) {
        this.broadcast = broadcast;
    }

    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost == null || targetHost.isBlank() ? "127.0.0.1" : targetHost.trim();
    }

    public void setTargetPort(int targetPort) {
        this.targetPort = requirePort(targetPort);
    }

    /** Port actually bound (useful when bindPort is 0), or -1 when not listening. */
    public int localPort() {
        return localPort;
    }

    public ReadOnlyObjectProperty<InetSocketAddress> lastSenderProperty() {
        return lastSenderProperty.getReadOnlyProperty();
    }

    public InetSocketAddress lastSender() {
        return lastSender;
    }

    // --- lifecycle ------------------------------------------------------------------

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        setStatus(ModuleStatus.CONNECTING);
        exec.submit(this::receiveLoop);
    }

    @Override
    public void stop() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) {
            s.close();
        }
    }

    /** Sends to the configured target host:port. */
    @Override
    public void send(byte[] payload) {
        String host = targetHost;
        int port = targetPort;
        exec.submit(() -> sendTo(new InetSocketAddress(host, port), payload));
    }

    /** Sends to whoever sent the most recent datagram. */
    public void replyToLastSender(byte[] payload) {
        InetSocketAddress target = lastSender;
        if (target == null) {
            logError("No datagram received yet — nothing to reply to");
            return;
        }
        exec.submit(() -> sendTo(target, payload));
    }

    private void receiveLoop() {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(broadcast);
            s.bind(new InetSocketAddress(bindAddress, bindPort));
            socket = s;
            localPort = s.getLocalPort();
            logInfo("Listening on " + bindAddress + ":" + localPort + (broadcast ? " (broadcast enabled)" : ""));
            setStatus(ModuleStatus.CONNECTED);

            byte[] buf = new byte[MAX_DATAGRAM];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                s.receive(packet);
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
                lastSender = from;
                FxThread.run(() -> lastSenderProperty.set(from));
                log.rx(TAG, data, describe(from));
            }
        } catch (IOException e) {
            if (running) {
                logError(socket == null ? "Could not bind " + bindAddress + ":" + bindPort : "Receive error", e);
                setStatus(ModuleStatus.ERROR);
            }
        } finally {
            running = false;
            socket = null;
            localPort = -1;
            if (s != null) {
                s.close();
            }
            if (status() != ModuleStatus.ERROR) {
                logInfo("Stopped listening");
                setStatus(ModuleStatus.DISCONNECTED);
            }
        }
    }

    private void sendTo(InetSocketAddress target, byte[] payload) {
        if (target.isUnresolved()) {
            logError("Cannot resolve host " + target.getHostString());
            return;
        }
        DatagramSocket s = socket;
        boolean temporary = s == null;
        try {
            if (temporary) {
                s = new DatagramSocket();
                s.setBroadcast(broadcast);
                if (!hintedTransientSend) {
                    hintedTransientSend = true;
                    logInfo("Not listening — sending from a temporary port " + s.getLocalPort()
                            + "; replies will not be captured. Start listening to receive.");
                }
            }
            s.send(new DatagramPacket(payload, payload.length, target));
            log.tx(TAG, payload, describe(target));
        } catch (SocketException e) {
            logError("Send to " + describe(target) + " failed", e);
        } catch (IOException e) {
            logError("Send to " + describe(target) + " failed", e);
        } finally {
            if (temporary && s != null) {
                s.close();
            }
        }
    }

    static String describe(InetSocketAddress a) {
        return a.getAddress() == null ? a.getHostString() + ":" + a.getPort()
                : a.getAddress().getHostAddress() + ":" + a.getPort();
    }
}
