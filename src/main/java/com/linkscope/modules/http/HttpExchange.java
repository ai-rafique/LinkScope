package com.linkscope.modules.http;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** One completed request/response pair (or a failed attempt when {@code error} is set). */
public record HttpExchange(
        LocalTime at,
        HttpRequestSpec request,
        int status,
        Map<String, List<String>> responseHeaders,
        byte[] body,
        long durationMs,
        String error) {

    public boolean failed() {
        return error != null;
    }

    public String statusLine() {
        return failed() ? "Failed: " + error : status + " " + reasonPhrase(status);
    }

    public String bodyText() {
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    public String contentType() {
        if (responseHeaders == null) {
            return "";
        }
        for (Map.Entry<String, List<String>> e : responseHeaders.entrySet()) {
            if ("content-type".equalsIgnoreCase(e.getKey()) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return "";
    }

    public boolean isJson() {
        return contentType().toLowerCase().contains("json");
    }

    public String formatHeaders() {
        if (responseHeaders == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        responseHeaders.forEach((name, values) -> {
            if (name == null) {
                return; // HttpClient puts the status line under a null key on some versions
            }
            for (String v : values) {
                sb.append(name).append(": ").append(v).append('\n');
            }
        });
        return sb.toString();
    }

    public static String reasonPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 415 -> "Unsupported Media Type";
            case 422 -> "Unprocessable Content";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> status >= 500 ? "Server Error" : status >= 400 ? "Client Error"
                    : status >= 300 ? "Redirect" : status >= 200 ? "Success" : "Informational";
        };
    }
}
