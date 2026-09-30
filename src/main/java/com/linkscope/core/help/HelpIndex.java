package com.linkscope.core.help;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic place-finder over the feature catalogue. Every entry becomes a small
 * document (title weighted 3, synonyms 2, path and description 1); queries are tokenised,
 * lightly stemmed and expanded with a fixed synonym table, then ranked with BM25 plus a
 * prefix boost (so "rum" already finds rumble while typing), a one-edit fuzzy boost for
 * typos, and a phrase boost when the whole query appears in a title. No model, no
 * network: the same query always gives the same order.
 */
public final class HelpIndex {
    private static final String RESOURCE = "/com/linkscope/help/catalogue.json";
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /** Query-side expansions: a word the user might type mapped to catalogue vocabulary. */
    static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("com", "serial port"), Map.entry("comport", "serial port"), Map.entry("uart", "serial"),
            Map.entry("rs232", "serial"), Map.entry("rs485", "serial modbus"), Map.entry("baud", "serial"),
            Map.entry("sniff", "capture packet"), Map.entry("sniffer", "capture packet"), Map.entry("wireshark", "capture pcap"),
            Map.entry("tcpdump", "capture pcap"), Map.entry("packet", "capture"), Map.entry("traffic", "capture"),
            Map.entry("gamepad", "controller hid"), Map.entry("joystick", "controller hid"), Map.entry("xbox", "controller rumble hid"),
            Map.entry("vibrate", "rumble"), Map.entry("vibration", "rumble"), Map.entry("vibrator", "rumble"), Map.entry("shake", "rumble"),
            Map.entry("ping", "scan host"), Map.entry("ip", "address host"), Map.entry("ips", "address host"), Map.entry("lan", "network"),
            Map.entry("subnet", "network"), Map.entry("wifi", "interface adapter"), Map.entry("ethernet", "interface adapter"),
            Map.entry("nic", "interface adapter"), Map.entry("socket", "tcp udp"), Map.entry("broker", "nats kafka mqtt"),
            Map.entry("publish", "broker topic"), Map.entry("subscribe", "broker topic"), Map.entry("crc", "checksum modbus"),
            Map.entry("checksum", "crc"), Map.entry("rtu", "modbus serial"), Map.entry("plc", "modbus"), Map.entry("register", "modbus"),
            Map.entry("tunnel", "forward"), Map.entry("forward", "tunnel"), Map.entry("file", "sftp"), Map.entry("upload", "sftp"),
            Map.entry("download", "sftp"), Map.entry("bytes", "decoder hex"), Map.entry("hex", "decoder"), Map.entry("parse", "decoder layout"),
            Map.entry("struct", "decoder layout"), Map.entry("field", "decoder layout"), Map.entry("occupied", "taken used"),
            Map.entry("busy", "taken used"), Map.entry("conflict", "taken need"), Map.entry("clash", "taken need"),
            Map.entry("free", "available"), Map.entry("dark", "theme"), Map.entry("light", "theme"), Map.entry("mode", "theme"),
            Map.entry("env", "variable placeholder"), Map.entry("secret", "password token placeholder"),
            Map.entry("password", "secret placeholder"), Map.entry("token", "secret placeholder"), Map.entry("shortcut", "keyboard"),
            Map.entry("hotkey", "keyboard"), Map.entry("keys", "keyboard"), Map.entry("csv", "export log"), Map.entry("export", "save"),
            Map.entry("rest", "http"), Map.entry("api", "http"), Map.entry("get", "http"), Map.entry("post", "http"), Map.entry("json", "http"),
            Map.entry("ws", "websocket"), Map.entry("shell", "ssh"), Map.entry("terminal", "ssh telnet"), Map.entry("login", "ssh"),
            Map.entry("usb", "usb"), Map.entry("ftdi", "usb serial stream"), Map.entry("stick", "usb"), Map.entry("dongle", "usb"),
            Map.entry("cable", "interface"), Map.entry("mac", "address"), Map.entry("gateway", "router"), Map.entry("router", "gateway"),
            Map.entry("dead", "never pressed"), Map.entry("broken", "never pressed"), Map.entry("test", "check"),
            Map.entry("diagnose", "check"), Map.entry("who", "process owner"), Map.entry("which", "process"),
            Map.entry("group", "multicast"), Map.entry("mcast", "multicast"), Map.entry("broadcast", "udp"));

    /** A ranked result. */
    public record Hit(HelpEntry entry, double score) {
    }

    private final List<HelpEntry> entries;
    private final List<Map<String, Integer>> docs = new ArrayList<>();
    private final List<Integer> lengths = new ArrayList<>();
    private final Map<String, Integer> docFrequency = new HashMap<>();
    private final Map<String, Double> idf = new HashMap<>();
    private double averageLength;

    public HelpIndex(List<HelpEntry> entries) {
        this.entries = List.copyOf(entries);
        long total = 0;
        for (HelpEntry e : this.entries) {
            Map<String, Integer> tf = new LinkedHashMap<>();
            add(tf, e.title(), 3);
            add(tf, e.synonyms(), 2);
            add(tf, e.path(), 1);
            add(tf, e.about(), 1);
            docs.add(tf);
            int len = tf.values().stream().mapToInt(Integer::intValue).sum();
            lengths.add(len);
            total += len;
            for (String t : tf.keySet()) {
                docFrequency.merge(t, 1, Integer::sum);
            }
        }
        averageLength = this.entries.isEmpty() ? 1 : (double) total / this.entries.size();
        int n = this.entries.size();
        docFrequency.forEach((t, df) -> idf.put(t, Math.log(1 + (n - df + 0.5) / (df + 0.5))));
    }

    /** Loads the catalogue shipped in the jar. */
    public static HelpIndex load() {
        try (InputStream in = HelpIndex.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("catalogue missing: " + RESOURCE);
            }
            List<HelpEntry> list = new ObjectMapper().readValue(in, new TypeReference<List<HelpEntry>>() { });
            return new HelpIndex(list);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<HelpEntry> entries() {
        return entries;
    }

    public HelpEntry byId(String id) {
        for (HelpEntry e : entries) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    private static void add(Map<String, Integer> tf, String text, int weight) {
        for (String t : tokenize(text)) {
            tf.merge(t, weight, Integer::sum);
        }
    }

    // --- text -------------------------------------------------------------------------------

    /** Lower-case alphanumeric tokens with a light stem: plurals and -ing dropped from longer words. */
    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.isEmpty()) {
                continue;
            }
            out.add(stem(raw));
        }
        return out;
    }

    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) {
            return w.substring(0, w.length() - 3);
        }
        if (w.length() > 5 && w.endsWith("ed") && !w.endsWith("eed")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 4 && (w.endsWith("sses") || w.endsWith("xes") || w.endsWith("zes") || w.endsWith("ches") || w.endsWith("shes"))) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) {
            w = w.substring(0, w.length() - 1);
        }
        // "capture" and "captured" must meet: drop a trailing e from longer words on both sides
        if (w.length() > 4 && w.endsWith("e")) {
            w = w.substring(0, w.length() - 1);
        }
        return w;
    }

    /** Query tokens plus their synonym expansions (originals first, so exact words weigh most). */
    static List<String> expand(String query) {
        List<String> base = tokenize(query);
        List<String> out = new ArrayList<>(base);
        Set<String> seen = new HashSet<>(base);
        for (String raw : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            String syn = SYNONYMS.get(raw);
            if (syn == null) {
                syn = SYNONYMS.get(stem(raw));
            }
            if (syn != null) {
                for (String t : tokenize(syn)) {
                    if (seen.add(t)) {
                        out.add(t);
                    }
                }
            }
        }
        return out;
    }

    // --- search -----------------------------------------------------------------------------

    public List<Hit> search(String query, int limit) {
        List<String> terms = expand(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<String> original = tokenize(query);
        String phrase = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            double score = score(i, terms, original);
            HelpEntry e = entries.get(i);
            String title = e.title().toLowerCase(Locale.ROOT);
            if (!phrase.isEmpty() && phrase.length() >= 3) {
                if (title.equals(phrase)) {
                    score += 3;
                } else if (title.contains(phrase)) {
                    score += 2;
                } else if (e.path().toLowerCase(Locale.ROOT).contains(phrase)) {
                    score += 1;
                }
            }
            if (score > 0) {
                hits.add(new Hit(e, score));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(h -> h.entry().title()));
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    private double score(int doc, List<String> terms, List<String> original) {
        Map<String, Integer> tf = docs.get(doc);
        double dl = lengths.get(doc);
        double score = 0;
        for (int k = 0; k < terms.size(); k++) {
            String t = terms.get(k);
            boolean isOriginal = k < original.size();
            double weight = isOriginal ? 1.0 : 0.6;
            Integer f = tf.get(t);
            if (f != null) {
                double termIdf = idf.getOrDefault(t, 0.0);
                score += weight * termIdf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * dl / averageLength));
                continue;
            }
            if (!isOriginal) {
                continue;
            }
            // no exact hit: prefix while typing, or one typo
            double best = 0;
            for (Map.Entry<String, Integer> d : tf.entrySet()) {
                String dt = d.getKey();
                if (t.length() >= 3 && dt.startsWith(t)) {
                    best = Math.max(best, 0.6 * idf.getOrDefault(dt, 0.0));
                } else if (t.length() >= 5 && Math.abs(dt.length() - t.length()) <= 1 && editDistanceAtMostOne(t, dt)) {
                    best = Math.max(best, 0.4 * idf.getOrDefault(dt, 0.0));
                }
            }
            score += best;
        }
        return score;
    }

    /** One insertion, deletion, substitution or adjacent transposition away (the usual typos). */
    static boolean editDistanceAtMostOne(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (Math.abs(a.length() - b.length()) > 1) {
            return false;
        }
        int i = 0;
        int j = 0;
        int edits = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (++edits > 1) {
                return false;
            }
            if (a.length() > b.length()) {
                i++;
            } else if (a.length() < b.length()) {
                j++;
            } else if (i + 1 < a.length() && j + 1 < b.length() && a.charAt(i) == b.charAt(j + 1) && a.charAt(i + 1) == b.charAt(j)) {
                i += 2;
                j += 2;
            } else {
                i++;
                j++;
            }
        }
        return edits + (a.length() - i) + (b.length() - j) <= 1;
    }

    /** All entries in catalogue order, for the map view. */
    public List<HelpEntry> all() {
        return Collections.unmodifiableList(entries);
    }
}
