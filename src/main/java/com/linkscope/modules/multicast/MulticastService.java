package com.linkscope.modules.multicast;

import com.linkscope.core.AbstractService;
import com.linkscope.core.ModuleStatus;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.util.Arrays;
import java.util.List;

/**
 * Multicast: join a group on a chosen interface, send/receive, TTL and loopback control.
 * Sending works before joining (temporary socket), receiving requires a join.
 */
public final class MulticastService extends AbstractService {
    public static final String TAG = "MCAST";
    private static final int MAX_DATAGRAM = 65_535;

    private volatile String group = "239.1.1.1";
    private volatile int port = 5000;
    private volatile String interfaceName;
    private volatile int ttl = 1;
    private volatile boolean loopback = true;

    private volatile MulticastSocket socket;
    private volatile boolean running;
    private volatile int localPort = -1;

    @Override
    public String moduleName() {
        return TAG;
    }

    /** Port actually bound (useful when port is 0), or -1 when not joined. */
    public int localPort() {
        return localPort;
    }

    public void setGroup(String group) {
        this.group = group == null || group.isBlank() ? "239.1.1.1" : group.trim();
    }

    public void setPort(int port) {
        this.port = requirePort(port);
    }

    /** {@link NetworkInterface#getName()} to join on, or null/blank for automatic. */
    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName == null || interfaceName.isBlank() ? null : interfaceName.trim();
    }

    public void setTtl(int ttl) {
        if (ttl < 0 || ttl > 255) {
            throw new IllegalArgumentException("TTL must be 0-255, got " + ttl);
        }
        this.ttl = ttl;
    }

    public void setLoopback(boolean loopback) {
        this.loopback = loopback;
    }

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
        MulticastSocket s = socket;
        if (s != null) {
            s.close();
        }
    }

    @Override
    public void send(byte[] payload) {
        String g = group;
        int p = port;
        exec.submit(() -> doSend(g, p, payload));
    }

    private void receiveLoop() {
        MulticastSocket ms = null;
        InetSocketAddress groupAddress = null;
        NetworkInterface nif = null;
        try {
            InetAddress g = InetAddress.getByName(group);
            if (!g.isMulticastAddress()) {
                throw new IOException(group + " is not a multicast address (224.0.0.0 – 239.255.255.255)");
            }
            nif = resolveInterface(interfaceName);
            groupAddress = new InetSocketAddress(g, port);

            ms = new MulticastSocket(null);
            ms.setReuseAddress(true);
            ms.bind(new InetSocketAddress(port));
            ms.setOption(StandardSocketOptions.IP_MULTICAST_TTL, ttl);
            ms.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, loopback);
            if (nif != null) {
                ms.setNetworkInterface(nif);
            }
            ms.joinGroup(groupAddress, nif);
            socket = ms;
            localPort = ms.getLocalPort();
            logInfo("Joined " + group + ":" + localPort + " on " + (nif == null ? "default interface" : describe(nif))
                    + ", TTL " + ttl + ", loopback " + (loopback ? "on" : "off"));
            setStatus(ModuleStatus.CONNECTED);

            byte[] buf = new byte[MAX_DATAGRAM];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                ms.receive(packet);
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
                log.rx(TAG, data, from.getAddress().getHostAddress() + ":" + from.getPort());
            }
        } catch (IOException e) {
            if (running) {
                String where = nif == null ? "default interface" : describe(nif);
                logError(socket == null ? "Could not join " + group + ":" + port + " on " + where : "Receive error", e);
                if (socket == null && e.getMessage() != null && e.getMessage().toLowerCase().contains("forbidden")) {
                    logError("Port " + port + " is likely inside a Windows reserved range; check with "
                            + "'netsh interface ipv4 show excludedportrange protocol=udp'");
                }
                setStatus(ModuleStatus.ERROR);
            }
        } finally {
            running = false;
            socket = null;
            localPort = -1;
            if (ms != null) {
                if (groupAddress != null && !ms.isClosed()) {
                    try {
                        ms.leaveGroup(groupAddress, nif);
                    } catch (IOException ignored) {
                        // leaving on close
                    }
                }
                ms.close();
            }
            if (status() != ModuleStatus.ERROR) {
                logInfo("Left " + group + ":" + port);
                setStatus(ModuleStatus.DISCONNECTED);
            }
        }
    }

    private void doSend(String g, int p, byte[] payload) {
        MulticastSocket s = socket;
        boolean temporary = s == null;
        try {
            InetAddress addr = InetAddress.getByName(g);
            if (temporary) {
                s = new MulticastSocket();
                s.setOption(StandardSocketOptions.IP_MULTICAST_TTL, ttl);
                s.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, loopback);
                NetworkInterface nif = resolveInterface(interfaceName);
                if (nif != null) {
                    s.setNetworkInterface(nif);
                }
            }
            s.send(new DatagramPacket(payload, payload.length, addr, p));
            log.tx(TAG, payload, g + ":" + p);
        } catch (IOException e) {
            logError("Send to " + g + ":" + p + " failed", e);
        } finally {
            if (temporary && s != null) {
                s.close();
            }
        }
    }

    // --- interfaces -----------------------------------------------------------------

    /** Interfaces that are up and multicast-capable: physical adapters first, then virtual, then loopback. */
    public static List<NetworkInterface> candidateInterfaces() {
        return com.linkscope.core.NetInterfaces.candidates();
    }

    /** Human-readable label: display name plus first IPv4 address, tagged when virtual. */
    public static String describe(NetworkInterface nif) {
        return com.linkscope.core.NetInterfaces.describe(nif);
    }

    static NetworkInterface resolveInterface(String name) throws SocketException {
        if (name != null) {
            NetworkInterface byName = NetworkInterface.getByName(name);
            if (byName == null) {
                throw new SocketException("No network interface named \"" + name + "\"");
            }
            return byName;
        }
        List<NetworkInterface> candidates = candidateInterfaces();
        return candidates.isEmpty() ? null : candidates.get(0);
    }
}
