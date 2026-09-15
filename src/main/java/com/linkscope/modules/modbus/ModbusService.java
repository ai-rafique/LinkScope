package com.linkscope.modules.modbus;

import com.linkscope.core.AbstractService;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.PayloadCodec;
import com.linkscope.core.protocol.Crc16;
import com.linkscope.core.protocol.ModbusDecoder;
import com.linkscope.core.protocol.ModbusDecoder.Frame;
import com.linkscope.core.protocol.ModbusEncoder;
import com.linkscope.modules.serial.SerialService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Modbus master/monitor over a serial port (RTU) or TCP. Incoming bytes are framed by the
 * inter-frame gap (RTU) or the MBAP length (TCP), verified with the configured CRC, and
 * decoded; outgoing PDUs are wrapped with the same CRC (RTU) or an MBAP header (TCP).
 */
public final class ModbusService extends AbstractService {
    public static final String TAG = "MODBUS";
    public static final int MAX_EXCHANGES = 2_000;
    private static final int TCP_READ_TIMEOUT_MS = 50;

    public enum Mode {
        RTU("RTU (serial)"), TCP("TCP");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** One frame in either direction; {@code frame} is empty only for undecodable bytes. */
    public record Exchange(LocalTime time, String direction, byte[] raw, Optional<Frame> frame, boolean crcOk, String note) {
        public String summary() {
            return frame.map(Frame::summary).orElse(note);
        }
    }

    private final ObservableList<Exchange> exchanges = FXCollections.observableArrayList();

    private volatile Mode mode = Mode.RTU;
    private volatile String portName = "";
    private volatile int baud = 9600;
    private volatile int dataBits = 8;
    private volatile SerialService.Parity parity = SerialService.Parity.NONE;
    private volatile SerialService.StopBits stopBits = SerialService.StopBits.ONE;
    private volatile String host = "127.0.0.1";
    private volatile int tcpPort = 502;
    private volatile Crc16 crc = Crc16.STANDARD_MODBUS;
    private volatile int frameGapMs;
    private volatile Supplier<ModbusLink> linkFactory;

    private volatile ModbusLink link;
    private volatile boolean running;
    private volatile boolean polling;
    private int transactionId;

    @Override
    public String moduleName() {
        return TAG;
    }

    public ObservableList<Exchange> exchanges() {
        return exchanges;
    }

    // --- configuration --------------------------------------------------------------

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    public void setSerial(String portName, int baud, int dataBits, SerialService.Parity parity, SerialService.StopBits stopBits) {
        if (baud <= 0) {
            throw new IllegalArgumentException("Baud rate must be positive");
        }
        this.portName = portName == null ? "" : portName.trim();
        this.baud = baud;
        this.dataBits = dataBits;
        this.parity = parity;
        this.stopBits = stopBits;
    }

    public void setTcp(String host, int port) {
        this.host = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        this.tcpPort = requirePort(port);
    }

    /** CRC used to verify RTU frames in and to append on RTU frames out. Takes effect immediately. */
    public void setCrc(Crc16 crc) {
        this.crc = crc;
    }

    public Crc16 crc() {
        return crc;
    }

    /** RTU silence that ends a frame; 0 = derive from the baud rate (3.5 character times). */
    public void setFrameGapMs(int frameGapMs) {
        this.frameGapMs = Math.max(0, frameGapMs);
    }

    /** Test hook: supplies the link instead of building one from the serial/TCP settings. */
    void setLinkFactory(Supplier<ModbusLink> factory) {
        this.linkFactory = factory;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPolling() {
        return polling;
    }

    // --- lifecycle ------------------------------------------------------------------

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        setStatus(ModuleStatus.CONNECTING);
        exec.submit(this::run);
    }

    @Override
    public void stop() {
        polling = false;
        running = false;
        ModbusLink l = link;
        if (l != null) {
            l.close();
        }
    }

