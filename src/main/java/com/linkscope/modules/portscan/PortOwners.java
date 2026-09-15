package com.linkscope.modules.portscan;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort TCP socket table via the platform's own tools: {@code netstat -ano} +
 * {@code tasklist} on Windows, {@code ss -tanp} on Linux, {@code lsof} on macOS. Yields
 * which local ports have a listener (with the owning process when known) and which are
 * held by non-listening sockets (established, time-wait, ...). Any failure yields an
 * empty snapshot; the scan still works, just without owners and "in use" detection.
 */
final class PortOwners {
    private static final long TOOL_TIMEOUT_S = 8;
    private static final Pattern SS_USERS = Pattern.compile("users:\\(\\(\"([^\"]+)\",pid=(\\d+)");

    /** Listening ports mapped to their owner label, plus every other local port in use. */
    record Snapshot(Map<Integer, String> listeningOwners, Set<Integer> activePorts) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Set.of());

        boolean isEmpty() {
            return listeningOwners.isEmpty() && activePorts.isEmpty();
        }
    }

    private PortOwners() {
    }

    static Snapshot snapshot() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return windows();
            }
            if (os.contains("mac")) {
                return mac();
            }
            return linux();
        } catch (IOException | InterruptedException | RuntimeException e) {
            return Snapshot.EMPTY;
        }
    }

    /** Listening ports only; convenience for callers that do not need the active set. */
    static Map<Integer, String> listening() {
        return snapshot().listeningOwners();
    }

    // --- Windows ----------------------------------------------------------------------

    private static Snapshot windows() throws IOException, InterruptedException {
        Map<Integer, String> pidByPort = new HashMap<>();
        Set<Integer> active = new HashSet<>();
        for (String line : run("netstat", "-ano", "-p", "tcp")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length < 4 || !"TCP".equalsIgnoreCase(cols[0])) {
                continue;
            }
            int port = portOf(cols[1]);
            if (port <= 0) {
                continue;
            }
            if ("LISTENING".equalsIgnoreCase(cols[3])) {
                if (cols.length >= 5) {
                    pidByPort.putIfAbsent(port, cols[4]);
                }
            } else {
                active.add(port);
            }
        }
        Map<String, String> nameByPid = new HashMap<>();
        if (!pidByPort.isEmpty()) {
            for (String line : run("tasklist", "/FO", "CSV", "/NH")) {
                if (line.startsWith("\"")) {
                    String[] cols = line.split("\",\"");
                    if (cols.length >= 2) {
                        nameByPid.put(cols[1].replace("\"", ""), cols[0].replace("\"", ""));
                    }
                }
            }
        }
        Map<Integer, String> owners = new HashMap<>();
        pidByPort.forEach((port, pid) -> {
            String name = nameByPid.get(pid);
            owners.put(port, name == null ? "pid " + pid : name + " (pid " + pid + ")");
        });
        return new Snapshot(owners, active);
    }

    // --- Linux ------------------------------------------------------------------------

    private static Snapshot linux() throws IOException, InterruptedException {
        Map<Integer, String> owners = new HashMap<>();
        Set<Integer> active = new HashSet<>();
        for (String line : run("ss", "-tanp")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length < 4 || "State".equals(cols[0])) {
                continue;
            }
            int port = portOf(cols[3]);
            if (port <= 0) {
                continue;
            }
            if ("LISTEN".equals(cols[0])) {
                Matcher m = SS_USERS.matcher(line);
                owners.putIfAbsent(port, m.find() ? m.group(1) + " (pid " + m.group(2) + ")" : "");
            } else {
                active.add(port);
            }
        }
        return new Snapshot(owners, active);
    }

    // --- macOS ------------------------------------------------------------------------

    private static Snapshot mac() throws IOException, InterruptedException {
        Map<Integer, String> owners = new HashMap<>();
        Set<Integer> active = new HashSet<>();
        for (String line : run("lsof", "-nP", "-iTCP")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length < 9 || "COMMAND".equals(cols[0])) {
                continue;
            }
            int port = portOf(cols[8]);
            if (port <= 0) {
                continue;
            }
            if (line.contains("(LISTEN)")) {
                owners.putIfAbsent(port, cols[0] + " (pid " + cols[1] + ")");
            } else {
                active.add(port);
            }
        }
        return new Snapshot(owners, active);
    }

    // --- helpers ----------------------------------------------------------------------

    /** Local port from "0.0.0.0:135", "[::]:22", "*:22", "127.0.0.1:8080 (LISTEN)" or "a:1->b:2". */
    static int portOf(String endpoint) {
        String e = endpoint;
        int arrow = e.indexOf("->");
        if (arrow > 0) {
            e = e.substring(0, arrow);
        }
        int space = e.indexOf(' ');
        if (space > 0) {
            e = e.substring(0, space);
        }
        int colon = e.lastIndexOf(':');
        if (colon < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(e.substring(colon + 1));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static List<String> run(String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), Charset.defaultCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        if (!p.waitFor(TOOL_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(command[0] + " timed out");
        }
        return lines;
    }
}
