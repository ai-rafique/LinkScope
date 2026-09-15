package com.linkscope.modules.modbus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Modbus TCP over a client socket. */
final class TcpLink implements ModbusLink {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private final String host;
    private final int port;
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    TcpLink(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void open() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        socket = s;
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    @Override
    public int read(byte[] buf, int timeoutMs) throws IOException {
        Socket s = socket;
        if (s == null || s.isClosed()) {
            return -1;
        }
        s.setSoTimeout(Math.max(1, timeoutMs));
        try {
            return in.read(buf);
        } catch (SocketTimeoutException e) {
            return 0;
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (out == null) {
            throw new IOException("Not connected");
        }
        out.write(data);
        out.flush();
    }

    @Override
    public String describe() {
        return host + ":" + port;
    }

    @Override
    public void close() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }
}