    private ModbusLink buildLink() {
        Supplier<ModbusLink> f = linkFactory;
        if (f != null) {
            return f.get();
        }
        return mode == Mode.TCP ? new TcpLink(host, tcpPort) : new SerialLink(portName, baud, dataBits, parity, stopBits);
    }

    private void run() {
        ModbusLink l = buildLink();
        try {
            l.open();
            link = l;
            int gap = mode == Mode.TCP ? TCP_READ_TIMEOUT_MS : frameGapMs > 0 ? frameGapMs
                    : l instanceof SerialLink s ? s.frameGapMs() : 20;
            logInfo("Opened " + l.describe() + " (" + mode.label + ", " + crc.description()
                    + (mode == Mode.RTU ? ", frame gap " + gap + " ms" : "") + ")");
            setStatus(ModuleStatus.CONNECTED);
            readLoop(l, gap);
        } catch (IOException e) {
            if (running) {
                logError("Link error", e);
                setStatus(ModuleStatus.ERROR);
            }
        } finally {
            running = false;
            polling = false;
            link = null;
            l.close();
            if (status() != ModuleStatus.ERROR) {
                logInfo("Closed");
                setStatus(ModuleStatus.DISCONNECTED);
            }
        }
    }

    private void readLoop(ModbusLink l, int gapMs) throws IOException {
        byte[] buf = new byte[1024];
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        while (running) {
            int n = l.read(buf, gapMs);
            if (n < 0) {
                if (running) {
                    logInfo("Link closed by peer");
                }
                return;
            }
            if (n > 0) {
                acc.write(buf, 0, n);
            }
            if (acc.size() == 0) {
                continue;
            }
            byte[] rest = mode == Mode.TCP ? drainTcp(acc.toByteArray()) : drainRtu(acc.toByteArray(), n == 0);
            acc.reset();
            acc.write(rest, 0, rest.length);
        }
    }

    /** Emits every complete MBAP frame at the front of {@code bytes}; returns the unconsumed tail. */
    byte[] drainTcp(byte[] bytes) {
        int start = 0;
        while (bytes.length - start >= 6) {
            int pid = ((bytes[start + 2] & 0xFF) << 8) | (bytes[start + 3] & 0xFF);
            int len = ((bytes[start + 4] & 0xFF) << 8) | (bytes[start + 5] & 0xFF);
            if (pid != 0 || len < 2 || len > 254) {
                emit(Arrays.copyOfRange(bytes, start, bytes.length), false, "not an MBAP frame");
                return new byte[0];
            }
            int total = 6 + len;
            if (bytes.length - start < total) {
                break;
            }
            emit(Arrays.copyOfRange(bytes, start, start + total), true, "");
            start += total;
        }
        return Arrays.copyOfRange(bytes, start, bytes.length);
    }

    /**
     * Emits every CRC-valid frame at the front of {@code bytes} (shortest valid prefix first,
     * so back-to-back frames in one read are separated). After a silent gap, whatever is left
     * is emitted as a bad-CRC frame. Returns the unconsumed tail.
     */
    byte[] drainRtu(byte[] bytes, boolean gapElapsed) {
        int start = 0;
        while (bytes.length - start >= 4) {
            int found = -1;
            for (int len = 4; len <= bytes.length - start && len <= 256; len++) {
                if (crc.verify(Arrays.copyOfRange(bytes, start, start + len))) {
                    found = len;
                    break;
                }
            }
            if (found < 0) {
                break;
            }
            emit(Arrays.copyOfRange(bytes, start, start + found), true, "");
            start += found;
        }
        if (gapElapsed && start < bytes.length) {
            emit(Arrays.copyOfRange(bytes, start, bytes.length), false, "CRC mismatch");
            start = bytes.length;
        }
        return Arrays.copyOfRange(bytes, start, bytes.length);
    }

