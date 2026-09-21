package com.linkscope.modules.usb;

import com.linkscope.core.AbstractService;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.PcapFiles;
import com.linkscope.modules.capture.PacketRow;
import com.linkscope.modules.capture.UsbPcapDissector;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live USB capture on Windows through USBPcap's command-line helper. The helper lists root
 * hubs and the devices behind each; for a capture it is started with {@code -o -} so the pcap
 * stream arrives on its standard output and is dissected record by record. The helper needs
 * administrator rights and arranges them itself: when started without, it spawns an elevated
 * worker (Windows shows a UAC prompt) and relays the worker's data. Stopping the helper ends
 * the worker too.
 */
public final class UsbCaptureService extends AbstractService {
    public static final String TAG = "USB";
    public static final int LINK_TYPE = UsbPcapDissector.DLT_USBPCAP;
    private static final long FLUSH_INTERVAL_MS = 100;
    private static final Pattern INTERFACE = Pattern.compile("interface \\{value=([^}]*)\\}\\{display=([^}]*)\\}");
    private static final Pattern VALUE = Pattern.compile("value \\{arg=99\\}\\{value=([^}]*)\\}\\{display=([^}]*)\\}(?:\\{enabled=([^}]*)\\})?(?:\\{parent=([^}]*)\\})?");

    /** A USBPcap root hub, e.g. {@code \\.\USBPcap1}. */
    public record Hub(String name, String display) {
        @Override
        public String toString() {
            return display + "  (" + name + ")";
        }
    }

    /**
     * A device attached to a hub. {@code address} is what the helper's {@code --devices}
     * option takes; {@code children} are the interfaces and child devices it reported below it.
     */
    public record UsbDevice(String address, String display, List<String> children) {
        public boolean looksLikeKeyboard() {
            String all = (display + " " + String.join(" ", children)).toLowerCase(Locale.ROOT);
            return all.contains("keyboard");
        }

        public String summary() {
            if (children.isEmpty()) {
                return display;
            }
            List<String> shown = children.size() > 3 ? children.subList(0, 3) : children;
            return display + "  —  " + String.join(", ", shown) + (children.size() > 3 ? " (+" + (children.size() - 3) + ")" : "");
        }
    }

    private final ObservableList<PacketRow> rows = FXCollections.observableArrayList();
    private final ConcurrentLinkedQueue<PacketRow> pending = new ConcurrentLinkedQueue<>();
    private final ReadOnlyLongWrapper captured = new ReadOnlyLongWrapper(0);
    private final ReadOnlyLongWrapper bytes = new ReadOnlyLongWrapper(0);

    private volatile String hub = "";
    private volatile List<String> devices = List.of();
    private volatile boolean allDevices;
    private volatile boolean injectDescriptors = true;
    private volatile int snaplen = 65535;
    private volatile int rowCap = 50_000;
    private volatile boolean running;
    private volatile Process helperProcess;
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

    public ReadOnlyLongProperty bytesProperty() {
        return bytes.getReadOnlyProperty();
    }

    public boolean isRunning() {
        return running;
    }

    // --- configuration --------------------------------------------------------------

    public void setHub(String hub) {
        this.hub = hub == null ? "" : hub.trim();
    }

    /** Device addresses to capture (the numbers in front of the device names). Ignored when {@link #setAllDevices} is on. */
    public void setDevices(List<String> addresses) {
        this.devices = addresses == null ? List.of() : List.copyOf(addresses);
    }

    public void setAllDevices(boolean all) {
        this.allDevices = all;
    }

    /** Ask the helper to emit the descriptors of already connected devices at the start of the capture. */
    public void setInjectDescriptors(boolean inject) {
        this.injectDescriptors = inject;
    }

    public void setSnaplen(int snaplen) {
        this.snaplen = Math.max(64, Math.min(65535, snaplen));
    }

    public void setRowCap(int rowCap) {
        this.rowCap = Math.max(1_000, rowCap);
    }

    // --- helper discovery -----------------------------------------------------------

