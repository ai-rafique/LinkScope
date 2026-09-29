package com.linkscope.modules.netscan;

import com.linkscope.core.AbstractService;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleStatus;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sweeps a /24 (base.0 to base.255, or a sub-range) to find which addresses answer.
 * Each host gets an ICMP/echo reachability check plus, optionally, a parallel TCP
 * touch on a few common ports so hosts that drop ping still show up as UP. After the
 * sweep the OS neighbour table is read: a host that answered ARP is present even if it
 * ignored everything else, and every present host gets its MAC address.
 */
public final class NetScanService extends AbstractService {
    public static final String TAG = "SCAN";
    /** Common service ports touched in parallel to detect ping-blocking hosts. */
    public static final List<Integer> PROBE_PORTS = List.of(80, 443, 22, 445, 135, 3389, 8080);

    public enum HostState {
        UP("Up"), DOWN("Down");

        public final String label;

        HostState(String label) {
            this.label = label;
        }
    }

    /**
     * One swept address. {@code via} says what proved it up: "ping", "tcp", "ping + tcp" or
     * "arp" (neighbour table only); {@code role} is "this PC", "gateway" or "".
     */
    public record HostResult(String ip, int lastOctet, HostState state, long rttMs, String hostname,
                             List<Integer> openPorts, String via, String mac, String role) {
        public HostResult(String ip, int lastOctet, HostState state, long rttMs, String hostname, List<Integer> openPorts, String via) {
            this(ip, lastOctet, state, rttMs, hostname, openPorts, via, "", "");
        }

        public HostResult withMac(String mac, HostState state, String via) {
            return new HostResult(ip, lastOctet, state, rttMs, hostname, openPorts, via, mac, role);
        }
    }

    private final ObservableList<HostResult> results = FXCollections.observableArrayList();
    private final ReadOnlyIntegerWrapper progress = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyIntegerWrapper total = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyIntegerWrapper upCount = new ReadOnlyIntegerWrapper(0);

    private volatile String base = "192.168.1";
    private volatile int from;
    private volatile int to = 255;
    private volatile int timeoutMs = 500;
    private volatile boolean probePorts = true;
    private volatile boolean resolveNames;
    private volatile boolean readArp = true;
    private volatile String selfIp = "";
    private volatile String gatewayIp = "";
    private volatile int parallelism = 64;
    private volatile boolean running;

    @Override
    public String moduleName() {
        return TAG;
    }

    public ObservableList<HostResult> results() {
        return results;
    }

