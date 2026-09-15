package com.linkscope.modules.portscan;

import com.linkscope.core.AbstractService;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleStatus;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local TCP port survey on one interface address. Every port gets two checks: a connect
 * (is something listening?) and a bind (does something hold the port?), which yields
 * LISTENING, IN USE (held but no listener, e.g. an established connection), RESERVED
 * (the OS refuses the bind, such as Windows excluded port ranges or privileged ports
 * without root) or FREE. Listening ports are enriched with the owning process when the
 * platform's netstat/ss/lsof is available.
 */
public final class PortScanService extends AbstractService {
    public static final String TAG = "PORTS";

    public enum PortState {
        LISTENING("Listening"), IN_USE("In use"), RESERVED("Reserved"), FREE("Free"), UNKNOWN("Unknown");

        public final String label;

        PortState(String label) {
            this.label = label;
        }
    }

    public record PortResult(int port, PortState state, String owner, String detail) {
    }

    /** One selectable local address. */
    public record LocalAddress(String label, String ip) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final ObservableList<PortResult> results = FXCollections.observableArrayList();
    private final ReadOnlyIntegerWrapper progress = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyIntegerWrapper total = new ReadOnlyIntegerWrapper(0);

    private volatile String address = "127.0.0.1";
    private volatile String ranges = "1-1024";
    private volatile int connectTimeoutMs = 300;
    private volatile int parallelism = 128;
    private volatile boolean running;

    @Override
    public String moduleName() {
        return TAG;
    }

    public ObservableList<PortResult> results() {
        return results;
    }

