package com.linkscope.modules.capture;

import com.linkscope.core.PayloadCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Wireshark-flavoured display filter compiled to a predicate over {@link PacketRow}.
 * <pre>
 *   tcp            udp            arp           icmp         ipv6        (bare protocol / app names: mqtt, dns, ...)
 *   ip.addr == 192.168.1.5        ip.src == 10.0.0.1          ip.dst != 10.0.0.2     (prefix "192.168.1." also works)
 *   port == 502    srcport == 80  dstport &gt;= 1024             len &gt; 100
 *   proto == UDP   app == MQTT    contains "hello"           contains 0x0103      info contains "SYN"
 *   and / or / not / &amp;&amp; / || / ! / parentheses
 * </pre>
 * Bad syntax throws {@link IllegalArgumentException} with a readable message.
 */
public final class DisplayFilter {

    private final List<String> tokens;
    private int pos;

    private DisplayFilter(List<String> tokens) {
        this.tokens = tokens;
    }

    /** An empty or blank filter matches everything. */
    public static Predicate<PacketRow> compile(String text) {
        if (text == null || text.isBlank()) {
            return row -> true;
        }
        DisplayFilter p = new DisplayFilter(tokenize(text));
        Predicate<PacketRow> result = p.parseOr();
        if (p.pos != p.tokens.size()) {
            throw new IllegalArgumentException("Unexpected \"" + p.tokens.get(p.pos) + "\"");
        }
        return result;
    }

