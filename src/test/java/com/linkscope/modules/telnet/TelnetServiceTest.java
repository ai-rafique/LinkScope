package com.linkscope.modules.telnet;

import com.linkscope.TestSupport;
import com.linkscope.core.LogEntry;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static com.linkscope.TestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelnetServiceTest {
    private static final int IAC = 255;
    private final TelnetService service = new TelnetService();
    private final StringBuilder console = new StringBuilder();

    @BeforeEach
    void setUp() {
        LogSink.get().clear();
        service.setOnOutput(console::append);
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    @Test
    void parserHandlesNegotiationSubnegotiationAndEscapedIac() {
        // DO ECHO (we answer WONT), WILL SGA (we answer DO), a subnegotiation to skip, text, IAC IAC literal
        byte[] chunk = b(IAC, 253, 1, IAC, 251, 3, IAC, 250, 24, 1, IAC, 240, 'h', 'i', IAC, IAC, '!', '\r', '\n');
        assertArrayEquals(b('h', 'i', IAC, '!', '\r', '\n'), service.feedBytes(chunk));
        assertEquals("hello\n", service.feed(b(IAC, 253, 1, 'h', 'e', 'l', 'l', 'o', '\r', '\n')));
        // split across reads: IAC at the end of one chunk, command in the next
        assertEquals("a", service.feed(b('a', IAC)));
        assertEquals("b", service.feed(b(252, 1, 'b')));
        assertTrue(TestSupport.snapshot(LogSink.get().entries()).stream()
                .anyMatch(e -> e.note() != null && e.note().contains("Server DO ECHO → WONT")));
        assertTrue(TestSupport.snapshot(LogSink.get().entries()).stream()
                .anyMatch(e -> e.note() != null && e.note().contains("Server WILL SUPPRESS-GO-AHEAD → DO")));
    }

    @Test
    void liveSessionNegotiatesEchoesAndEscapesOutgoingIac() throws IOException {
        CopyOnWriteArrayList<byte[]> received = new CopyOnWriteArrayList<>();
        AtomicReference<byte[]> negotiationReplies = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread fake = new Thread(() -> {
                try (Socket s = server.accept()) {
                    OutputStream out = s.getOutputStream();
                    InputStream in = s.getInputStream();
                    out.write(b(IAC, 253, 1, IAC, 251, 1, IAC, 251, 3));   // DO ECHO, WILL ECHO, WILL SGA
                    out.write("login: ".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    ByteArrayOutputStream acc = new ByteArrayOutputStream();
                    byte[] buf = new byte[256];
                    long deadline = System.currentTimeMillis() + 8000;
                    while (System.currentTimeMillis() < deadline) {
                        s.setSoTimeout(500);
                        try {
                            int n = in.read(buf);
                            if (n < 0) {
                                break;
                            }
                            acc.write(buf, 0, n);
                            received.add(acc.toByteArray());
                            if (acc.size() >= 9 + 6) {  // 3 replies (9 bytes) + "abÿÿ\r\n" (6 bytes)
                                out.write("ok\r\n".getBytes(StandardCharsets.US_ASCII));
                                out.flush();
                                break;
                            }
                        } catch (java.net.SocketTimeoutException ignored) {
                            // keep waiting
                        }
                    }
                    Thread.sleep(300);
                } catch (IOException | InterruptedException ignored) {
                    // test ends
                }
            });
            fake.setDaemon(true);
            fake.start();

            service.setTarget("127.0.0.1", server.getLocalPort());
            service.start();
            awaitTrue("connected", () -> service.status() == ModuleStatus.CONNECTED);
            awaitTrue("prompt shown", () -> console.toString().contains("login: "));
            assertTrue(!console.toString().contains("ÿ"), "negotiation must not reach the console");

            service.send(b('a', 'b', IAC, '\r', '\n'));   // one 0xFF byte in user data
            awaitTrue("server got data", Duration.ofSeconds(10), () -> received.stream().anyMatch(r -> r.length >= 15));
            byte[] all = received.get(received.size() - 1);
            byte[] replies = Arrays.copyOfRange(all, 0, 9);
            // WONT ECHO (to DO ECHO), DO ECHO (to WILL ECHO), DO SGA (to WILL SGA)
            assertArrayEquals(b(IAC, 252, 1, IAC, 253, 1, IAC, 253, 3), replies);
            assertArrayEquals(b('a', 'b', IAC, IAC, '\r', '\n'), Arrays.copyOfRange(all, 9, 15));
            awaitTrue("echo back", () -> console.toString().contains("ok"));
            assertTrue(TestSupport.snapshot(LogSink.get().entries()).stream().anyMatch(e -> e.kind() == LogEntry.Kind.TX));
        }
    }

    @Test
    void connectionRefusedIsAnError() {
        service.setTarget("127.0.0.1", 1);
        service.start();
        awaitTrue("error", () -> service.status() == ModuleStatus.ERROR);
    }
}