    public ReadOnlyIntegerProperty progressProperty() {
        return progress.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty totalProperty() {
        return total.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty upCountProperty() {
        return upCount.getReadOnlyProperty();
    }

    public boolean isRunning() {
        return running;
    }

    public String base() {
        return base;
    }

    // --- configuration --------------------------------------------------------------

    /** Accepts "192.168.1", "192.168.1.x", "192.168.1.0/24" or any address in the subnet. */
    public void setBase(String input) {
        this.base = normalizeBase(input);
    }

    public void setRange(int from, int to) {
        if (from < 0 || to > 255 || from > to) {
            throw new IllegalArgumentException("Range must be within 0-255 and from <= to");
        }
        this.from = from;
        this.to = to;
    }

    public void setTimeoutMs(int timeoutMs) {
        if (timeoutMs < 50 || timeoutMs > 60_000) {
            throw new IllegalArgumentException("Timeout must be 50-60000 ms");
        }
        this.timeoutMs = timeoutMs;
    }

    public void setProbePorts(boolean probePorts) {
        this.probePorts = probePorts;
    }

    public void setResolveNames(boolean resolveNames) {
        this.resolveNames = resolveNames;
    }

    /** Read the neighbour table after the sweep to add MACs and catch ARP-only hosts (default on). */
    public void setReadArp(boolean readArp) {
        this.readArp = readArp;
    }

    /** Our own address on the scanned network, tagged "this PC" in the results. */
    public void setSelfIp(String selfIp) {
        this.selfIp = selfIp == null ? "" : selfIp;
    }

    /** The default gateway on the scanned network, tagged "gateway" in the results. */
    public void setGatewayIp(String gatewayIp) {
        this.gatewayIp = gatewayIp == null ? "" : gatewayIp;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = Math.max(1, Math.min(256, parallelism));
    }

    static String normalizeBase(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Network base is required, e.g. 192.168.1.x");
        }
        String s = input.trim();
        int slash = s.indexOf('/');
        if (slash >= 0) {
            String prefix = s.substring(slash + 1);
            if (!"24".equals(prefix)) {
                throw new IllegalArgumentException("Only /24 networks are supported (got /" + prefix + ")");
            }
            s = s.substring(0, slash);
        }
        String[] parts = s.split("\\.");
        if (parts.length < 3 || parts.length > 4) {
            throw new IllegalArgumentException("Expected an IPv4 base like 192.168.1.x");
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            int octet;
            try {
                octet = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Octet \"" + parts[i] + "\" is not a number");
            }
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException("Octet " + octet + " is out of range");
            }
            if (i > 0) {
                out.append('.');
            }
            out.append(octet);
        }
        return out.toString();
    }

    // --- lifecycle ------------------------------------------------------------------

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        int count = to - from + 1;
        FxThread.run(() -> {
            results.clear();
            progress.set(0);
            total.set(count);
            upCount.set(0);
        });
        logInfo("Scanning " + base + "." + from + " – " + base + "." + to + " (timeout " + timeoutMs + " ms"
                + (probePorts ? ", ping + tcp " + PROBE_PORTS : ", ping only") + (readArp ? ", then ARP table" : "") + ")");
        setStatus(ModuleStatus.CONNECTING);
        exec.submit(this::run);
    }

    @Override
    public void stop() {
        if (running) {
            running = false;
            logInfo("Scan stopped");
        }
        if (status() != ModuleStatus.DISCONNECTED) {
            setStatus(ModuleStatus.DISCONNECTED);
        }
    }

    private void run() {
        Semaphore slots = new Semaphore(parallelism);
        int count = to - from + 1;
        CountDownLatch done = new CountDownLatch(count);
        AtomicInteger up = new AtomicInteger();
        long started = System.currentTimeMillis();
        for (int octet = from; octet <= to; octet++) {
            final int o = octet;
            exec.submit(() -> {
                try {
                    slots.acquire();
                    try {
                        if (running) {
                            HostResult r = probe(o);
                            if (r.state() == HostState.UP) {
                                up.incrementAndGet();
                            }
                            FxThread.run(() -> {
                                results.add(r);
                                progress.set(progress.get() + 1);
                                if (r.state() == HostState.UP) {
                                    upCount.set(upCount.get() + 1);
                                }
                            });
                        }
                    } finally {
                        slots.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (running && readArp) {
            up.set(enrichFromArp());
        }
        if (running) {
            running = false;
            logInfo("Scan complete: " + up.get() + " of " + count + " addresses up in "
                    + (System.currentTimeMillis() - started) + " ms");
            setStatus(ModuleStatus.CONNECTED);
        }
    }

    /** Adds MACs from the neighbour table and promotes ARP-only hosts to UP. Returns the new up count. */
    private int enrichFromArp() {
        Map<String, String> arp = ArpTable.read();
        List<HostResult> current = FxThread.read(() -> new ArrayList<>(results));
        if (arp.isEmpty()) {
            return (int) current.stream().filter(r -> r.state() == HostState.UP).count();
        }
        List<HostResult> updated = new ArrayList<>(current.size());
        int promoted = 0;
        int upNow = 0;
        for (HostResult r : current) {
            String mac = arp.get(r.ip());
            HostResult n = r;
            if (mac != null) {
                boolean wasDown = r.state() == HostState.DOWN;
                n = r.withMac(mac, HostState.UP, wasDown ? "arp" : r.via());
                if (wasDown) {
                    promoted++;
                    logInfo(r.ip() + " answers ARP only (" + mac + "): present but silent to ping and probes");
                }
            }
            if (n.state() == HostState.UP) {
                upNow++;
            }
            updated.add(n);
        }
        int finalUp = upNow;
        FxThread.run(() -> {
            results.setAll(updated);
            upCount.set(finalUp);
        });
        if (promoted > 0) {
            logInfo(promoted + " host(s) found through the ARP table only");
        }
        return upNow;
    }

    private HostResult probe(int octet) {
        String ip = base + "." + octet;
        String role = ip.equals(selfIp) ? "this PC" : ip.equals(gatewayIp) ? "gateway" : "";
        InetAddress addr;
        try {
            addr = InetAddress.getByName(ip);
        } catch (IOException e) {
            return new HostResult(ip, octet, HostState.DOWN, -1, "", List.of(), "", "", role);
        }
        List<Integer> open = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch portsDone = new CountDownLatch(probePorts ? PROBE_PORTS.size() : 0);
        if (probePorts) {
            for (int port : PROBE_PORTS) {
                exec.submit(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(addr, port), timeoutMs);
                        open.add(port);
                    } catch (IOException ignored) {
                        // closed, filtered or unreachable
                    } finally {
                        portsDone.countDown();
                    }
                });
            }
        }
        long t0 = System.nanoTime();
        boolean pinged = false;
        try {
            pinged = addr.isReachable(timeoutMs);
        } catch (IOException ignored) {
            // treated as not reachable
        }
        long rtt = (System.nanoTime() - t0) / 1_000_000;
        try {
            portsDone.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Integer> ports = new ArrayList<>(open);
        Collections.sort(ports);
        boolean isUp = pinged || !ports.isEmpty();
        String via = pinged && !ports.isEmpty() ? "ping + tcp" : pinged ? "ping" : !ports.isEmpty() ? "tcp" : "";
        String hostname = "";
        if (isUp && resolveNames) {
            String name = addr.getCanonicalHostName();
            hostname = name.equals(ip) ? "" : name;
        }
        if (isUp) {
            logInfo(ip + " up via " + via + (pinged ? " (" + rtt + " ms)" : "")
                    + (ports.isEmpty() ? "" : ", tcp " + ports) + (hostname.isEmpty() ? "" : " " + hostname)
                    + (role.isEmpty() ? "" : " [" + role + "]"));
        }
        return new HostResult(ip, octet, isUp ? HostState.UP : HostState.DOWN, pinged ? rtt : -1, hostname, ports, via, "", role);
    }

    /** Not applicable: a scan has nothing to send. */
    @Override
    public void send(byte[] payload) {
        logError("Net Scan has nothing to send to");
    }
}