    // --- tokenizer ------------------------------------------------------------------

    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(' || c == ')') {
                out.add(String.valueOf(c));
                i++;
            } else if (c == '"' || c == '\'') {
                int end = text.indexOf(c, i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("Unterminated string");
                }
                out.add("\"" + text.substring(i + 1, end));
                i = end + 1;
            } else if ("=!<>&|".indexOf(c) >= 0) {
                int j = i + 1;
                while (j < text.length() && "=!<>&|".indexOf(text.charAt(j)) >= 0) {
                    j++;
                }
                out.add(text.substring(i, j));
                i = j;
            } else {
                int j = i;
                while (j < text.length() && !Character.isWhitespace(text.charAt(j)) && "()=!<>&|\"'".indexOf(text.charAt(j)) < 0) {
                    j++;
                }
                out.add(text.substring(i, j));
                i = j;
            }
        }
        return out;
    }

    // --- parser ---------------------------------------------------------------------

    private String peek() {
        return pos < tokens.size() ? tokens.get(pos) : null;
    }

    private boolean accept(String... words) {
        String t = peek();
        if (t == null) {
            return false;
        }
        for (String w : words) {
            if (t.equalsIgnoreCase(w)) {
                pos++;
                return true;
            }
        }
        return false;
    }

    private Predicate<PacketRow> parseOr() {
        Predicate<PacketRow> left = parseAnd();
        while (accept("or", "||")) {
            Predicate<PacketRow> right = parseAnd();
            Predicate<PacketRow> l = left;
            left = row -> l.test(row) || right.test(row);
        }
        return left;
    }

    private Predicate<PacketRow> parseAnd() {
        Predicate<PacketRow> left = parseNot();
        while (accept("and", "&&")) {
            Predicate<PacketRow> right = parseNot();
            Predicate<PacketRow> l = left;
            left = row -> l.test(row) && right.test(row);
        }
        return left;
    }

    private Predicate<PacketRow> parseNot() {
        if (accept("not", "!")) {
            Predicate<PacketRow> inner = parseNot();
            return row -> !inner.test(row);
        }
        return parsePrimary();
    }

    private Predicate<PacketRow> parsePrimary() {
        String t = peek();
        if (t == null) {
            throw new IllegalArgumentException("Filter ends unexpectedly");
        }
        if ("(".equals(t)) {
            pos++;
            Predicate<PacketRow> inner = parseOr();
            if (!accept(")")) {
                throw new IllegalArgumentException("Missing \")\"");
            }
            return inner;
        }
        pos++;
        String word = t.toLowerCase(Locale.ROOT);
        if (word.equals("contains")) {
            String value = takeValue();
            return containsAnywhere(value);
        }
        String next = peek();
        if (next != null && (isOperator(next) || next.equalsIgnoreCase("contains"))) {
            pos++;
            String value = takeValue();
            return comparison(word, next.toLowerCase(Locale.ROOT), value);
        }
        return bareWord(word);
    }

    private static boolean isOperator(String t) {
        return t.equals("==") || t.equals("=") || t.equals("!=") || t.equals("<") || t.equals(">") || t.equals("<=") || t.equals(">=");
    }

    private String takeValue() {
        String v = peek();
        if (v == null || isOperator(v) || v.equals("(") || v.equals(")")) {
            throw new IllegalArgumentException("Expected a value after \"" + tokens.get(pos - 1) + "\"");
        }
        pos++;
        return v.startsWith("\"") ? v.substring(1) : v;
    }

    /** {@code tcp}, {@code arp}, {@code mqtt} ... match the protocol or app column, case-insensitively. */
    private static Predicate<PacketRow> bareWord(String word) {
        String w = word.equals("ip") ? "ipv4" : word;
        return row -> {
            String proto = row.protocol().toLowerCase(Locale.ROOT);
            String app = row.app().toLowerCase(Locale.ROOT);
            if (w.equals("ipv4")) {
                return row.src().contains(".") && !proto.equals("arp");
            }
            if (w.equals("ipv6")) {
                return row.src().contains(":");
            }
            return proto.equals(w) || app.equals(w) || app.replace("/", "").equals(w)
                    || (w.equals("icmp") && proto.startsWith("icmp"));
        };
    }

    private static Predicate<PacketRow> containsAnywhere(String value) {
        byte[] hex = hexValue(value);
        if (hex != null) {
            return row -> row.payload() != null && indexOf(row.payload(), hex) >= 0;
        }
        String needle = value.toLowerCase(Locale.ROOT);
        return row -> row.info().toLowerCase(Locale.ROOT).contains(needle)
                || PacketDissector.payloadText(row).toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Predicate<PacketRow> comparison(String field, String op, String value) {
        switch (field) {
            case "ip.addr", "addr", "host" -> {
                return textCompare(op, value, row -> row.src(), row -> row.dst());
            }
            case "ip.src", "src" -> {
                return textCompare(op, value, row -> row.src());
            }
            case "ip.dst", "dst" -> {
                return textCompare(op, value, row -> row.dst());
            }
            case "port", "tcp.port", "udp.port" -> {
                int port = intValue(value, field);
                return numberCompare(op, port, row -> row.srcPort(), row -> row.dstPort());
            }
            case "srcport", "tcp.srcport", "udp.srcport", "src.port" -> {
                int port = intValue(value, field);
                return numberCompare(op, port, row -> row.srcPort());
            }
            case "dstport", "tcp.dstport", "udp.dstport", "dst.port" -> {
                int port = intValue(value, field);
                return numberCompare(op, port, row -> row.dstPort());
            }
            case "len", "length", "frame.len" -> {
                int len = intValue(value, field);
                return numberCompare(op, len, row -> row.length());
            }
            case "proto", "protocol" -> {
                return textCompare(op, value, row -> row.protocol());
            }
            case "app" -> {
                return textCompare(op, value, row -> row.app());
            }
            case "info" -> {
                return textCompare(op, value, row -> row.info());
            }
            case "payload", "data" -> {
                return textCompare(op, value, PacketDissector::payloadText);
            }
            default -> throw new IllegalArgumentException("Unknown field \"" + field
                    + "\" (try ip.addr, ip.src, ip.dst, port, srcport, dstport, len, proto, app, info)");
        }
    }

    @SafeVarargs
    private static Predicate<PacketRow> textCompare(String op, String value, java.util.function.Function<PacketRow, String>... getters) {
        String v = value.toLowerCase(Locale.ROOT);
        boolean prefix = v.endsWith(".") || v.endsWith(":");
        Predicate<String> match = switch (op) {
            case "==", "=" -> s -> prefix ? s.startsWith(v) : s.equals(v);
            case "!=" -> s -> prefix ? !s.startsWith(v) : !s.equals(v);
            case "contains" -> s -> s.contains(v);
            default -> throw new IllegalArgumentException("Operator \"" + op + "\" does not apply to text");
        };
        boolean negated = op.equals("!=");
        return row -> {
            boolean any = false;
            boolean all = true;
            for (var g : getters) {
                String s = g.apply(row) == null ? "" : g.apply(row).toLowerCase(Locale.ROOT);
                boolean m = match.test(s);
                any |= m;
                all &= m;
            }
            return negated ? all : any;
        };
    }

    @SafeVarargs
    private static Predicate<PacketRow> numberCompare(String op, int value, java.util.function.Function<PacketRow, Integer>... getters) {
        Predicate<Integer> match = switch (op) {
            case "==", "=" -> n -> n == value;
            case "!=" -> n -> n != value;
            case "<" -> n -> n < value;
            case ">" -> n -> n > value;
            case "<=" -> n -> n <= value;
            case ">=" -> n -> n >= value;
            default -> throw new IllegalArgumentException("Operator \"" + op + "\" does not apply to numbers");
        };
        boolean negated = op.equals("!=");
        return row -> {
            boolean any = false;
            boolean all = true;
            boolean present = false;
            for (var g : getters) {
                Integer n = g.apply(row);
                if (n == null) {
                    continue;
                }
                present = true;
                boolean m = match.test(n);
                any |= m;
                all &= m;
            }
            return present && (negated ? all : any);
        };
    }

    private static int intValue(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " needs a number, got \"" + value + "\"");
        }
    }

    private static byte[] hexValue(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        if (!v.startsWith("0x") || v.length() < 4) {
            return null;
        }
        try {
            return PayloadCodec.parseHex(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