    public ReadOnlyIntegerProperty progressProperty() {
        return progress.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty totalProperty() {
        return total.getReadOnlyProperty();
    }

    public boolean isRunning() {
        return running;
    }

    // --- configuration --------------------------------------------------------------

    public void setAddress(String address) {
        this.address = address == null || address.isBlank() ? "127.0.0.1" : address.trim();
    }

    /** "1-1024", "22,80,443", "8000-9000,3306" and combinations. */
    public void setRanges(String ranges) {
        parseRanges(ranges);
        this.ranges = ranges;
    }

    public void setConnectTimeoutMs(int ms) {
        this.connectTimeoutMs = Math.max(50, ms);
    }

    public void setParallelism(int parallelism) {
        this.parallelism = Math.max(1, Math.min(512, parallelism));
    }

    static List<int[]> parseRanges(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Port range is required, e.g. 1-1024");
        }
        List<int[]> out = new ArrayList<>();
        for (String part : text.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int dash = p.indexOf('-');
            int lo;
            int hi;
            try {
                lo = Integer.parseInt(dash < 0 ? p : p.substring(0, dash).trim());
                hi = dash < 0 ? lo : Integer.parseInt(p.substring(dash + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad port range \"" + p + "\"");
            }
            if (lo < 1 || hi > 65535 || lo > hi) {
                throw new IllegalArgumentException("Port range \"" + p + "\" must be within 1-65535, low-high");
            }
            out.add(new int[] {lo, hi});
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Port range is required, e.g. 1-1024");
        }
        return out;
    }

    static int countPorts(List<int[]> ranges) {
        int n = 0;
        for (int[] r : ranges) {
            n += r[1] - r[0] + 1;
        }
        return n;
    }

    /** Loopback first, then every IPv4 address of an interface that is up. Never throws. */
    public static List<LocalAddress> localAddresses() {
        List<LocalAddress> out = new ArrayList<>();
        out.add(new LocalAddress("Loopback (127.0.0.1)", "127.0.0.1"));
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!nif.isUp() || nif.isLoopback()) {
                        continue;
                    }
                } catch (SocketException e) {
                    continue;
                }
                for (InterfaceAddress a : nif.getInterfaceAddresses()) {
                    if (a.getAddress() instanceof Inet4Address ip) {
                        out.add(new LocalAddress(nif.getDisplayName() + " (" + ip.getHostAddress() + ")", ip.getHostAddress()));
                    }
                }
            }
        } catch (SocketException ignored) {
            // loopback only
        }
        return out;
    }

    // --- lifecycle ------------------------------------------------------------------

    @Override
    public void start() {
        if (running) {
            return;
        }
        List<int[]> parsed;
        InetAddress addr;
        try {
            parsed = parseRanges(ranges);
            addr = InetAddress.getByName(address);
        } catch (IllegalArgumentException | IOException e) {
            logError(e.getMessage());
            setStatus(ModuleStatus.ERROR);
            return;
        }
        running = true;
        int count = countPorts(parsed);
        FxThread.run(() -> {
            results.clear();
            progress.set(0);
            total.set(count);
        });
        logInfo("Scanning " + count + " TCP port(s) " + ranges + " on " + address);
        setStatus(ModuleStatus.CONNECTING);
        exec.submit(() -> run(addr, parsed, count));
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

    private void run(InetAddress addr, List<int[]> parsed, int count) {
        PortOwners.Snapshot snapshot = PortOwners.snapshot();
        if (snapshot.isEmpty()) {
            logInfo("Socket table unavailable (netstat/ss/lsof): no process names, and ports held by "
                    + "established connections will read as free");
        } else {
            logInfo("Socket table: " + snapshot.listeningOwners().size() + " listening, "
                    + snapshot.activePorts().size() + " other local port(s) in use");
        }
        Semaphore slots = new Semaphore(parallelism);
        CountDownLatch done = new CountDownLatch(count);
        AtomicInteger listening = new AtomicInteger();
        AtomicInteger inUse = new AtomicInteger();
        AtomicInteger reserved = new AtomicInteger();
        long started = System.currentTimeMillis();
        for (int[] r : parsed) {
            for (int port = r[0]; port <= r[1]; port++) {
                final int p = port;
                exec.submit(() -> {
                    try {
                        slots.acquire();
                        try {
                            if (running) {
                                PortResult res = probe(addr, p, snapshot);
                                switch (res.state()) {
                                    case LISTENING -> listening.incrementAndGet();
                                    case IN_USE -> inUse.incrementAndGet();
                                    case RESERVED -> reserved.incrementAndGet();
                                    default -> { }
                                }
                                FxThread.run(() -> {
                                    results.add(res);
                                    progress.set(progress.get() + 1);
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
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (running) {
            running = false;
            logInfo("Scan complete in " + (System.currentTimeMillis() - started) + " ms: " + listening.get()
                    + " listening, " + inUse.get() + " in use, " + reserved.get() + " reserved, "
                    + (count - listening.get() - inUse.get() - reserved.get()) + " free");
            setStatus(ModuleStatus.CONNECTED);
        }
    }

    PortResult probe(InetAddress addr, int port, PortOwners.Snapshot snapshot) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(addr, port), connectTimeoutMs);
            String owner = snapshot.listeningOwners().getOrDefault(port, "");
            logInfo("Port " + port + " listening" + (owner.isEmpty() ? "" : " (" + owner + ")"));
            return new PortResult(port, PortState.LISTENING, owner, "accepts connections");
        } catch (ConnectException refused) {
            // nothing listening; fall through to the bind test
        } catch (SocketTimeoutException timeout) {
            return new PortResult(port, PortState.UNKNOWN, "", "connect timed out (filtered?)");
        } catch (IOException e) {
            return new PortResult(port, PortState.UNKNOWN, "", e.getMessage() == null ? "connect error" : e.getMessage());
        }
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(false);
            ss.bind(new InetSocketAddress(addr, port));
            if (snapshot.activePorts().contains(port)) {
                // Windows lets a listener bind next to an established connection's local port, so ask the socket table.
                logInfo("Port " + port + " in use (established or closing connection)");
                return new PortResult(port, PortState.IN_USE, "", "held by a non-listening socket (per socket table)");
            }
            return new PortResult(port, PortState.FREE, "", "");
        } catch (BindException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("permission") || msg.contains("forbidden") || msg.contains("access")) {
                logInfo("Port " + port + " reserved by the OS (bind refused)");
                return new PortResult(port, PortState.RESERVED, "", "bind refused: " + e.getMessage());
            }
            logInfo("Port " + port + " in use (bound, not listening)");
            return new PortResult(port, PortState.IN_USE, "", "bound by another socket");
        } catch (IOException e) {
            return new PortResult(port, PortState.UNKNOWN, "", e.getMessage() == null ? "bind error" : e.getMessage());
        }
    }

    /** Not applicable: a scan has nothing to send. */
    @Override
    public void send(byte[] payload) {
        logError("Ports has nothing to send to");
    }
}
