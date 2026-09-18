package com.linkscope.modules.capture;

import com.linkscope.core.AbstractService;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.NetInterfaces;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.pcap4j.core.BpfProgram;
import org.pcap4j.core.NotOpenException;
import org.pcap4j.core.PcapAddress;
import org.pcap4j.core.PcapDumper;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNativeException;
import org.pcap4j.core.PcapNetworkInterface;
import org.pcap4j.core.PcapStat;
import org.pcap4j.core.Pcaps;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.UnknownPacket;
import org.pcap4j.packet.namednumber.DataLinkType;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;

/**
 * Live packet capture on one interface through pcap4j (Npcap on Windows, libpcap on Linux).
 * Packets are dissected on the capture thread and flushed to the table in batches so a
 * busy link does not stall the UI. Also opens and writes .pcap files.
 */
public final class CaptureService extends AbstractService {
    public static final String TAG = "CAPTURE";
    private static final int SNAPLEN = 65_536;
    private static final int READ_TIMEOUT_MS = 100;
    private static final long FLUSH_INTERVAL_MS = 100;

    static {
        // Npcap installs its DLLs outside the default search path unless "WinPcap API-compatible
        // mode" was chosen; point pcap4j at them explicitly when present.
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            Path npcap = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "Npcap");
            if (Files.isRegularFile(npcap.resolve("wpcap.dll"))) {
                System.setProperty("org.pcap4j.core.pcapLibName",
                        System.getProperty("org.pcap4j.core.pcapLibName", npcap.resolve("wpcap.dll").toString()));
                System.setProperty("org.pcap4j.core.packetLibName",
                        System.getProperty("org.pcap4j.core.packetLibName", npcap.resolve("Packet.dll").toString()));
            }
        }
    }

    /** A capture-capable interface as reported by the driver. */
    public record Device(String name, String description, List<String> addresses, boolean loopback, boolean virtual) {
        public String label() {
            String desc = description == null || description.isBlank() ? name : description;
            return desc + (addresses.isEmpty() ? "" : " (" + String.join(", ", addresses) + ")")
                    + (virtual && !loopback ? " [virtual]" : "");
        }

        /** True when an address is a routable IPv4 (not link-local 169.254.x, not loopback). */
        public boolean hasRoutableIpv4() {
            return addresses.stream().anyMatch(a -> a.indexOf('.') > 0 && !a.startsWith("169.254.") && !a.startsWith("127."));
        }

        /** Sort key: physical adapters with a routable IPv4 first, loopback last. */
        int rank() {
            if (loopback) {
                return 5;
            }
            int r = virtual ? 2 : 0;
            if (!hasRoutableIpv4()) {
                r += 1;
            }
            return r;
        }

        @Override
        public String toString() {
            return label();
        }
    }

    private final ObservableList<PacketRow> rows = FXCollections.observableArrayList();
    private final ConcurrentLinkedQueue<PacketRow> pending = new ConcurrentLinkedQueue<>();
    private final ReadOnlyLongWrapper captured = new ReadOnlyLongWrapper(0);
    private final ReadOnlyLongWrapper dropped = new ReadOnlyLongWrapper(0);
    private final ReadOnlyLongWrapper bytes = new ReadOnlyLongWrapper(0);

    private volatile String deviceName = "";
    private volatile String bpf = "";
    private volatile boolean promiscuous = true;
    private volatile int rowCap = 50_000;
    private volatile boolean running;
    private volatile PcapHandle handle;
    private volatile DataLinkType linkType = DataLinkType.EN10MB;
    private long counter;
    private long totalBytes;
    private Instant firstTime;

    @Override
    public String moduleName() {
        return TAG;
    }

    public ObservableList<PacketRow> rows() {
        return rows;
    }

    public ReadOnlyLongProperty capturedProperty() {
        return captured.getReadOnlyProperty();
    }

    public ReadOnlyLongProperty droppedProperty() {
        return dropped.getReadOnlyProperty();
    }

    public ReadOnlyLongProperty bytesProperty() {
        return bytes.getReadOnlyProperty();
    }

    public boolean isRunning() {
        return running;
    }

    public DataLinkType linkType() {
        return linkType;
    }

    // --- configuration --------------------------------------------------------------

    public void setDevice(String name) {
        this.deviceName = name == null ? "" : name;
    }

    /** BPF capture filter, e.g. {@code udp port 16004} or {@code host 192.168.1.10 and not port 22}. */
    public void setCaptureFilter(String bpf) {
        this.bpf = bpf == null ? "" : bpf.trim();
    }

    public void setPromiscuous(boolean promiscuous) {
        this.promiscuous = promiscuous;
    }

    public void setRowCap(int rowCap) {
        this.rowCap = Math.max(1_000, rowCap);
    }

    /** Lists capture devices. Throws IllegalStateException with guidance when the driver is missing. */
    public static List<Device> devices() {
        try {
            List<Device> out = new ArrayList<>();
            for (PcapNetworkInterface nif : Pcaps.findAllDevs()) {
                List<String> addrs = new ArrayList<>();
                for (PcapAddress a : nif.getAddresses()) {
                    if (a.getAddress() != null) {
                        addrs.add(a.getAddress().getHostAddress());
                    }
                }
                boolean virtual = NetInterfaces.looksVirtual(nif.getName()) || NetInterfaces.looksVirtual(nif.getDescription());
                out.add(new Device(nif.getName(), nif.getDescription(), addrs, nif.isLoopBack(), virtual));
            }
            out.sort(Comparator.comparingInt(Device::rank));
            return out;
        } catch (PcapNativeException e) {
            throw new IllegalStateException("Could not list capture devices: " + e.getMessage(), e);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            throw new IllegalStateException("Packet capture driver not available. Install Npcap on Windows "
                    + "(https://npcap.com) or libpcap on Linux, then restart LinkScope.", e);
        }
    }

    // --- lifecycle ------------------------------------------------------------------

    @Override
    public void start() {
        if (running) {
            return;
        }
        if (deviceName.isEmpty()) {
            logError("Pick an interface first");
            setStatus(ModuleStatus.ERROR);
            return;
        }
        running = true;
        setStatus(ModuleStatus.CONNECTING);
        exec.submit(this::run);
    }

    @Override
    public void stop() {
        running = false;
    }

    /** Clears the table and counters. */
    public void clear() {
        counter = 0;
        totalBytes = 0;
        firstTime = null;
        pending.clear();
        FxThread.run(() -> {
            rows.clear();
            captured.set(0);
            dropped.set(0);
            bytes.set(0);
        });
    }

    private void run() {
        PcapHandle h = null;
        try {
            PcapNetworkInterface nif = Pcaps.getDevByName(deviceName);
            if (nif == null) {
                throw new PcapNativeException("Interface not found: " + deviceName);
            }
            h = nif.openLive(SNAPLEN, promiscuous ? PcapNetworkInterface.PromiscuousMode.PROMISCUOUS
                    : PcapNetworkInterface.PromiscuousMode.NONPROMISCUOUS, READ_TIMEOUT_MS);
            if (!bpf.isEmpty()) {
                h.setFilter(bpf, BpfProgram.BpfCompileMode.OPTIMIZE);
            }
            handle = h;
            linkType = h.getDlt();
            logInfo("Capturing on " + (nif.getDescription() == null ? nif.getName() : nif.getDescription())
                    + (bpf.isEmpty() ? "" : " with filter \"" + bpf + "\"") + (promiscuous ? " (promiscuous)" : "")
                    + ", link type " + linkType);
            setStatus(ModuleStatus.CONNECTED);
            exec.submit(this::flushLoop);
            long lastStats = System.currentTimeMillis();
            while (running) {
                try {
                    Packet packet = h.getNextPacketEx();
                    Timestamp ts = h.getTimestamp();
                    Instant time = ts == null ? Instant.now() : ts.toInstant();
                    if (firstTime == null) {
                        firstTime = time;
                    }
                    double rel = (time.toEpochMilli() - firstTime.toEpochMilli()) / 1000.0
                            + (time.getNano() % 1_000_000) / 1_000_000_000.0;
                    PacketRow row = PacketDissector.dissect(packet, ++counter, time, rel);
                    totalBytes += row.length();
                    pending.add(row);
                } catch (TimeoutException ignored) {
                    // no packet within the read timeout; loop and check the running flag
                }
                long now = System.currentTimeMillis();
                if (now - lastStats > 1000) {
                    lastStats = now;
                    updateStats(h);
                }
            }
            logInfo("Capture stopped after " + counter + " packet(s), " + totalBytes + " bytes");
        } catch (PcapNativeException | NotOpenException | EOFException e) {
            if (running) {
                logError("Capture failed on " + deviceName, e);
                setStatus(ModuleStatus.ERROR);
            }
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            logError("Packet capture driver not available (install Npcap or libpcap): " + e.getMessage());
            setStatus(ModuleStatus.ERROR);
        } finally {
            running = false;
            handle = null;
            if (h != null) {
                h.close();
            }
            flushPending();
            if (status() != ModuleStatus.ERROR) {
                setStatus(ModuleStatus.DISCONNECTED);
            }
        }
    }

    private void updateStats(PcapHandle h) {
        try {
            PcapStat s = h.getStats();
            long d = s.getNumPacketsDropped() + s.getNumPacketsDroppedByIf();
            long total = totalBytes;
            long count = counter;
            FxThread.run(() -> {
                dropped.set(d);
                captured.set(count);
                bytes.set(total);
            });
        } catch (PcapNativeException | NotOpenException ignored) {
            // stats are best effort
        }
    }

    private void flushLoop() {
        while (running) {
            flushPending();
            try {
                Thread.sleep(FLUSH_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void flushPending() {
        if (pending.isEmpty()) {
            return;
        }
        List<PacketRow> batch = new ArrayList<>();
        PacketRow r;
        while ((r = pending.poll()) != null) {
            batch.add(r);
        }
        long count = counter;
        long total = totalBytes;
        FxThread.run(() -> {
            rows.addAll(batch);
            if (rows.size() > rowCap) {
                rows.remove(0, rows.size() - rowCap);
            }
            captured.set(count);
            bytes.set(total);
        });
    }

    // --- files ------------------------------------------------------------------------

    /** Replaces the table with the packets of a .pcap file. */
    public void openFile(Path file) {
        exec.submit(() -> {
            clear();
            try (PcapHandle h = Pcaps.openOffline(file.toString())) {
                linkType = h.getDlt();
                List<PacketRow> loaded = new ArrayList<>();
                Instant first = null;
                long n = 0;
                long total = 0;
                while (true) {
                    Packet packet;
                    try {
                        packet = h.getNextPacketEx();
                    } catch (EOFException end) {
                        break;
                    } catch (TimeoutException ignored) {
                        continue;
                    }
                    Timestamp ts = h.getTimestamp();
                    Instant time = ts == null ? Instant.now() : ts.toInstant();
                    if (first == null) {
                        first = time;
                    }
                    double rel = (time.toEpochMilli() - first.toEpochMilli()) / 1000.0;
                    PacketRow row = PacketDissector.dissect(packet, ++n, time, rel);
                    total += row.length();
                    loaded.add(row);
                    if (loaded.size() > rowCap) {
                        loaded.remove(0);
                    }
                }
                long count = n;
                long bytesTotal = total;
                FxThread.run(() -> {
                    rows.setAll(loaded);
                    captured.set(count);
                    bytes.set(bytesTotal);
                });
                counter = n;
                totalBytes = total;
                logInfo("Opened " + file.getFileName() + ": " + n + " packet(s), link type " + linkType);
            } catch (PcapNativeException | NotOpenException e) {
                logError("Could not open " + file, e);
            } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
                logError("Packet capture driver not available (install Npcap or libpcap): " + e.getMessage());
            }
        });
    }

    /** Writes the given rows (typically the filtered view) as a .pcap file. */
    public void saveFile(Path file, List<PacketRow> toSave, Runnable onDone) {
        List<PacketRow> snapshot = new ArrayList<>(toSave);
        DataLinkType dlt = linkType;
        exec.submit(() -> {
            try (PcapHandle dead = Pcaps.openDead(dlt, SNAPLEN); PcapDumper dumper = dead.dumpOpen(file.toString())) {
                for (PacketRow r : snapshot) {
                    dumper.dump(UnknownPacket.newPacket(r.raw(), 0, r.raw().length), Timestamp.from(r.time()));
                }
                logInfo("Saved " + snapshot.size() + " packet(s) to " + file);
                FxThread.run(onDone);
            } catch (PcapNativeException | NotOpenException e) {
                logError("Could not save " + file, e);
            } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
                logError("Packet capture driver not available (install Npcap or libpcap): " + e.getMessage());
            }
        });
    }

    /** Not applicable: capture is read-only. */
    @Override
    public void send(byte[] payload) {
        logError("Capture has nothing to send to");
    }

    static boolean fileLooksLikePcap(Path file) throws IOException {
        byte[] head = new byte[4];
        try (var in = Files.newInputStream(file)) {
            if (in.read(head) < 4) {
                return false;
            }
        }
        int magic = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
        return magic == 0xa1b2c3d4 || magic == 0xd4c3b2a1 || magic == 0xa1b23c4d || magic == 0x4d3cb2a1;
    }
}