    /** USBPcapCMD.exe, or null when USBPcap is not installed (or this is not Windows). */
    public static Path helper() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        String home = System.getenv("USBPCAP_HOME");
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home, "USBPcapCMD.exe"));
        }
        String pf = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
        candidates.add(Path.of(pf, "USBPcap", "USBPcapCMD.exe"));
        candidates.add(Path.of(pf, "Wireshark", "extcap", "USBPcapCMD.exe"));
        candidates.add(Path.of(pf, "Wireshark", "extcap", "wireshark", "USBPcapCMD.exe"));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    public static String helperMissingMessage() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return "USB capture uses USBPcap and is available on Windows only.";
        }
        return "USBPcap is not installed. Install it (bundled with Wireshark, or from the LinkScope setup), restart Windows, then try again.";
    }

    // --- hub and device listing -----------------------------------------------------

    /** Root hubs the helper can capture on. Blocking; call off the FX thread. */
    public static List<Hub> hubs() throws IOException {
        Path h = helper();
        if (h == null) {
            throw new IOException(helperMissingMessage());
        }
        return parseInterfaces(run(h, List.of("--extcap-interfaces"), 15));
    }

    /** Devices behind a hub. Blocking; call off the FX thread. */
    public static List<UsbDevice> devices(String hub) throws IOException {
        Path h = helper();
        if (h == null) {
            throw new IOException(helperMissingMessage());
        }
        return parseDevices(run(h, List.of("--extcap-interface", hub, "--extcap-config"), 15));
    }

    static List<Hub> parseInterfaces(String output) {
        List<Hub> out = new ArrayList<>();
        Matcher m = INTERFACE.matcher(output);
        while (m.find()) {
            out.add(new Hub(m.group(1), m.group(2)));
        }
        return out;
    }

    /** Top-level entries (numeric addresses) become devices; entries with a parent are folded into their root's children. */
    static List<UsbDevice> parseDevices(String output) {
        Map<String, String> displays = new LinkedHashMap<>();
        Map<String, String> parents = new LinkedHashMap<>();
        Matcher m = VALUE.matcher(output);
        while (m.find()) {
            displays.put(m.group(1), m.group(2));
            if (m.group(4) != null) {
                parents.put(m.group(1), m.group(4));
            }
        }
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (String id : displays.keySet()) {
            if (!parents.containsKey(id)) {
                children.put(id, new ArrayList<>());
            }
        }
        for (Map.Entry<String, String> e : parents.entrySet()) {
            String root = e.getValue();
            while (parents.containsKey(root)) {
                root = parents.get(root);
            }
            List<String> list = children.get(root);
            if (list != null) {
                list.add(displays.get(e.getKey()));
            }
        }
        List<UsbDevice> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : children.entrySet()) {
            out.add(new UsbDevice(e.getKey(), stripAddress(displays.get(e.getKey())), List.copyOf(e.getValue())));
        }
        return out;
    }

    private static String stripAddress(String display) {
        // "[6] USB Composite Device" -> "USB Composite Device"; the address is shown separately.
        return display.replaceFirst("^\\[\\d+]\\s*", "");
    }

    private static String run(Path exe, List<String> args, int timeoutSeconds) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out;
        try (InputStream in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("USBPcapCMD did not answer within " + timeoutSeconds + " s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for USBPcapCMD");
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    // --- capture --------------------------------------------------------------------

    /** Helper command line: pcap stream on stdout ({@code -o -}), all devices or a list, optional descriptor injection. */
    static List<String> buildCommand(Path helper, String hub, boolean all, List<String> devices, boolean inject, int snaplen) {
        List<String> cmd = new ArrayList<>();
        cmd.add(helper.toString());
        cmd.add("-d");
        cmd.add(hub);
        cmd.add("-o");
        cmd.add("-");
        cmd.add("-s");
        cmd.add(String.valueOf(snaplen));
        if (all) {
            cmd.add("-A");
        } else {
            cmd.add("--devices");
            cmd.add(String.join(",", devices));
        }
        if (inject) {
            cmd.add("--inject-descriptors");
        }
        return cmd;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        Path h = helper();
        if (h == null) {
            logError(helperMissingMessage());
            setStatus(ModuleStatus.ERROR);
            return;
        }
        if (hub.isEmpty()) {
            logError("Pick a root hub first");
            setStatus(ModuleStatus.ERROR);
            return;
        }
        if (!allDevices && devices.isEmpty()) {
            logError("Tick at least one device, or choose all devices");
            setStatus(ModuleStatus.ERROR);
            return;
        }
        running = true;
        setStatus(ModuleStatus.CONNECTING);
        exec.submit(() -> run(h));
    }

    @Override
    public void stop() {
        running = false;
        Process p = helperProcess;
        if (p != null) {
            // Closing the helper's stdout pipe is what makes its elevated worker exit too.
            p.destroy();
        }
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
            bytes.set(0);
        });
    }

    private void run(Path helperExe) {
        Process p = null;
        StringBuilder errors = new StringBuilder();
        try {
            clear();
            List<String> cmd = buildCommand(helperExe, hub, allDevices, devices, injectDescriptors, snaplen);
            logInfo("Starting USBPcapCMD " + String.join(" ", cmd.subList(1, cmd.size())));
            p = new ProcessBuilder(cmd).start();
            helperProcess = p;
            Process proc = p;
            exec.submit(() -> drain(proc.getErrorStream(), errors));
            exec.submit(this::flushLoop);
            try (PcapFiles.Stream stream = new PcapFiles.Stream(new BufferedInputStream(p.getInputStream(), 1 << 16))) {
                PcapFiles.Header header = stream.readHeader();
                if (header == null) {
                    throw new IOException("USBPcapCMD produced no data" + said(errors)
                            + " (administrator rights declined, or the driver is not active yet)");
                }
                if (header.linkType() != LINK_TYPE) {
                    logError("Unexpected link type " + header.linkType() + " from USBPcapCMD; decoding as USBPcap anyway");
                }
                logInfo("Capturing USB traffic on " + hub + (allDevices ? " (all devices)" : " (devices " + String.join(",", devices) + ")"));
                setStatus(ModuleStatus.CONNECTED);
                while (running) {
                    PcapFiles.Record r = stream.next();
                    if (r == null) {
                        break;
                    }
                    enqueue(r);
                }
            } catch (IOException e) {
                if (running) {
                    throw e;
                }
                // the stream breaks when we stop the helper; that is the normal way out
            }
            flushPending();
            if (running) {
                logError("USBPcapCMD stopped on its own after " + counter + " record(s)" + said(errors));
                setStatus(ModuleStatus.ERROR);
            } else {
                logInfo("USB capture stopped after " + counter + " record(s), " + totalBytes + " payload byte(s)");
            }
        } catch (IOException e) {
            logError("USB capture failed", e);
            setStatus(ModuleStatus.ERROR);
        } finally {
            running = false;
            helperProcess = null;
            if (p != null && p.isAlive()) {
                p.destroy();
            }
            flushPending();
            if (status() != ModuleStatus.ERROR) {
                setStatus(ModuleStatus.DISCONNECTED);
            }
        }
    }

    private static String said(StringBuilder errors) {
        String s;
        synchronized (errors) {
            s = errors.toString().trim();
        }
        return s.isEmpty() ? "" : ": " + s;
    }

    private void enqueue(PcapFiles.Record r) {
        if (firstTime == null) {
            firstTime = r.time();
        }
        double rel = (r.time().toEpochMilli() - firstTime.toEpochMilli()) / 1000.0
                + ((r.time().getNano() % 1_000_000) - (firstTime.getNano() % 1_000_000)) / 1_000_000_000.0;
        PacketRow row = UsbPcapDissector.dissect(r.data(), ++counter, r.time(), rel);
        totalBytes += row.payload().length;
        pending.add(row);
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

    private static void drain(InputStream in, StringBuilder into) {
        try (in) {
            byte[] all = in.readAllBytes();
            synchronized (into) {
                into.append(new String(all, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // process closed its output
        }
    }

    /** Single-quoted PowerShell literal; values with spaces are additionally double-quoted for the child's command line. */
    static String psString(String value) {
        String v = value.contains(" ") ? "\"" + value + "\"" : value;
        return "'" + v.replace("'", "''") + "'";
    }

    /** Runs {@code USBPcapCMD -I} elevated: registers non-standard hardware ids so USB 3.0 hubs can be captured. */
    public void initUsb3Capture() {
        Path h = helper();
        if (h == null) {
            logError(helperMissingMessage());
            return;
        }
        exec.submit(() -> {
            try {
                String launcher = "$p = Start-Process -FilePath " + psString(h.toString()) + " -ArgumentList @('-I') -Verb RunAs -PassThru -Wait; exit $p.ExitCode";
                Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-Command", launcher)
                        .redirectErrorStream(true).start();
                String said = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                int code = p.waitFor();
                if (code == 0) {
                    logInfo("USB 3.0 capture support initialised; unplug and replug the device, or restart, before capturing");
                } else {
                    logError("USB 3.0 initialisation did not complete (exit " + code + ")" + (said.isEmpty() ? "" : ": " + said));
                }
            } catch (IOException e) {
                logError("Could not run USBPcapCMD -I", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // --- files ------------------------------------------------------------------------

    /** Replaces the table with the records of a USBPcap file (pure Java, no driver needed). */
    public void openFile(Path file) {
        exec.submit(() -> {
            try {
                PcapFiles.Header header = PcapFiles.readHeader(file);
                if (header.linkType() != LINK_TYPE) {
                    logError(file.getFileName() + " is not a USB capture (link type " + header.linkType() + "); open it in the Capture tab instead");
                    return;
                }
                List<PcapFiles.Record> records = PcapFiles.readAll(file);
                clear();
                records.forEach(this::enqueue);
                flushPending();
                logInfo("Opened " + file.getFileName() + ": " + records.size() + " USB record(s)");
            } catch (IOException e) {
                logError("Could not open " + file, e);
            }
        });
    }

    /** Writes rows as a USBPcap file that Wireshark opens. */
    public void saveFile(Path file, List<PacketRow> toSave, Runnable onDone) {
        List<PacketRow> snapshot = new ArrayList<>(toSave);
        exec.submit(() -> {
            try {
                List<PcapFiles.Record> records = new ArrayList<>(snapshot.size());
                for (PacketRow r : snapshot) {
                    records.add(new PcapFiles.Record(r.time(), r.raw(), r.raw().length));
                }
                PcapFiles.write(file, LINK_TYPE, records);
                logInfo("Saved " + records.size() + " USB record(s) to " + file);
                FxThread.run(onDone);
            } catch (IOException e) {
                logError("Could not save " + file, e);
            }
        });
    }

    /** Not applicable: capture is read-only. */
    @Override
    public void send(byte[] payload) {
        logError("USB capture has nothing to send to");
    }
}
