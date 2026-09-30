package com.linkscope.modules.hid;

import com.linkscope.core.AbstractService;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleStatus;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads one input device and publishes decoded reports at a UI-friendly rate. HID devices
 * are read with blocking ReadFile calls on a virtual thread; XInput slots are polled at
 * 125 Hz and published when the packet counter changes. Output goes back either as
 * rumble (XInput) or as a raw output report (HID).
 */
public final class HidService extends AbstractService {
    public static final String TAG = "HID";
    private static final long FLUSH_MS = 16;
    private static final long XINPUT_POLL_MS = 8;

    private final ReadOnlyObjectWrapper<HidReport> latest = new ReadOnlyObjectWrapper<>();
    private final ReadOnlyObjectWrapper<HidLayout> layout = new ReadOnlyObjectWrapper<>();
    private final ReadOnlyIntegerWrapper reportsPerSecond = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyIntegerWrapper reportCount = new ReadOnlyIntegerWrapper(0);

    private volatile HidDevice device;
    private volatile boolean logReports;
    private volatile boolean running;
    private volatile HidNative.Handle handle;
    private volatile HidReport pending;
    private final AtomicLong counter = new AtomicLong();
    private volatile boolean canWrite;

    @Override
    public String moduleName() {
        return TAG;
    }

