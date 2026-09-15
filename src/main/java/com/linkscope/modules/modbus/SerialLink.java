package com.linkscope.modules.modbus;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortInvalidPortException;
import com.linkscope.modules.serial.SerialService;

import java.io.IOException;

/** Modbus RTU over a serial port (jSerialComm). */
final class SerialLink implements ModbusLink {
    private final String portName;
    private final int baud;
    private final int dataBits;
    private final SerialService.Parity parity;
    private final SerialService.StopBits stopBits;
    private SerialPort port;

    SerialLink(String portName, int baud, int dataBits, SerialService.Parity parity, SerialService.StopBits stopBits) {
        this.portName = portName;
        this.baud = baud;
        this.dataBits = dataBits;
        this.parity = parity;
        this.stopBits = stopBits;
    }

    /** 3.5 character times at this baud rate (11 bits per char), clamped to 5-100 ms, plus slack. */
    int frameGapMs() {
        double charMs = 11_000.0 / baud;
        return (int) Math.max(5, Math.min(100, Math.ceil(charMs * 3.5))) + 2;
    }

    @Override
    public void open() throws IOException {
        if (portName == null || portName.isBlank()) {
            throw new IOException("No serial port selected");
        }
        SerialPort p;
        try {
            p = SerialPort.getCommPort(portName);
        } catch (SerialPortInvalidPortException e) {
            throw new IOException("Invalid port " + portName);
        }
        p.setComPortParameters(baud, dataBits, stopBitsCode(stopBits), parityCode(parity));
        p.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        if (!p.openPort()) {
            throw new IOException("Could not open " + portName + " (error " + p.getLastErrorCode() + ")");
        }
        p.setDTRandRTS(true, true);
        port = p;
    }

    @Override
    public int read(byte[] buf, int timeoutMs) throws IOException {
        SerialPort p = port;
        if (p == null || !p.isOpen()) {
            return -1;
        }
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, Math.max(1, timeoutMs), 0);
        int n = p.readBytes(buf, buf.length);
        if (n < 0) {
            throw new IOException("Read from " + portName + " failed (error " + p.getLastErrorCode() + ")");
        }
        return n;
    }

    @Override
    public void write(byte[] data) throws IOException {
        SerialPort p = port;
        if (p == null || !p.isOpen()) {
            throw new IOException("Port not open");
        }
        int written = p.writeBytes(data, data.length);
        if (written != data.length) {
            throw new IOException("Write to " + portName + " failed (" + written + " of " + data.length + " bytes)");
        }
    }

    @Override
    public String describe() {
        return portName + " @ " + baud + " " + dataBits + parityLetter(parity) + stopBits.label;
    }

    @Override
    public void close() {
        SerialPort p = port;
        port = null;
        if (p != null) {
            p.closePort();
        }
    }

    private static int parityCode(SerialService.Parity p) {
        return switch (p) {
            case EVEN -> SerialPort.EVEN_PARITY;
            case ODD -> SerialPort.ODD_PARITY;
            case MARK -> SerialPort.MARK_PARITY;
            case SPACE -> SerialPort.SPACE_PARITY;
            default -> SerialPort.NO_PARITY;
        };
    }

    private static int stopBitsCode(SerialService.StopBits s) {
        return switch (s) {
            case ONE_POINT_FIVE -> SerialPort.ONE_POINT_FIVE_STOP_BITS;
            case TWO -> SerialPort.TWO_STOP_BITS;
            default -> SerialPort.ONE_STOP_BIT;
        };
    }

    private static char parityLetter(SerialService.Parity p) {
        return switch (p) {
            case EVEN -> 'E';
            case ODD -> 'O';
            case MARK -> 'M';
            case SPACE -> 'S';
            default -> 'N';
        };
    }
}
