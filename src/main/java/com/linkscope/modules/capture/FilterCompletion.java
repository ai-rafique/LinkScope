package com.linkscope.modules.capture;

import com.linkscope.core.KnownPorts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Completion for the display filter language, Wireshark style: what is offered depends on
 * where the caret is in the expression. After nothing, {@code and}, {@code or}, {@code not}
 * or {@code (} it offers fields and bare protocol names; after a field it offers operators;
 * after an operator it offers values for that field, taken from the capture itself when the
 * field is an address, a port or an application; after a complete term it offers connectors.
 * Pure and deterministic; the popup only renders what this returns.
 */
public final class FilterCompletion {

    /** One row in the popup: what gets inserted, what is shown, and a short explanation. */
    public record Suggestion(String insert, String label, String detail) {
    }

    /** What the completion may replace: the token under the caret and where it starts. */
    public record Edit(int start, int end, String token) {
    }

    /** Values observed in the current capture, supplied lazily by the owner. */
    public interface Context {
        Set<String> addresses();

        Set<Integer> ports();

        Set<String> apps();

        Set<String> usbAddresses();

        Set<String> protocols();

        Context EMPTY = new Context() {
            public Set<String> addresses() {
                return Set.of();
            }

            public Set<Integer> ports() {
                return Set.of();
            }

            public Set<String> apps() {
                return Set.of();
            }

            public Set<String> usbAddresses() {
                return Set.of();
            }

            public Set<String> protocols() {
                return Set.of();
            }
        };
    }

    static final Map<String, String> FIELDS = new LinkedHashMap<>();
    static final Map<String, String> BARE = new LinkedHashMap<>();
    static final Map<String, String> OPERATORS = new LinkedHashMap<>();
    static final Map<String, String> CONNECTORS = new LinkedHashMap<>();

    static {
        FIELDS.put("ip.addr", "source or destination address; a trailing dot matches a prefix (192.168.1.)");
        FIELDS.put("ip.src", "source address");
        FIELDS.put("ip.dst", "destination address");
        FIELDS.put("port", "source or destination port");
        FIELDS.put("srcport", "source port");
        FIELDS.put("dstport", "destination port");
        FIELDS.put("len", "frame length in bytes");
        FIELDS.put("proto", "protocol column: TCP, UDP, ARP, ICMP, USB");
        FIELDS.put("app", "application guessed from the port: MQTT, Modbus, DNS, ...");
        FIELDS.put("info", "the info column text");
        FIELDS.put("payload", "payload as text");
        FIELDS.put("usb.addr", "bus.device of a USB record, e.g. 1.5");
        FIELDS.put("usb.bus", "USB bus number");
        FIELDS.put("usb.device", "USB device address");
        FIELDS.put("usb.endpoint", "endpoint number");
        FIELDS.put("usb.dir", "in (device to host) or out");
        FIELDS.put("usb.type", "control, bulk, interrupt or isoch");
        FIELDS.put("usb.function", "URB function name");
        FIELDS.put("usb.status", "success, stall_pid, ...");
        FIELDS.put("usb.stage", "control stage: setup, data, status, complete");
        FIELDS.put("usb.request", "decoded setup request, e.g. get_descriptor");
        FIELDS.put("usb.descriptor", "descriptor type in a request or response");
        FIELDS.put("usb.vid", "vendor id from a device descriptor (hex)");
        FIELDS.put("usb.pid", "product id from a device descriptor (hex)");

        BARE.put("tcp", "TCP segments");
        BARE.put("udp", "UDP datagrams");
        BARE.put("arp", "ARP requests and replies");
        BARE.put("icmp", "ICMP (v4 and v6)");
        BARE.put("ip", "IPv4 packets");
        BARE.put("ipv6", "IPv6 packets");
        BARE.put("dns", "DNS queries and responses");
        BARE.put("http", "HTTP by port");
        BARE.put("tls", "TLS by port");
        BARE.put("mqtt", "MQTT by port");
        BARE.put("nats", "NATS by port");
        BARE.put("kafka", "Kafka by port");
        BARE.put("modbus", "Modbus TCP by port");
        BARE.put("usb", "USB records");
        BARE.put("bulk", "USB bulk transfers");
        BARE.put("control", "USB control transfers");
        BARE.put("interrupt", "USB interrupt transfers");
        BARE.put("isoch", "USB isochronous transfers");
        BARE.put("in", "USB device-to-host");
        BARE.put("out", "USB host-to-device");
        BARE.put("contains", "text or 0xHEX anywhere in payload or info");
        BARE.put("not", "negate the next term");
        BARE.put("(", "group terms");

        OPERATORS.put("==", "equals (a trailing dot or colon matches a prefix)");
        OPERATORS.put("!=", "differs (for ip.addr: both ends differ)");
        OPERATORS.put("contains", "text or 0xHEX appears in the value");
        OPERATORS.put("<", "less than (numbers)");
        OPERATORS.put(">", "greater than (numbers)");
        OPERATORS.put("<=", "at most (numbers)");
        OPERATORS.put(">=", "at least (numbers)");

        CONNECTORS.put("and", "both terms must hold");
        CONNECTORS.put("or", "either term holds");
        CONNECTORS.put(")", "close the group");
    }

    private static final Set<String> NUMERIC = Set.of("port", "srcport", "dstport", "len", "usb.bus", "usb.device", "usb.endpoint");
    private static final Set<String> TEXT_ONLY = Set.of("ip.addr", "ip.src", "ip.dst", "proto", "app", "info", "payload", "usb.addr",
            "usb.dir", "usb.type", "usb.function", "usb.status", "usb.stage", "usb.request", "usb.descriptor", "usb.vid", "usb.pid");

    private FilterCompletion() {
    }

    /** The token under the caret (letters, digits, dots, colons, underscores, hex), and its span. */
    public static Edit edit(String text, int caret) {
        int c = Math.max(0, Math.min(caret, text.length()));
        int start = c;
        while (start > 0 && isWordChar(text.charAt(start - 1))) {
            start--;
        }
        int end = c;
        if (start == c) {
            // nothing word-like before the caret: a half-typed operator (=, !, <, >) is the token instead
            while (start > 0 && isOperatorChar(text.charAt(start - 1))) {
                start--;
            }
            while (end < text.length() && isOperatorChar(text.charAt(end))) {
                end++;
            }
        } else {
            while (end < text.length() && isWordChar(text.charAt(end))) {
                end++;
            }
        }
        return new Edit(start, end, text.substring(start, c));
    }

    private static boolean isOperatorChar(char ch) {
        return ch == '=' || ch == '!' || ch == '<' || ch == '>';
    }

    private static boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '.' || ch == ':' || ch == '_' || ch == '-';
    }

    /** Tokens before the edit, so the state can be read: last complete token and the one before it. */
    static List<String> tokensBefore(String text, int start) {
        List<String> out = new ArrayList<>();
        try {
            for (String t : DisplayFilter.tokenize(text.substring(0, start))) {
                out.add(t);
            }
        } catch (IllegalArgumentException e) {
            // an unterminated string: treat as being inside a value
            out.add("\"");
        }
        return out;
    }

    public static List<Suggestion> suggest(String text, int caret, Supplier<Context> context) {
        Edit edit = edit(text, caret);
        String partial = edit.token().toLowerCase(Locale.ROOT);
        List<String> before = tokensBefore(text, edit.start());
        String last = before.isEmpty() ? "" : before.get(before.size() - 1).toLowerCase(Locale.ROOT);
        String beforeLast = before.size() < 2 ? "" : before.get(before.size() - 2).toLowerCase(Locale.ROOT);

        List<Suggestion> out = new ArrayList<>();
        if (last.startsWith("\"")) {
            return out; // typing inside quotes
        }
        if (isOperator(last) && FIELDS.containsKey(beforeLast)) {
            values(beforeLast, last, partial, context.get(), out);
            return out;
        }
        if (last.equals("contains") && !FIELDS.containsKey(beforeLast)) {
            out.add(new Suggestion("\"", "\"text\"", "text to look for in payload or info"));
            out.add(new Suggestion("0x", "0xHEX", "bytes to look for in the payload"));
            return filter(out, partial);
        }
        if (FIELDS.containsKey(last)) {
            boolean numeric = NUMERIC.contains(last);
            OPERATORS.forEach((op, why) -> {
                if (numeric || !why.contains("numbers")) {
                    if (!(TEXT_ONLY.contains(last) && why.contains("numbers"))) {
                        out.add(new Suggestion(op + " ", op, why));
                    }
                }
            });
            return filter(out, partial);
        }
        boolean expectTerm = last.isEmpty() || last.equals("and") || last.equals("or") || last.equals("not") || last.equals("(")
                || last.equals("&&") || last.equals("||") || last.equals("!");
        if (!expectTerm && !isOperator(last)) {
            // a complete term sits before the caret: connectors (and a field in case they keep typing)
            CONNECTORS.forEach((c, why) -> out.add(new Suggestion(c + " ", c, why)));
            if (!partial.isEmpty()) {
                fieldsAndBare(partial, out);
            }
            return filter(out, partial);
        }
        fieldsAndBare(partial, out);
        return filter(out, partial);
    }

    private static boolean isOperator(String t) {
        return t.equals("==") || t.equals("=") || t.equals("!=") || t.equals("<") || t.equals(">") || t.equals("<=") || t.equals(">=")
                || t.equals("contains");
    }

    private static void fieldsAndBare(String partial, List<Suggestion> out) {
        boolean dotted = partial.contains(".");
        if (!dotted) {
            // whole families first when nothing specific is typed
            out.add(new Suggestion("ip.", "ip.", "ip.addr, ip.src, ip.dst"));
            out.add(new Suggestion("usb.", "usb.", "usb.addr, usb.endpoint, usb.dir, usb.type, ..."));
        }
        FIELDS.forEach((f, why) -> out.add(new Suggestion(f + " ", f, why)));
        BARE.forEach((b, why) -> out.add(new Suggestion(b.equals("(") ? "(" : b + " ", b, why)));
    }

    private static void values(String field, String op, String partial, Context ctx, List<Suggestion> out) {
        switch (field) {
            case "ip.addr", "ip.src", "ip.dst" -> {
                for (String a : sorted(ctx.addresses())) {
                    out.add(new Suggestion(a + " ", a, "seen in this capture"));
                }
                Set<String> prefixes = new LinkedHashSet<>();
                for (String a : ctx.addresses()) {
                    int dot = a.lastIndexOf('.');
                    if (dot > 0) {
                        prefixes.add(a.substring(0, dot + 1));
                    }
                }
                for (String p : sorted(prefixes)) {
                    out.add(new Suggestion(p + " ", p, "whole /24 (prefix match)"));
                }
            }
            case "port", "srcport", "dstport" -> {
                Map<Integer, String> seen = new TreeMap<>();
                for (int p : ctx.ports()) {
                    seen.put(p, "seen in this capture" + (KnownPorts.nameOf(p).isEmpty() ? "" : " · " + KnownPorts.nameOf(p)));
                }
                seen.forEach((p, why) -> out.add(new Suggestion(p + " ", String.valueOf(p), why)));
                Map<Integer, String> known = new TreeMap<>(KnownPorts.NAMES);
                known.forEach((p, why) -> {
                    if (!seen.containsKey(p)) {
                        out.add(new Suggestion(p + " ", String.valueOf(p), why));
                    }
                });
            }
            case "proto" -> {
                Set<String> protos = new LinkedHashSet<>(sorted(ctx.protocols()));
                protos.addAll(List.of("TCP", "UDP", "ARP", "ICMP", "ICMPv6", "USB"));
                protos.forEach(p -> out.add(new Suggestion(p + " ", p, "protocol column")));
            }
            case "app" -> {
                Set<String> apps = new LinkedHashSet<>(sorted(ctx.apps()));
                apps.addAll(new java.util.TreeSet<>(KnownPorts.NAMES.values()));
                apps.forEach(a -> out.add(new Suggestion(a + " ", a, ctx.apps().contains(a) ? "seen in this capture" : "by well-known port")));
            }
            case "usb.addr" -> sorted(ctx.usbAddresses()).forEach(a -> out.add(new Suggestion(a + " ", a, "bus.device seen in this capture")));
            case "usb.dir" -> {
                out.add(new Suggestion("in ", "in", "device to host"));
                out.add(new Suggestion("out ", "out", "host to device"));
            }
            case "usb.type" -> {
                for (String t : List.of("bulk", "control", "interrupt", "isoch")) {
                    out.add(new Suggestion(t + " ", t, "transfer type"));
                }
            }
            case "usb.stage" -> {
                for (String t : List.of("setup", "data", "status", "complete")) {
                    out.add(new Suggestion(t + " ", t, "control transfer stage"));
                }
            }
            case "usb.status" -> {
                for (String t : List.of("success", "stall_pid", "canceled", "dev_not_responding", "endpoint_halted", "timeout")) {
                    out.add(new Suggestion(t + " ", t, "USBD status"));
                }
            }
            case "usb.request" -> {
                for (String t : List.of("get_descriptor", "set_configuration", "set_interface", "get_status", "clear_feature", "set_feature",
                        "set_report_hid", "set_idle_hid", "set_line_coding_cdc", "set_control_line_state_cdc")) {
                    out.add(new Suggestion(t + " ", t, "decoded setup request"));
                }
            }
            case "usb.descriptor" -> {
                for (String t : List.of("device", "configuration", "string", "interface", "endpoint", "hid", "report")) {
                    out.add(new Suggestion(t + " ", t, "descriptor type"));
                }
            }
            case "len" -> {
                for (String t : List.of("60", "64", "128", "512", "1500")) {
                    out.add(new Suggestion(t + " ", t, "bytes"));
                }
            }
            default -> {
                // info, payload, usb.vid, usb.pid, usb.function: free text
            }
        }
        if (op.equals("contains") && (field.equals("info") || field.equals("payload"))) {
            out.add(0, new Suggestion("\"", "\"text\"", "quoted text"));
        }
        List<Suggestion> filtered = filter(out, partial);
        out.clear();
        out.addAll(filtered);
    }

    private static List<String> sorted(Set<String> values) {
        List<String> list = new ArrayList<>(values);
        list.sort(String::compareTo);
        return list;
    }

    private static final int MAX_ROWS = 64;

    /** Always a fresh list: callers may clear and refill the list they passed in. */
    private static List<Suggestion> filter(List<Suggestion> all, String partial) {
        if (partial.isEmpty()) {
            return new ArrayList<>(all.size() > MAX_ROWS ? all.subList(0, MAX_ROWS) : all);
        }
        List<Suggestion> starts = new ArrayList<>();
        List<Suggestion> contains = new ArrayList<>();
        boolean operatorPartial = isOperatorChar(partial.charAt(0));
        for (Suggestion s : all) {
            String l = s.label().toLowerCase(Locale.ROOT);
            if (l.startsWith(partial)) {
                starts.add(s);
            } else if (!operatorPartial && l.contains(partial)) {
                contains.add(s);
            }
        }
        starts.addAll(contains);
        return starts.size() > MAX_ROWS ? new ArrayList<>(starts.subList(0, MAX_ROWS)) : starts;
    }

    /** Applies a suggestion: replaces the token under the caret and returns the new text and caret. */
    public static String[] apply(String text, int caret, Suggestion s) {
        Edit e = edit(text, caret);
        String replaced = text.substring(0, e.start()) + s.insert() + text.substring(e.end());
        int newCaret = e.start() + s.insert().length();
        // avoid "ip.addr  ==" double spaces when the user had typed a space already
        return new String[] {replaced, String.valueOf(newCaret)};
    }
}