    public ReadOnlyObjectProperty<HidReport> latestProperty() {
        return latest.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<HidLayout> layoutProperty() {
        return layout.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty reportsPerSecondProperty() {
        return reportsPerSecond.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty reportCountProperty() {
        return reportCount.getReadOnlyProperty();
    }

    public boolean isRunning() {
        return running;
    }

    public HidDevice device() {
        return device;
    }

    /** True while an XInput controller is open (rumble) or a HID device with output reports is open. */
    public boolean canWrite() {
        return running && canWrite;
    }

    // --- configuration ---------------------------------------------------------------

    public void setDevice(HidDevice device) {
        this.device = device;
    }

    /** Also write every report to the shared log (noisy: gamepads report at 125-250 Hz). */
    public void setLogReports(boolean logReports) {
        this.logReports = logReports;
    }

    /** Available devices; blocking, call off the FX thread. */
    public static List<HidDevice> devices() {
        return HidNative.enumerate();
    }

    public static boolean available() {
        return HidNative.available() || HidNative.xinputAvailable();
    }

    public static String unavailableReason() {
        return HidNative.loadError().isEmpty() ? "No HID support on this platform" : HidNative.loadError();
    }

    // --- lifecycle -------------------------------------------------------------------

    @Override
    public void start() {
        if (running) {
            return;
        }
        HidDevice d = device;
        if (d == null) {
            logError("Pick a device first");
            setStatus(ModuleStatus.ERROR);
            return;
        }
        running = true;
        counter.set(0);
        setStatus(ModuleStatus.CONNECTING);
        exec.submit(() -> {
            if (d.isXInput()) {
                pollXInput(d);
            } else {
                readHid(d);
            }
        });
        exec.submit(this::flushLoop);
    }

    @Override
    public void stop() {
        running = false;
        HidNative.Handle h = handle;
        if (h != null) {
            h.cancel();
        }
    }

    private void readHid(HidDevice d) {
        HidNative.Handle h = null;
        try {
            h = HidNative.Handle.open(d.path());
            handle = h;
            canWrite = d.outputLength() > 0;
            HidLayout l = h.layout();
            FxThread.run(() -> layout.set(l));
            logInfo("Opened " + d.product() + " (" + d.ids() + "): " + l.buttons().size() + " button(s), " + l.values().size()
                    + " value(s), input report " + l.inputLength() + " B" + (l.outputLength() > 0 ? ", output report " + l.outputLength() + " B" : "")
                    + (h.writable() ? "" : ", read-only"));
            if (l.inputLength() == 0) {
                throw new IOException("This device has no input reports; nothing to show");
            }
            setStatus(ModuleStatus.CONNECTED);
            while (running) {
                HidReport r = h.read();
                if (r == null) {
                    if (running) {
                        throw new IOException("The device stopped answering (unplugged?)");
                    }
                    break;
                }
                publish(r);
            }
            logInfo("Closed " + d.product() + " after " + counter.get() + " report(s)");
        } catch (IOException e) {
            logError("HID: " + e.getMessage());
            setStatus(ModuleStatus.ERROR);
        } finally {
            running = false;
            handle = null;
            if (h != null) {
                h.close();
            }
            if (status() != ModuleStatus.ERROR) {
                setStatus(ModuleStatus.DISCONNECTED);
            }
        }
    }

    private void pollXInput(HidDevice d) {
        try {
            HidLayout l = HidLayout.forXInput();
            FxThread.run(() -> layout.set(l));
            canWrite = true;
            XInputState first = HidNative.xinputState(d.xinputSlot());
            if (first == null) {
                throw new IOException("No controller in XInput slot " + (d.xinputSlot() + 1));
            }
            logInfo("Reading Xbox controller in XInput slot " + (d.xinputSlot() + 1));
            setStatus(ModuleStatus.CONNECTED);
            long lastPacket = -1;
            long lastPublish = 0;
            int misses = 0;
            while (running) {
                XInputState s = HidNative.xinputState(d.xinputSlot());
                if (s == null) {
                    if (++misses > 25) {
                        throw new IOException("Controller in slot " + (d.xinputSlot() + 1) + " disconnected");
                    }
                } else {
                    misses = 0;
                    long now = System.currentTimeMillis();
                    if (s.packet() != lastPacket || now - lastPublish > 250) {
                        lastPacket = s.packet();
                        lastPublish = now;
                        publish(s.toReport(Instant.now()));
                    }
                }
                Thread.sleep(XINPUT_POLL_MS);
            }
            HidNative.xinputRumble(d.xinputSlot(), 0, 0);
            logInfo("Stopped reading XInput slot " + (d.xinputSlot() + 1) + " after " + counter.get() + " state change(s)");
        } catch (IOException e) {
            logError("XInput: " + e.getMessage());
            setStatus(ModuleStatus.ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
            if (status() != ModuleStatus.ERROR) {
                setStatus(ModuleStatus.DISCONNECTED);
            }
        }
    }

    private void publish(HidReport r) {
        counter.incrementAndGet();
        pending = r;
        if (logReports) {
            log.rx(TAG, r.raw(), describe(r));
        }
    }

    /** One line for the log: held buttons and every axis that is off its rest position. */
    static String describe(HidReport r) {
        StringBuilder sb = new StringBuilder();
        if (!r.pressed().isEmpty()) {
            sb.append(String.join(" ", r.pressed()));
        }
        for (HidReport.Axis a : r.axes()) {
            boolean active = a.hat() ? a.hatDegrees() >= 0 : a.centered() ? Math.abs(a.normalized()) > 0.05 : a.fraction() > 0.02;
            if (active) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(a.name()).append(' ').append(a.display());
            }
        }
        return sb.toString();
    }

    /** Pushes the newest report to the FX thread at most every 16 ms and keeps the rate counter. */
    private void flushLoop() {
        long windowStart = System.currentTimeMillis();
        long windowCount = 0;
        long lastSeen = counter.get();
        while (running) {
            HidReport r = pending;
            if (r != null) {
                pending = null;
                long count = counter.get();
                FxThread.run(() -> {
                    latest.set(r);
                    reportCount.set((int) Math.min(Integer.MAX_VALUE, count));
                });
            }
            long now = System.currentTimeMillis();
            long total = counter.get();
            windowCount += total - lastSeen;
            lastSeen = total;
            if (now - windowStart >= 1000) {
                int rate = (int) (windowCount * 1000 / Math.max(1, now - windowStart));
                windowStart = now;
                windowCount = 0;
                FxThread.run(() -> reportsPerSecond.set(rate));
            }
            try {
                Thread.sleep(FLUSH_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        FxThread.run(() -> reportsPerSecond.set(0));
    }

    // --- output ----------------------------------------------------------------------

    /** Rumble an XInput controller: 0..1 per motor. The left motor is the heavy, low-frequency one. */
    public void rumble(double left, double right) {
        HidDevice d = device;
        if (d == null || !d.isXInput() || !running) {
            logError("Rumble needs an open Xbox controller (XInput)");
            return;
        }
        int l = (int) Math.round(Math.max(0, Math.min(1, left)) * 65535);
        int r = (int) Math.round(Math.max(0, Math.min(1, right)) * 65535);
        exec.submit(() -> {
            if (HidNative.xinputRumble(d.xinputSlot(), l, r)) {
                logInfo(l == 0 && r == 0 ? "Rumble off" : "Rumble left " + Math.round(left * 100) + "%, right " + Math.round(right * 100) + "%");
            } else {
                logError("XInput refused the rumble command");
            }
        });
    }

    /** Sends raw bytes as an output report to the open HID device (report id first, 00 when unused). */
    public void sendOutput(byte[] report) {
        HidNative.Handle h = handle;
        if (h == null) {
            logError("Open a HID device first");
            return;
        }
        exec.submit(() -> {
            try {
                h.write(report);
                log.tx(TAG, report, "output report");
            } catch (IOException e) {
                logError("Output report failed: " + e.getMessage());
            }
        });
    }

    @Override
    public void send(byte[] payload) {
        sendOutput(payload);
    }
}
