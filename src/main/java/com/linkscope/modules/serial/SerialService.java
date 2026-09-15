package com.linkscope.modules.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortInvalidPortException;
import com.linkscope.core.AbstractService;
import com.linkscope.core.ModuleStatus;

import java.util.Arrays;
import java.util.List;

/**
 * Serial port via jSerialComm: enumerate, open with baud/data/parity/stop/flow settings,
 * DTR/RTS control, send/receive. The read loop runs on a virtual thread with a
 * semi-blocking timeout so close() unblocks it promptly.
 */
public final class SerialService extends AbstractService {
    public static final String TAG = "SERIAL";
    public static final List<Integer> COMMON_BAUD_RATES = List.of(
            300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600);
    private static final int READ_TIMEOUT_MS = 200;
    private static final int READ_BUFFER = 4096;

    public enum Parity {
        NONE("None", SerialPort.NO_PARITY, 'N'),
        EVEN("Even", SerialPort.EVEN_PARITY, 'E'),
        ODD("Odd", SerialPort.ODD_PARITY, 'O'),
        MARK("Mark", SerialPort.MARK_PARITY, 'M'),
        SPACE("Space", SerialPort.SPACE_PARITY, 'S');

        public final String label;
        final int code;
        final char letter;

        Parity(String label, int code, char letter) {
            this.label = label;
            this.code = code;
            this.letter = letter;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum StopBits {
        ONE("1", SerialPort.ONE_STOP_BIT),
        ONE_POINT_FIVE("1.5", SerialPort.ONE_POINT_FIVE_STOP_BITS),
        TWO("2", SerialPort.TWO_STOP_BITS);

        public final String label;
        final int code;

        StopBits(String label, int code) {
            this.label = label;
            this.code = code;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum FlowControl {
        NONE("None", SerialPort.FLOW_CONTROL_DISABLED),
        RTS_CTS("RTS/CTS", SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED),
        XON_XOFF("XON/XOFF", SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED);

        public final String label;
        final int code;

        FlowControl(String label, int code) {
            this.label = label;
            this.code = code;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private volatile String portName = "";
    private volatile int baudRate = 9600;
    private volatile int dataBits = 8;
    private volatile Parity parity = Parity.NONE;
    private volatile StopBits stopBits = StopBits.ONE;
    private volatile FlowControl flowControl = FlowControl.NONE;
    private volatile boolean dtr = true;
    private volatile boolean rts = true;

    private volatile SerialPort port;
    private volatile boolean running;

    @Override
    public String moduleName() {
        return TAG;
    }

    /** Enumerates ports. Can take a moment on Windows; call off the FX thread. */
    public static List<SerialPort> availablePorts() {
        return List.of(SerialPort.getCommPorts());
    }

    // --- configuration ----------------------------------------------------------------

    public void setPortName(String portName) {
        this.portName = portName == null ? "" : portName.trim();
    }

    public void setBaudRate(int baudRate) {
        if (baudRate <= 0) {
            throw new IllegalArgumentException("Baud rate must be positive");
        }
        this.baudRate = baudRate;
    }

    public void setDataBits(int dataBits) {
        if (dataBits < 5 || dataBits > 8) {
            throw new IllegalArgumentException("Data bits must be 5-8");
        }
        this.dataBits = dataBits;
    }

    public void setParity(Parity parity) {
        this.parity = parity;
    }

    public void setStopBits(StopBits stopBits) {
        this.stopBits = stopBits;
    }

    public void setFlowControl(FlowControl flowControl) {
        this.flowControl = flowControl;
    }

    public void setDtr(boolean dtr) {
        this.dtr = dtr;
        SerialPort p = port;
        if (p != null) {
            exec.submit(() -> applyLine(p, "DTR", dtr ? p.setDTR() : p.clearDTR()));
        }
    }

    public void setRts(boolean rts) {
        this.rts = rts;
        SerialPort p = port;
        if (p != null) {
            exec.submit(() -> applyLine(p, "RTS", rts ? p.setRTS() : p.clearRTS()));
        }
    }

    public String settingsSummary() {
        return baudRate + " " + dataBits + parity.letter + stopBits.label + ", flow " + flowControl.label;
    }

    // --- lifecycle --------------------------------------------------------------------

    @Override
    public void start() {
        if (running) {
            return;
        }
        if (portName.isEmpty()) {
            logError("No serial port selected");
            setStatus(ModuleStatus.ERROR);
            return;
        }
        running = true;
        setStatus(ModuleStatus.CONNECTING);
        exec.submit(this::readLoop);
    }

    @Override
    public void stop() {
        running = false;
        SerialPort p = port;
        if (p != null) {
            p.closePort();
        }
    }

    @Override
    public void send(byte[] payload) {
        SerialPort p = port;
        if (p == null || !p.isOpen()) {
            logError("Port not open — open it first");
            return;
        }
        exec.submit(() -> {
            int written = p.writeBytes(payload, payload.length);
            if (written < 0) {
                logError("Write to " + portName + " failed (" + errorDetail(p) + ")");
            } else {
                log.tx(TAG, Arrays.copyOf(payload, written), portName);
                if (written < payload.length) {
                    logError("Short write: " + written + " of " + payload.length + " bytes");
                }
            }
        });
    }

    private void readLoop() {
        SerialPort p = null;
        try {
            p = SerialPort.getCommPort(portName);
            p.setComPortParameters(baudRate, dataBits, stopBits.code, parity.code);
            p.setFlowControl(flowControl.code);
            p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT_MS, 0);
            if (!p.openPort()) {
                logError("Could not open " + portName + " (" + errorDetail(p) + ")");
                setStatus(ModuleStatus.ERROR);
                return;
            }
            port = p;
            p.setDTRandRTS(dtr, rts);
            logInfo("Opened " + portName + " @ " + settingsSummary()
                    + (dtr ? ", DTR" : "") + (rts ? ", RTS" : ""));
            setStatus(ModuleStatus.CONNECTED);

            byte[] buf = new byte[READ_BUFFER];
            while (running && p.isOpen()) {
                int n = p.readBytes(buf, buf.length);
                if (n < 0) {
                    if (running) {
                        logError("Read from " + portName + " failed (" + errorDetail(p) + ") — device unplugged?");
                        setStatus(ModuleStatus.ERROR);
                    }
                    break;
                }
                if (n > 0) {
                    log.rx(TAG, Arrays.copyOf(buf, n), portName);
                }
            }
        } catch (SerialPortInvalidPortException e) {
            logError("Invalid port " + portName, e);
            setStatus(ModuleStatus.ERROR);
        } finally {
            running = false;
            port = null;
            if (p != null) {
                p.closePort();
            }
            if (status() != ModuleStatus.ERROR) {
                logInfo("Closed " + portName);
                setStatus(ModuleStatus.DISCONNECTED);
            }
        }
    }

    private void applyLine(SerialPort p, String line, boolean ok) {
        if (ok) {
            logInfo(line + " " + (("DTR".equals(line) ? dtr : rts) ? "asserted" : "cleared"));
        } else {
            logError("Could not change " + line + " (" + errorDetail(p) + ")");
        }
    }

    private static String errorDetail(SerialPort p) {
        return "error " + p.getLastErrorCode() + " at " + p.getLastErrorLocation();
    }
}
