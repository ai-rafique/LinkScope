package com.linkscope.modules.tcp;

import com.linkscope.core.AbstractService;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.TransportModule;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TCP dual mode: an independent {@link Client} (connect out, optional auto-reconnect)
 * and {@link Server} (listen, accept many clients, send to one or all) that run at the
 * same time. Each has its own status; {@link #statusProperty()} is the combined view
 * shown on the tab. As a {@link TransportModule}, start/send act on the client half.
 */
public final class TcpService implements TransportModule {
    public static final String TAG = "TCP";
    public static final String TAG_CLIENT = "TCP/CLIENT";
    public static final String TAG_SERVER = "TCP/SERVER";
    private static final int READ_BUFFER = 8192;

    private final Client client = new Client();
    private final Server server = new Server();
    private final ReadOnlyObjectWrapper<ModuleStatus> combined = new ReadOnlyObjectWrapper<>(ModuleStatus.DISCONNECTED);

    public TcpService() {
        combined.bind(Bindings.createObjectBinding(
                () -> combine(client.statusProperty().get(), server.statusProperty().get()),
                client.statusProperty(), server.statusProperty()));
    }

    static ModuleStatus combine(ModuleStatus a, ModuleStatus b) {
        if (a == ModuleStatus.ERROR || b == ModuleStatus.ERROR) {
            return ModuleStatus.ERROR;
        }
        if (a == ModuleStatus.CONNECTED || b == ModuleStatus.CONNECTED) {
            return ModuleStatus.CONNECTED;
        }
        if (a == ModuleStatus.CONNECTING || b == ModuleStatus.CONNECTING) {
            return ModuleStatus.CONNECTING;
        }
        return ModuleStatus.DISCONNECTED;
    }

    public Client client() {
        return client;
    }

    public Server server() {
        return server;
    }

    @Override
    public String moduleName() {
        return TAG;
    }

    @Override
    public ReadOnlyObjectProperty<ModuleStatus> statusProperty() {
        return combined.getReadOnlyProperty();
    }

    @Override
    public void start() {
        client.start();
    }

    /** Stops both halves. */
    @Override
    public void stop() {
        client.stop();
        server.stop();
    }

    @Override
    public void send(byte[] payload) {
        client.send(payload);
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    private static String remote(Socket s) {
        InetSocketAddress a = (InetSocketAddress) s.getRemoteSocketAddress();
        return a == null ? "?" : a.getAddress().getHostAddress() + ":" + a.getPort();
    }

    // ==================================================================================

    /** One accepted connection on the server side. Identified by its remote address. */
    public static final class ClientConn {
        private final Socket socket;
        private final String id;
        private final Object writeLock = new Object();

        ClientConn(Socket socket) {
            this.socket = socket;
            this.id = remote(socket);
        }

        public String id() {
            return id;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    // ==================================================================================

    /** Outbound connection with optional auto-reconnect. */
    public static final class Client extends AbstractService {
        private volatile String host = "127.0.0.1";
        private volatile int port = 5000;
        private volatile boolean autoReconnect;
        private volatile int connectTimeoutMs = 5000;
        private volatile int reconnectDelayMs = 2000;

        private volatile Socket socket;
        private volatile boolean running;
        private final Object writeLock = new Object();

        @Override
        public String moduleName() {
            return TAG_CLIENT;
        }

        public void setHost(String host) {
            this.host = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        }

        public void setPort(int port) {
            this.port = requirePort(port);
        }

        public void setAutoReconnect(boolean autoReconnect) {
            this.autoReconnect = autoReconnect;
        }

        public void setConnectTimeoutMs(int ms) {
            this.connectTimeoutMs = Math.max(100, ms);
        }

        public void setReconnectDelayMs(int ms) {
            this.reconnectDelayMs = Math.max(100, ms);
        }

        public boolean isConnected() {
            Socket s = socket;
            return s != null && s.isConnected() && !s.isClosed();
        }

        @Override
        public void start() {
            if (running) {
                return;
            }
            running = true;
            setStatus(ModuleStatus.CONNECTING);
            exec.submit(this::loop);
        }

        @Override
        public void stop() {
            running = false;
            closeQuietly(socket);
        }

        @Override
        public void send(byte[] payload) {
            Socket s = socket;
            if (s == null || !s.isConnected() || s.isClosed()) {
                logError("Not connected — connect first");
                return;
            }
            exec.submit(() -> {
                try {
                    synchronized (writeLock) {
                        OutputStream out = s.getOutputStream();
                        out.write(payload);
                        out.flush();
                    }
                    log.tx(TAG_CLIENT, payload, remote(s));
                } catch (IOException e) {
                    logError("Send failed", e);
                }
            });
        }

        private void loop() {
            try {
                while (running) {
                    setStatus(ModuleStatus.CONNECTING);
                    Socket s = new Socket();
                    boolean connected = false;
                    try {
                        s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
                        s.setTcpNoDelay(true);
                        socket = s;
                        connected = true;
                        logInfo("Connected to " + remote(s) + " (local port " + s.getLocalPort() + ")");
                        setStatus(ModuleStatus.CONNECTED);
                        readLoop(s);
                        if (running) {
                            logInfo("Connection closed by " + remote(s));
                        } else {
                            logInfo("Disconnected");
                        }
                    } catch (IOException e) {
                        if (running) {
                            logError(connected ? "Connection error" : "Connect to " + host + ":" + port + " failed", e);
                            setStatus(ModuleStatus.ERROR);
                        } else {
                            logInfo("Disconnected");
                        }
                    } finally {
                        socket = null;
                        closeQuietly(s);
                    }
                    if (!running || !autoReconnect) {
                        break;
                    }
                    logInfo("Reconnecting in " + reconnectDelayMs + " ms…");
                    if (!sleepWhileRunning(reconnectDelayMs)) {
                        break;
                    }
                }
            } finally {
                running = false;
                if (status() != ModuleStatus.ERROR) {
                    setStatus(ModuleStatus.DISCONNECTED);
                }
            }
        }

        private void readLoop(Socket s) throws IOException {
            InputStream in = s.getInputStream();
            byte[] buf = new byte[READ_BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) {
                log.rx(TAG_CLIENT, Arrays.copyOf(buf, n), remote(s));
            }
        }

        private boolean sleepWhileRunning(int ms) {
            long deadline = System.currentTimeMillis() + ms;
            while (running && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(Math.min(100, Math.max(1, deadline - System.currentTimeMillis())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return running;
        }
    }

    // ==================================================================================

    /** Listener accepting any number of clients, each served on its own virtual thread. */
    public static final class Server extends AbstractService {
        private volatile String bindAddress = "0.0.0.0";
        private volatile int port = 5000;

        private volatile ServerSocket serverSocket;
        private volatile boolean running;
        private volatile int localPort = -1;
        private final List<ClientConn> live = new CopyOnWriteArrayList<>();
        private final ObservableList<ClientConn> clients = FXCollections.observableArrayList();

        @Override
        public String moduleName() {
            return TAG_SERVER;
        }

        public void setBindAddress(String bindAddress) {
            this.bindAddress = bindAddress == null || bindAddress.isBlank() ? "0.0.0.0" : bindAddress.trim();
        }

        public void setPort(int port) {
            this.port = requirePort(port);
        }

        /** Port actually bound (useful when port is 0), or -1 when not listening. */
        public int localPort() {
            return localPort;
        }

        /** Connected clients, mutated on the FX thread — bind UI to this. */
        public ObservableList<ClientConn> clients() {
            return clients;
        }

        /** Thread-safe snapshot of connected clients. */
        public List<ClientConn> liveClients() {
            return List.copyOf(live);
        }

        @Override
        public void start() {
            if (running) {
                return;
            }
            running = true;
            setStatus(ModuleStatus.CONNECTING);
            exec.submit(this::acceptLoop);
        }

        @Override
        public void stop() {
            running = false;
            ServerSocket ss = serverSocket;
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                    // closing
                }
            }
            for (ClientConn c : live) {
                closeQuietly(c.socket);
            }
        }

        /** Broadcasts to every connected client. */
        @Override
        public void send(byte[] payload) {
            if (live.isEmpty()) {
                logError("No clients connected");
                return;
            }
            for (ClientConn c : live) {
                sendTo(c, payload);
            }
        }

        public void sendTo(ClientConn conn, byte[] payload) {
            exec.submit(() -> {
                try {
                    synchronized (conn.writeLock) {
                        OutputStream out = conn.socket.getOutputStream();
                        out.write(payload);
                        out.flush();
                    }
                    log.tx(TAG_SERVER, payload, conn.id());
                } catch (IOException e) {
                    logError("Send to " + conn.id() + " failed", e);
                }
            });
        }

        public void disconnect(ClientConn conn) {
            closeQuietly(conn.socket);
        }

        private void acceptLoop() {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                    ss.setReuseAddress(true);
                }
                ss.bind(new InetSocketAddress(bindAddress, port));
                serverSocket = ss;
                localPort = ss.getLocalPort();
                logInfo("Listening on " + bindAddress + ":" + localPort);
                setStatus(ModuleStatus.CONNECTED);
                while (running) {
                    Socket accepted = ss.accept();
                    accepted.setTcpNoDelay(true);
                    ClientConn conn = new ClientConn(accepted);
                    live.add(conn);
                    FxThread.run(() -> clients.add(conn));
                    logInfo("Client connected: " + conn.id() + " (" + live.size() + " total)");
                    exec.submit(() -> serve(conn));
                }
            } catch (IOException e) {
                if (running) {
                    logError(serverSocket == null ? "Could not listen on " + bindAddress + ":" + port : "Accept error", e);
                    setStatus(ModuleStatus.ERROR);
                }
            } finally {
                running = false;
                serverSocket = null;
                localPort = -1;
                if (ss != null) {
                    try {
                        ss.close();
                    } catch (IOException ignored) {
                        // closing
                    }
                }
                for (ClientConn c : live) {
                    closeQuietly(c.socket);
                }
                if (status() != ModuleStatus.ERROR) {
                    logInfo("Stopped listening");
                    setStatus(ModuleStatus.DISCONNECTED);
                }
            }
        }

        private void serve(ClientConn conn) {
            try {
                InputStream in = conn.socket.getInputStream();
                byte[] buf = new byte[READ_BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    log.rx(TAG_SERVER, Arrays.copyOf(buf, n), conn.id());
                }
            } catch (IOException e) {
                if (running && !conn.socket.isClosed()) {
                    logError("Client " + conn.id() + " error", e);
                }
            } finally {
                live.remove(conn);
                FxThread.run(() -> clients.remove(conn));
                closeQuietly(conn.socket);
                logInfo("Client disconnected: " + conn.id() + " (" + live.size() + " remaining)");
            }
        }
    }
}
