package com.linkscope.modules.websocket;

import com.linkscope.core.AbstractService;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleStatus;
import com.linkscope.modules.http.HttpRequestSpec.Header;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket tester on {@code java.net.http.WebSocket}: connect with optional headers,
 * send text or binary frames, ping with round-trip timing, and log every frame. Frames
 * that arrive fragmented are reassembled before being logged.
 */
public final class WebSocketService extends AbstractService {
    public static final String TAG = "WS";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final long CLOSE_TIMEOUT_MS = 3_000;

    private volatile String url = "ws://localhost:8080/";
    private volatile List<Header> headers = List.of();
    private volatile WebSocket socket;
    private volatile boolean closing;
    private volatile long pingSentNanos;
    private final ReadOnlyLongWrapper lastPingMs = new ReadOnlyLongWrapper(-1);
    private final StringBuilder textBuffer = new StringBuilder();
    private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    @Override
    public String moduleName() {
        return TAG;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url.trim();
    }

    public void setHeaders(List<Header> headers) {
        this.headers = headers == null ? List.of() : List.copyOf(headers);
    }

    /** Round-trip time of the last ping in ms, or -1 before any pong. */
    public ReadOnlyLongProperty lastPingMsProperty() {
        return lastPingMs.getReadOnlyProperty();
    }

    public boolean isOpen() {
        WebSocket s = socket;
        return s != null && !s.isOutputClosed();
    }

    static URI toUri(String url) {
        String u = url.trim();
        if (u.isEmpty()) {
            throw new IllegalArgumentException("URL is required");
        }
        if (u.startsWith("http://")) {
            u = "ws://" + u.substring(7);
        } else if (u.startsWith("https://")) {
            u = "wss://" + u.substring(8);
        } else if (!u.contains("://")) {
            u = "ws://" + u;
        }
        URI uri = URI.create(u);
        if (!"ws".equals(uri.getScheme()) && !"wss".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Scheme must be ws:// or wss://");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("URL needs a host");
        }
        return uri;
    }

    // --- lifecycle ------------------------------------------------------------------

    @Override
    public void start() {
        if (socket != null || status() == ModuleStatus.CONNECTING) {
            return;
        }
        closing = false;
        setStatus(ModuleStatus.CONNECTING);
        exec.submit(this::connect);
    }

    private void connect() {
        URI uri;
        try {
            uri = toUri(url);
        } catch (IllegalArgumentException e) {
            logError("Invalid URL: " + e.getMessage());
            setStatus(ModuleStatus.ERROR);
            return;
        }
        try {
            WebSocket.Builder b = client.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT);
            for (Header h : headers) {
                b.header(h.name(), h.value());
            }
            WebSocket s = b.buildAsync(uri, new Listener()).get(CONNECT_TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
            socket = s;
            logInfo("Connected to " + uri + (s.getSubprotocol().isEmpty() ? "" : " (subprotocol " + s.getSubprotocol() + ")"));
            setStatus(ModuleStatus.CONNECTED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            setStatus(ModuleStatus.DISCONNECTED);
        } catch (Exception e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            logError("Connect to " + uri + " failed", cause);
            setStatus(ModuleStatus.ERROR);
        }
    }

    @Override
    public void stop() {
        WebSocket s = socket;
        if (s == null) {
            if (status() != ModuleStatus.DISCONNECTED) {
                setStatus(ModuleStatus.DISCONNECTED);
            }
            return;
        }
        closing = true;
        exec.submit(() -> {
            try {
                if (!s.isOutputClosed()) {
                    s.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                s.abort();
            }
            // If the peer never answers the close handshake, give up after the timeout.
            exec.submit(() -> {
                try {
                    Thread.sleep(CLOSE_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (socket == s) {
                    s.abort();
                    finishClose("close timed out");
                }
            });
        });
    }

    private void finishClose(String reason) {
        if (socket == null) {
            return;
        }
        socket = null;
        logInfo("Disconnected (" + reason + ")");
        setStatus(ModuleStatus.DISCONNECTED);
    }

    // --- frames ---------------------------------------------------------------------

    /** Sends a text frame (payload decoded as UTF-8). */
    public void sendText(byte[] payload) {
        WebSocket s = socket;
        if (s == null) {
            logError("Not connected — connect first");
            return;
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        exec.submit(() -> {
            try {
                s.sendText(text, true).get(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                log.tx(TAG, payload, "text frame");
            } catch (Exception e) {
                logError("Send failed", unwrap(e));
            }
        });
    }

    /** Sends a binary frame. */
    public void sendBinary(byte[] payload) {
        WebSocket s = socket;
        if (s == null) {
            logError("Not connected — connect first");
            return;
        }
        exec.submit(() -> {
            try {
                s.sendBinary(ByteBuffer.wrap(payload), true).get(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                log.tx(TAG, payload, "binary frame");
            } catch (Exception e) {
                logError("Send failed", unwrap(e));
            }
        });
    }

    /** Default send is a text frame. */
    @Override
    public void send(byte[] payload) {
        sendText(payload);
    }

    /** Sends a ping carrying a timestamp; the pong handler reports the round trip. */
    public void ping() {
        WebSocket s = socket;
        if (s == null) {
            logError("Not connected — connect first");
            return;
        }
        exec.submit(() -> {
            try {
                pingSentNanos = System.nanoTime();
                ByteBuffer body = ByteBuffer.allocate(8).putLong(pingSentNanos).flip();
                s.sendPing(body).get(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                logInfo("Ping sent");
            } catch (Exception e) {
                logError("Ping failed", unwrap(e));
            }
        });
    }

    private static Throwable unwrap(Exception e) {
        Throwable t = e;
        while ((t instanceof java.util.concurrent.ExecutionException || t instanceof CompletionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                byte[] bytes = textBuffer.toString().getBytes(StandardCharsets.UTF_8);
                textBuffer.setLength(0);
                log.rx(TAG, bytes, "text frame");
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binaryBuffer.writeBytes(chunk);
            if (last) {
                byte[] bytes = binaryBuffer.toByteArray();
                binaryBuffer.reset();
                log.rx(TAG, bytes, "binary frame");
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
            logInfo("Ping received (" + message.remaining() + " B), pong sent");
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
            long sent = pingSentNanos;
            if (sent != 0) {
                long ms = (System.nanoTime() - sent) / 1_000_000;
                pingSentNanos = 0;
                FxThread.run(() -> lastPingMs.set(ms));
                logInfo("Pong received, round trip " + ms + " ms");
            } else {
                logInfo("Pong received");
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            String why = "code " + statusCode + (reason == null || reason.isEmpty() ? "" : ", " + reason)
                    + (closing ? "" : ", closed by peer");
            finishClose(why);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (!closing) {
                logError("Connection error", error);
                socket = null;
                setStatus(ModuleStatus.ERROR);
            } else {
                finishClose("aborted");
            }
        }
    }
}
