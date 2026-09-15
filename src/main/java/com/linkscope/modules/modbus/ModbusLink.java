package com.linkscope.modules.modbus;

import java.io.IOException;

/**
 * Byte pipe under the Modbus module: a serial port for RTU or a TCP socket for Modbus TCP.
 * Reads are bounded by a timeout so the service can detect the inter-frame gap.
 */
interface ModbusLink extends AutoCloseable {

    /** Opens the link; throws with a user-readable message on failure. */
    void open() throws IOException;

    /**
     * Reads up to {@code buf.length} bytes, waiting at most {@code timeoutMs}.
     * Returns 0 on timeout, -1 when the link is closed or broken.
     */
    int read(byte[] buf, int timeoutMs) throws IOException;

    void write(byte[] data) throws IOException;

    /** Human-readable endpoint, e.g. "COM3 @ 9600 8N1" or "192.168.1.10:502". */
    String describe();

    @Override
    void close();
}
