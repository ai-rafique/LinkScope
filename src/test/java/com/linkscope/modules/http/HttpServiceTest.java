package com.linkscope.modules.http;

import com.linkscope.core.LogEntry;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleStatus;
import com.linkscope.modules.http.HttpRequestSpec.AuthType;
import com.linkscope.modules.http.HttpRequestSpec.BodyType;
import com.linkscope.modules.http.HttpRequestSpec.Header;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loopback tests against the JDK's built-in HttpServer; no network access needed. */
class HttpServiceTest {
    private static HttpServer server;
    private static String base;
    private final HttpService service = new HttpService();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Echoes method, selected headers and body as JSON.
        server.createContext("/echo", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String ct = exchange.getRequestHeaders().getFirst("Content-Type");
            String json = "{\"method\":\"" + exchange.getRequestMethod() + "\",\"auth\":\"" + (auth == null ? "" : auth)
                    + "\",\"contentType\":\"" + (ct == null ? "" : ct) + "\",\"body\":\""
                    + new String(body, StandardCharsets.UTF_8).replace("\"", "'") + "\"}";
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/echo");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void clearLog() {
        LogSink.get().clear();
    }

    private static HttpRequestSpec request(String method, String path, BodyType type, String body, AuthType auth,
                                           String user, String secret, boolean follow) {
        return new HttpRequestSpec(method, base + path, List.of(new Header("X-Test", "1")), type, body, auth, user,
                secret, 5_000, follow);
    }

    private HttpExchange run(HttpRequestSpec spec) throws Exception {
        return service.execute(spec).get(10, TimeUnit.SECONDS);
    }

    @Test
    void getEchoesAndLogsBothDirections() throws Exception {
        HttpExchange x = run(request("GET", "/echo", BodyType.NONE, "", AuthType.NONE, "", "", true));
        assertFalse(x.failed());
        assertEquals(200, x.status());
        assertEquals("200 OK", x.statusLine());
        assertTrue(x.isJson());
        assertTrue(x.bodyText().contains("\"method\":\"GET\""));
        assertEquals(ModuleStatus.CONNECTED, service.status());
        assertTrue(com.linkscope.TestSupport.snapshot(LogSink.get().entries()).stream().anyMatch(e -> e.kind() == LogEntry.Kind.TX && e.note().startsWith("GET ")));
        assertTrue(com.linkscope.TestSupport.snapshot(LogSink.get().entries()).stream().anyMatch(e -> e.kind() == LogEntry.Kind.RX && e.note().startsWith("200 OK")));
    }

    @Test
    void postJsonWithBearerAuth() throws Exception {
        HttpExchange x = run(request("POST", "/echo", BodyType.JSON, "{\"a\":1}", AuthType.BEARER, "", "secret-token", true));
        assertEquals(200, x.status());
        String body = x.bodyText();
        assertTrue(body.contains("\"method\":\"POST\""), body);
        assertTrue(body.contains("\"auth\":\"Bearer secret-token\""), body);
        assertTrue(body.contains("\"contentType\":\"application/json\""), body);
        assertTrue(body.contains("\"body\":\"{'a':1}\""), body);
    }

    @Test
    void formBodyWithBasicAuth() throws Exception {
        HttpExchange x = run(request("PUT", "/echo", BodyType.FORM, "k=v w\nz=1", AuthType.BASIC, "u", "p", true));
        String body = x.bodyText();
        assertTrue(body.contains("\"auth\":\"Basic dTpw\""), body);
        assertTrue(body.contains("application/x-www-form-urlencoded"), body);
        assertTrue(body.contains("\"body\":\"k=v+w&z=1\""), body);
    }

    @Test
    void notFoundIsAResponseNotAnError() throws Exception {
        HttpExchange x = run(request("DELETE", "/missing", BodyType.NONE, "", AuthType.NONE, "", "", true));
        assertFalse(x.failed());
        assertEquals(404, x.status());
        assertEquals("404 Not Found", x.statusLine());
        assertEquals(ModuleStatus.CONNECTED, service.status());
    }

    @Test
    void redirectFollowedOrShownDependingOnOption() throws Exception {
        assertEquals(200, run(request("GET", "/redirect", BodyType.NONE, "", AuthType.NONE, "", "", true)).status());
        HttpExchange raw = run(request("GET", "/redirect", BodyType.NONE, "", AuthType.NONE, "", "", false));
        assertEquals(302, raw.status());
        assertTrue(raw.formatHeaders().toLowerCase().contains("location: /echo"), raw.formatHeaders());
    }

    @Test
    void timeoutAndConnectionRefusedAreReportedAsErrors() throws Exception {
        HttpRequestSpec slow = new HttpRequestSpec("GET", base + "/slow", List.of(), BodyType.NONE, "", AuthType.NONE,
                "", "", 300, true);
        HttpExchange x = run(slow);
        assertTrue(x.failed());
        assertEquals("timed out", x.error());
        assertEquals(ModuleStatus.ERROR, service.status());

        HttpRequestSpec refused = new HttpRequestSpec("GET", "http://127.0.0.1:1/", List.of(), BodyType.NONE, "",
                AuthType.NONE, "", "", 2_000, true);
        HttpExchange r = run(refused);
        assertTrue(r.failed());
        assertNotNull(r.error());
        assertTrue(com.linkscope.TestSupport.snapshot(LogSink.get().entries()).stream().anyMatch(e -> e.kind() == LogEntry.Kind.ERROR));
    }

    @Test
    void invalidUrlFailsFastWithoutSending() throws Exception {
        HttpExchange x = run(new HttpRequestSpec("GET", "http://", List.of(), BodyType.NONE, "", AuthType.NONE,
                "", "", 1000, true));
        assertTrue(x.failed());
        assertTrue(x.error().contains("URL"), x.error());
        assertEquals(ModuleStatus.ERROR, service.status());
    }
}