    private void emit(byte[] frame, boolean valid, String problem) {
        Optional<Frame> decoded;
        String note;
        if (mode == Mode.TCP) {
            decoded = valid ? ModbusDecoder.decodeTcp(frame) : Optional.empty();
            note = valid ? decoded.map(Frame::summary).orElse("undecodable MBAP frame") : problem;
        } else if (valid) {
            decoded = ModbusDecoder.decodeRtu(frame, crc);
            note = decoded.map(Frame::summary).orElse("undecodable frame");
        } else {
            decoded = frame.length >= 2 ? Optional.of(ModbusDecoder.decodeRtuUnchecked(frame)) : Optional.empty();
            int calc = frame.length >= 3 ? crc.compute(frame, 0, frame.length - 2) : -1;
            note = "CRC BAD" + (calc >= 0 ? " (calculated " + Crc16.hex4(calc) + ", received "
                    + Crc16.hex4(crc.trailing(frame, frame.length)) + ")" : "")
                    + decoded.map(f -> " — looks like " + f.summary()).orElse("");
        }
        record("RX", frame, decoded, valid, note);
        if (valid) {
            log.rx(TAG, frame, note);
        } else {
            log.rx(TAG, frame, note);
            logError("Received frame with " + (problem.isEmpty() ? "bad CRC" : problem) + ": " + PayloadCodec.toHex(frame));
        }
    }

    private void record(String direction, byte[] raw, Optional<Frame> frame, boolean crcOk, String note) {
        Exchange x = new Exchange(LocalTime.now(), direction, raw, frame, crcOk, note);
        FxThread.run(() -> {
            exchanges.add(x);
            if (exchanges.size() > MAX_EXCHANGES) {
                exchanges.remove(0, exchanges.size() - MAX_EXCHANGES);
            }
        });
    }

    // --- sending --------------------------------------------------------------------

    /** Wraps a PDU for the current mode (CRC for RTU, MBAP for TCP) and sends it. */
    public void sendPdu(int unitId, byte[] pdu) {
        byte[] frame;
        synchronized (this) {
            frame = mode == Mode.TCP ? ModbusEncoder.tcpFrame(transactionId++ & 0xFFFF, unitId, pdu)
                    : ModbusEncoder.rtuFrame(unitId, pdu, crc);
        }
        sendFrame(frame);
    }

    /** Sends unit id + PDU bytes typed by hand, adding the CRC (RTU) or MBAP header (TCP). */
    public void sendRaw(byte[] unitAndPdu) {
        if (unitAndPdu.length < 2) {
            logError("Raw frame needs at least a unit id and a function code");
            return;
        }
        byte[] pdu = Arrays.copyOfRange(unitAndPdu, 1, unitAndPdu.length);
        sendPdu(unitAndPdu[0] & 0xFF, pdu);
    }

    private void sendFrame(byte[] frame) {
        ModbusLink l = link;
        if (l == null) {
            logError("Not connected — open the link first");
            return;
        }
        Optional<Frame> decoded = mode == Mode.TCP ? ModbusDecoder.decodeTcp(frame) : ModbusDecoder.decodeRtu(frame, crc);
        String note = decoded.map(Frame::summary).orElse("raw frame");
        exec.submit(() -> {
            try {
                l.write(frame);
                record("TX", frame, decoded, true, note);
                log.tx(TAG, frame, note);
            } catch (IOException e) {
                logError("Send failed", e);
            }
        });
    }

    /** As {@link #sendRaw}: the payload is unit id followed by the PDU. */
    @Override
    public void send(byte[] payload) {
        sendRaw(payload);
    }

    /** Repeats {@code request} every {@code intervalMs} until {@link #stopPolling()} or the link closes. */
    public void startPolling(Supplier<byte[]> unitAndPdu, int intervalMs) {
        if (polling) {
            return;
        }
        polling = true;
        logInfo("Polling every " + intervalMs + " ms");
        exec.submit(() -> {
            try {
                while (polling && running) {
                    sendRaw(unitAndPdu.get());
                    Thread.sleep(Math.max(20, intervalMs));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                logError("Polling stopped", e);
            } finally {
                polling = false;
            }
        });
    }

    public void stopPolling() {
        if (polling) {
            polling = false;
            logInfo("Polling stopped");
        }
    }
}
