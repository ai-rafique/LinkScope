package com.linkscope.modules.http;

import com.linkscope.core.AbstractService;
import com.linkscope.core.ModuleStatus;
import com.linkscope.modules.http.HttpRequestSpec.Header;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * HTTP tester on {@code java.net.http.HttpClient}. Requests are stateless, so status means:
 * CONNECTING while a request is in flight, CONNECTED once the last request got a response
 * (any status code), ERROR when the last request failed, DISCONNECTED when idle/cancelled.
 */
public final class HttpService extends AbstractService {
    public static final String TAG = "HTTP";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient following = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final HttpClient direct = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private volatile HttpRequestSpec lastRequest;
    private volatile CompletableFuture<?> inFlight;

    @Override
    public String moduleName() {
        return TAG;
    }

    public HttpRequestSpec lastRequest() {
        return lastRequest;
    }

    /** Issues the request off the FX thread; the future completes (never exceptionally) with the exchange. */
    public CompletableFuture<HttpExchange> execute(HttpRequestSpec spec) {
        CompletableFuture<HttpExchange> result = new CompletableFuture<>();
        HttpRequest request;
        try {
            request = build(spec);
        } catch (IllegalArgumentException e) {
            logError("Bad request: " + e.getMessage());
            setStatus(ModuleStatus.ERROR);
            result.complete(new HttpExchange(LocalTime.now(), spec, 0, null, null, 0, e.getMessage()));
            return result;
        }
        lastRequest = spec;
        byte[] body = spec.bodyBytes();
        log.tx(TAG, body, spec.method() + " " + spec.url());
        setStatus(ModuleStatus.CONNECTING);

        HttpClient client = spec.followRedirects() ? following : direct;
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> send = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        inFlight = send;
        send.whenComplete((response, error) -> {
            long ms = (System.nanoTime() - started) / 1_000_000;
            inFlight = null;
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                String message = cause instanceof CancellationException ? "cancelled" : describe(cause);
                if (cause instanceof CancellationException) {
                    logInfo("Request cancelled");
                    setStatus(ModuleStatus.DISCONNECTED);
                } else {
                    logError(spec.method() + " " + spec.url() + " failed after " + ms + " ms: " + message);
                    setStatus(ModuleStatus.ERROR);
                }
                result.complete(new HttpExchange(LocalTime.now(), spec, 0, null, null, ms, message));
                return;
            }
            HttpExchange exchange = new HttpExchange(LocalTime.now(), spec, response.statusCode(),
                    response.headers().map(), response.body(), ms, null);
            log.rx(TAG, response.body(), exchange.statusLine() + " (" + ms + " ms)");
            setStatus(ModuleStatus.CONNECTED);
            result.complete(exchange);
        });
        return result;
    }

    static HttpRequest build(HttpRequestSpec spec) {
        URI uri;
        try {
            String url = spec.url().trim();
            if (!url.contains("://")) {
                url = "http://" + url;
            }
            uri = URI.create(url);
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("URL needs a host: " + spec.url());
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL: " + e.getMessage());
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(100, spec.timeoutMs())));
        for (Header h : spec.headers()) {
            b.header(h.name(), h.value());
        }
        String auth = spec.authorizationHeader();
        if (auth != null && !spec.hasHeader("Authorization")) {
            b.header("Authorization", auth);
        }
        HttpRequest.BodyPublisher publisher;
        if (spec.hasBody()) {
            publisher = HttpRequest.BodyPublishers.ofByteArray(spec.bodyBytes());
            if (!spec.hasHeader("Content-Type")) {
                b.header("Content-Type", spec.defaultContentType());
            }
        } else {
            publisher = HttpRequest.BodyPublishers.noBody();
        }
        b.method(spec.method(), publisher);
        return b.build();
    }

    private static String describe(Throwable t) {
        if (t instanceof java.net.http.HttpTimeoutException) {
            return "timed out";
        }
        if (t instanceof java.net.ConnectException) {
            return "connection refused";
        }
        if (t instanceof java.net.UnknownHostException) {
            return "unknown host " + t.getMessage();
        }
        if (t instanceof IOException && t.getMessage() != null) {
            return t.getMessage();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** Not meaningful for HTTP; provided for the TransportModule contract. */
    @Override
    public void start() {
        // requests are issued via execute()
    }

    /** Cancels the in-flight request, if any. */
    @Override
    public void stop() {
        CompletableFuture<?> f = inFlight;
        if (f != null) {
            f.cancel(true);
        } else if (status() == ModuleStatus.CONNECTING) {
            setStatus(ModuleStatus.DISCONNECTED);
        }
    }

    /** Re-sends the last request with this payload as a raw body. */
    @Override
    public void send(byte[] payload) {
        HttpRequestSpec last = lastRequest;
        if (last == null) {
            logError("No request to resend yet");
            return;
        }
        execute(new HttpRequestSpec(last.method(), last.url(), last.headers(), HttpRequestSpec.BodyType.RAW,
                new String(payload, java.nio.charset.StandardCharsets.UTF_8), last.auth(), last.username(),
                last.secret(), last.timeoutMs(), last.followRedirects()));
    }
}
