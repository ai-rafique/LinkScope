package com.linkscope.modules.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Everything needed to issue one HTTP request, in the shape the form captures it. Header
 * and form text use one {@code Name: value} / {@code key=value} pair per line, which is
 * easy to paste from docs and easy to save in a preset.
 */
public record HttpRequestSpec(
        String method,
        String url,
        List<Header> headers,
        BodyType bodyType,
        String body,
        AuthType auth,
        String username,
        String secret,
        int timeoutMs,
        boolean followRedirects) {

    public static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    public static final int DEFAULT_TIMEOUT_MS = 10_000;

    public record Header(String name, String value) {
    }

    public enum BodyType {
        NONE("None"), RAW("Raw"), JSON("JSON"), FORM("Form");

        public final String label;

        BodyType(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum AuthType {
        NONE("None"), BASIC("Basic"), BEARER("Bearer token");

        public final String label;

        AuthType(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public HttpRequestSpec {
        headers = headers == null ? List.of() : List.copyOf(headers);
        body = body == null ? "" : body;
        username = username == null ? "" : username;
        secret = secret == null ? "" : secret;
        method = method == null ? "GET" : method.toUpperCase();
    }

    /** Parses {@code Name: value} lines; blank lines and lines starting with # are ignored. */
    public static List<Header> parseHeaders(String text) {
        List<Header> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\R")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            int colon = l.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("Header line needs \"Name: value\": " + l);
            }
            out.add(new Header(l.substring(0, colon).trim(), l.substring(colon + 1).trim()));
        }
        return out;
    }

    public static String formatHeaders(List<Header> headers) {
        StringBuilder sb = new StringBuilder();
        for (Header h : headers) {
            sb.append(h.name()).append(": ").append(h.value()).append('\n');
        }
        return sb.toString();
    }

    /** {@code key=value} per line (or already {@code a=1&b=2}) to a URL-encoded form body. */
    public static String formEncode(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\R|&")) {
            String l = line.trim();
            if (l.isEmpty()) {
                continue;
            }
            int eq = l.indexOf('=');
            String key = eq < 0 ? l : l.substring(0, eq);
            String value = eq < 0 ? "" : l.substring(eq + 1);
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(key.trim(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public boolean hasBody() {
        return bodyType != BodyType.NONE && !"GET".equals(method) && !"HEAD".equals(method);
    }

    public byte[] bodyBytes() {
        if (!hasBody()) {
            return new byte[0];
        }
        String text = bodyType == BodyType.FORM ? formEncode(body) : body;
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Content-Type to send when the user did not set one explicitly, or null for no body. */
    public String defaultContentType() {
        if (!hasBody()) {
            return null;
        }
        return switch (bodyType) {
            case JSON -> "application/json";
            case FORM -> "application/x-www-form-urlencoded";
            default -> "text/plain; charset=utf-8";
        };
    }

    public boolean hasHeader(String name) {
        for (Header h : headers) {
            if (h.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Value for the Authorization header, or null when auth is NONE. */
    public String authorizationHeader() {
        return switch (auth) {
            case BASIC -> "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
            case BEARER -> "Bearer " + secret;
            default -> null;
        };
    }

    /** True when the secret is a literal value rather than a ${VAR} placeholder. */
    public boolean hasLiteralSecret() {
        return auth != AuthType.NONE && !secret.isEmpty() && !secret.trim().startsWith("${");
    }
}
