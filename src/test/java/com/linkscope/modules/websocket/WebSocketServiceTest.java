package com.linkscope.modules.websocket;

import com.linkscope.core.LogEntry;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleStatus;
import com.linkscope.modules.http.HttpRequestSpec.Header;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.linkscope.TestSupport.awaitTrue;
import static com.linkscope.TestSupport.logged;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loopback tests against a Java-WebSocket echo server (test dependency only). */
class WebSocketServiceTest {
    private static EchoServer server;
    private static String url;
    private final WebSocketService service = new WebSocketService();

    /** Echoes text and binary frames and records the last handshake header we care about. */
    static final class EchoServer extends WebSocketServer {
        final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
        final CountDownLatch started = new CountDownLatch(1);

        EchoServer() {
            super(new InetSocketAddress("127.0.0.1", 0));
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            lastAuthHeader.set(handshake.getFieldValue("Authorization"));
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            conn.send("echo:" + message);
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            conn.send(message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
        }

        @Override
        public void onStart() {
            started.countDown();
        }
    }

    @BeforeAll
    static void startServer() throws InterruptedException {
        server = new EchoServer();
        server.start();
        assertTrue(server.started.await(10, TimeUnit.SECONDS), "echo server start");
        url = "ws://127.0.0.1:" + server.getPort() + "/echo";
    }

    @AfterAll
    static void stopServer() throws InterruptedException {
        server.stop(1000);
    }

    @BeforeEach
    void clearLog() {
        LogSink.get().clear();
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    private void connect() {
        service.setUrl(url);
        service.start();
        awaitTrue("connected", () -> service.status() == ModuleStatus.CONNECTED);
    }

    @Test
    void textAndBinaryFramesRoundTrip() {
        connect();
        byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
        service.sendText(hello);
        awaitTrue("TX text", () -> logged(WebSocketService.TAG, LogEntry.Kind.TX, hello));
        awaitTrue("RX echo", () -> logged(WebSocketService.TAG, LogEntry.Kind.RX, "echo:hello".getBytes(StandardCharsets.UTF_8)));

        byte[] raw = {0, 1, 2, (byte) 0xff};
        service.sendBinary(raw);
        awaitTrue("RX binary echo", () -> LogSink.get().entries().stream().anyMatch(e -> e.kind() == LogEntry.Kind.RX
                && "binary frame".equals(e.note()) && java.util.Arrays.equals(e.payload(), raw)));
    }

    @Test
    void pingGetsPongWithRoundTrip() {
        connect();
        service.ping();
        awaitTrue("pong logged", () -> LogSink.get().entries().stream()
                .anyMatch(e -> e.kind() == LogEntry.Kind.INFO && e.note().startsWith("Pong received, round trip")));
        assertTrue(service.lastPingMsProperty().get() >= 0);
    }

    @Test
    void handshakeHeadersAreSentAndCloseIsClean() {
        service.setHeaders(java.util.List.of(new Header("Authorization", "Bearer abc")));
        connect();
        awaitTrue("server saw header", () -> "Bearer abc".equals(server.lastAuthHeader.get()));
        service.stop();
        awaitTrue("disconnected", () -> service.status() == ModuleStatus.DISCONNECTED);
        assertTrue(LogSink.get().entries().stream().anyMatch(e -> e.kind() == LogEntry.Kind.INFO
                && e.note().startsWith("Disconnected (code 1000")));
    }

    @Test
    void connectionRefusedIsAnError() {
        service.setUrl("ws://127.0.0.1:1/");
        service.start();
        awaitTrue("error", () -> service.status() == ModuleStatus.ERROR);
        assertTrue(LogSink.get().entries().stream().anyMatch(e -> e.kind() == LogEntry.Kind.ERROR));
    }

    @Test
    void urlNormalization() {
        assertEquals("ws://h:1/p", WebSocketService.toUri("h:1/p").toString());
        assertEquals("wss://h/p", WebSocketService.toUri("https://h/p").toString());
        assertThrows(IllegalArgumentException.class, () -> WebSocketService.toUri("ftp://h"));
        assertThrows(IllegalArgumentException.class, () -> WebSocketService.toUri(""));
    }
}
