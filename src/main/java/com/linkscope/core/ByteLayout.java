package com.linkscope.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a byte array into fields according to a typed layout string and decodes each field.
 * <p>
 * Layout grammar (whitespace or comma separated):
 * <ul>
 *   <li>{@code 2 3 5} - field widths in bytes, decoded every plausible way for their size</li>
 *   <li>{@code 4f 2i 2u 5s 3x} - width plus a type hint: f float, i signed, u unsigned, s string, x skip</li>
 *   <li>{@code *} - the rest of the data as one field ({@code *s} as text, {@code *x} skipped)</li>
 *   <li>{@code [2 4]*3} - a group repeated 3 times; {@code [2 4]*} repeats while data remains</li>
 * </ul>
 * Bytes past the layout are reported as an unassigned tail; a field the data cannot fill is
 * marked truncated.
 */
public final class ByteLayout {

    public enum Type {
        AUTO("auto"), UINT("uint"), INT("int"), FLOAT("float"), STRING("string"), SKIP("skip");

        public final String label;

        Type(String label) {
            this.label = label;
        }
    }

    /** One declared field; width -1 means "the rest". */
    public record FieldSpec(int width, Type type) {
        public boolean isRest() {
            return width < 0;
        }
    }

    /** One field cut from the data. {@code unassigned} marks the trailing bytes no spec claimed. */
    public record Field(int index, int offset, int length, FieldSpec spec, byte[] bytes, boolean truncated, boolean unassigned) {
        public String hex() {
            return PayloadCodec.toHex(bytes);
        }
    }

    /** Layout tree node: either a leaf field or a repeated group. */
    private record Node(FieldSpec field, List<Node> group, int repeat, boolean unbounded) {
        static Node leaf(FieldSpec f) {
            return new Node(f, null, 1, false);
        }

        static Node group(List<Node> children, int repeat, boolean unbounded) {
            return new Node(null, children, repeat, unbounded);
        }
    }

    private final List<Node> nodes;
    private final String source;

    private ByteLayout(List<Node> nodes, String source) {
        this.nodes = nodes;
        this.source = source;
    }

    public String source() {
        return source;
    }

    // --- parsing --------------------------------------------------------------------

    public static ByteLayout parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Layout is empty, e.g. 2 3 5 6 4");
        }
        List<String> tokens = tokenize(text);
        int[] pos = {0};
        List<Node> nodes = parseSequence(tokens, pos, false);
        if (pos[0] != tokens.size()) {
            throw new IllegalArgumentException("Unexpected \"" + tokens.get(pos[0]) + "\"");
        }
        return new ByteLayout(nodes, text.trim());
    }

    private static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '[' || c == ']') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add(String.valueOf(c));
            } else if (Character.isWhitespace(c) || c == ',' || c == ';') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static List<Node> parseSequence(List<String> tokens, int[] pos, boolean inGroup) {
        List<Node> out = new ArrayList<>();
        while (pos[0] < tokens.size()) {
            String t = tokens.get(pos[0]);
            if ("]".equals(t)) {
                if (!inGroup) {
                    throw new IllegalArgumentException("\"]\" without a matching \"[\"");
                }
                return out;
            }
            if ("[".equals(t)) {
                pos[0]++;
                List<Node> children = parseSequence(tokens, pos, true);
                if (pos[0] >= tokens.size() || !"]".equals(tokens.get(pos[0]))) {
                    throw new IllegalArgumentException("Missing \"]\"");
                }
                pos[0]++;
                // A "*", "*N" or "xN" right after "]" is the group's repeat count ("*" alone = while data remains).
                int repeat = 1;
                boolean unbounded = false;
                if (pos[0] < tokens.size() && tokens.get(pos[0]).matches("\\*\\d*|x\\d+")) {
                    String r = tokens.get(pos[0]).substring(1);
                    if (r.isEmpty()) {
                        unbounded = true;
                    } else {
                        repeat = parseCount(r, "Repeat count");
                    }
                    pos[0]++;
                }
                if (children.isEmpty()) {
                    throw new IllegalArgumentException("Empty group []");
                }
                out.add(Node.group(children, repeat, unbounded));
                continue;
            }
            out.add(Node.leaf(parseField(t)));
            pos[0]++;
        }
        if (inGroup) {
            throw new IllegalArgumentException("Missing \"]\"");
        }
        return out;
    }

    private static FieldSpec parseField(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        if (t.equals("*") || t.equals("*s") || t.equals("*x")) {
            return new FieldSpec(-1, t.equals("*s") ? Type.STRING : t.equals("*x") ? Type.SKIP : Type.AUTO);
        }
        if (!t.matches("\\d+[uifsx]?")) {
            throw new IllegalArgumentException("Bad field \"" + token + "\" (use a width like 4, or 4f 2i 2u 5s 3x, or *)");
        }
        char suffix = Character.isDigit(t.charAt(t.length() - 1)) ? 0 : t.charAt(t.length() - 1);
        int width = parseCount(suffix == 0 ? t : t.substring(0, t.length() - 1), "Field width");
        Type type = switch (suffix) {
            case 'u' -> Type.UINT;
            case 'i' -> Type.INT;
            case 'f' -> Type.FLOAT;
            case 's' -> Type.STRING;
            case 'x' -> Type.SKIP;
            default -> Type.AUTO;
        };
        if (type == Type.FLOAT && width != 4 && width != 8) {
            throw new IllegalArgumentException("Float fields must be 4 or 8 bytes (got " + width + "f)");
        }
        if ((type == Type.UINT || type == Type.INT) && width > 8) {
            throw new IllegalArgumentException("Integer fields are at most 8 bytes (got " + token + ")");
        }
        return new FieldSpec(width, type);
    }

    private static int parseCount(String s, String label) {
        try {
            int v = Integer.parseInt(s);
            if (v < 1 || v > 65_535) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a positive whole number, got \"" + s + "\"");
        }
    }

    // --- applying -------------------------------------------------------------------

    /** Cuts {@code data} into fields. Never throws: shortfalls are marked, leftovers become an unassigned tail. */
    public List<Field> apply(byte[] data) {
        List<Field> out = new ArrayList<>();
        int[] offset = {0};
        applyNodes(nodes, data, offset, out);
        if (offset[0] < data.length) {
            byte[] tail = slice(data, offset[0], data.length - offset[0]);
            out.add(new Field(out.size() + 1, offset[0], tail.length, new FieldSpec(tail.length, Type.AUTO), tail, false, true));
        }
        return out;
    }

    private static void applyNodes(List<Node> nodes, byte[] data, int[] offset, List<Field> out) {
        for (Node n : nodes) {
            if (n.field() != null) {
                applyField(n.field(), data, offset, out);
                continue;
            }
            if (n.unbounded()) {
                int guard = 0;
                while (offset[0] < data.length && guard++ < 100_000) {
                    int before = offset[0];
                    applyNodes(n.group(), data, offset, out);
                    if (offset[0] == before) {
                        break;
                    }
                }
            } else {
                for (int i = 0; i < n.repeat(); i++) {
                    applyNodes(n.group(), data, offset, out);
                }
            }
        }
    }

    private static void applyField(FieldSpec spec, byte[] data, int[] offset, List<Field> out) {
        int start = offset[0];
        int available = Math.max(0, data.length - start);
        int want = spec.isRest() ? available : spec.width();
        int take = Math.min(want, available);
        if (take == 0 && spec.isRest()) {
            return; // nothing left for "the rest"
        }
        byte[] bytes = slice(data, start, take);
        out.add(new Field(out.size() + 1, start, take, spec, bytes, take < want, false));
        offset[0] += take;
    }

    private static byte[] slice(byte[] data, int off, int len) {
        byte[] b = new byte[len];
        System.arraycopy(data, off, b, 0, len);
        return b;
    }

    // --- decoding -------------------------------------------------------------------

    /**
     * Compact one-line reading of a field for the table: for typed fields just that type,
     * for auto fields the interpretations that fit the width, preferred endianness first.
     */
    public static String summarize(Field f, boolean littleEndianFirst) {
        byte[] b = f.bytes();
        if (b.length == 0) {
            return f.truncated() ? "(no data)" : "";
        }
        Type type = f.spec().type();
        switch (type) {
            case SKIP:
                return "(skipped)";
            case STRING:
                return quote(new String(b, StandardCharsets.UTF_8));
            case UINT:
                return "u" + (b.length * 8) + " " + both(f, littleEndianFirst, false);
            case INT:
                return "i" + (b.length * 8) + " " + both(f, littleEndianFirst, true);
            case FLOAT:
                return (b.length == 4 ? "f32 " : "f64 ") + bothFloat(f, littleEndianFirst);
            default:
                break;
        }
        return switch (b.length) {
            case 1 -> "u8 " + (b[0] & 0xFF) + " · i8 " + b[0] + " · " + quote(PayloadCodec.toAscii(b))
                    + " · bits " + String.format("%8s", Integer.toBinaryString(b[0] & 0xFF)).replace(' ', '0');
            case 2 -> "u16 " + both(f, littleEndianFirst, false) + " · i16 " + both(f, littleEndianFirst, true);
            case 4 -> "u32 " + both(f, littleEndianFirst, false) + " · i32 " + both(f, littleEndianFirst, true)
                    + " · f32 " + bothFloat(f, littleEndianFirst);
            case 8 -> "u64 " + both(f, littleEndianFirst, false) + " · i64 " + both(f, littleEndianFirst, true)
                    + " · f64 " + bothFloat(f, littleEndianFirst);
            default -> quote(PayloadCodec.toAscii(b));
        };
    }

    /** Every interpretation of a field, for the detail table. */
    public static List<PayloadDecoder.Decoded> decode(Field f, boolean littleEndianFirst) {
        List<PayloadDecoder.Decoded> out = new ArrayList<>();
        byte[] b = f.bytes();
        String where = "offset " + f.offset() + " (0x" + Integer.toHexString(f.offset()) + "), " + b.length + " byte(s)"
                + (f.truncated() ? ", TRUNCATED" : "") + (f.unassigned() ? ", not covered by the layout" : "");
        out.add(new PayloadDecoder.Decoded("Field #" + f.index(), where));
        if (b.length == 0) {
            return out;
        }
        out.add(new PayloadDecoder.Decoded("Hex", PayloadCodec.toHex(b)));
        out.add(new PayloadDecoder.Decoded("ASCII (escaped)", PayloadCodec.toAscii(b)));
        out.add(new PayloadDecoder.Decoded("UTF-8", new String(b, StandardCharsets.UTF_8)));
        if (b.length == 1) {
            out.add(new PayloadDecoder.Decoded("Bits", String.format("%8s", Integer.toBinaryString(b[0] & 0xFF)).replace(' ', '0')));
            out.add(new PayloadDecoder.Decoded("uint8 / int8", (b[0] & 0xFF) + " / " + b[0]));
            out.add(new PayloadDecoder.Decoded("bool", (b[0] != 0) + ""));
        }
        if (b.length >= 2 && b.length <= 8) {
            String le = both(f, true, false).split(" / ")[0];
            String be = both(f, false, false).split(" / ")[0];
            out.add(new PayloadDecoder.Decoded("uint" + b.length * 8 + " LE / BE", le + " / " + be));
            String ile = both(f, true, true).split(" / ")[0];
            String ibe = both(f, false, true).split(" / ")[0];
            out.add(new PayloadDecoder.Decoded("int" + b.length * 8 + " LE / BE", ile + " / " + ibe));
        }
        if (b.length == 4) {
            out.add(new PayloadDecoder.Decoded("float32 LE / BE", bothFloat(f, true)));
            long ule = unsigned(b, true);
            long ube = unsigned(b, false);
            out.add(new PayloadDecoder.Decoded("unix time LE / BE", unixTime(ule) + " / " + unixTime(ube)));
        }
        if (b.length == 8) {
            out.add(new PayloadDecoder.Decoded("float64 LE / BE", bothFloat(f, true)));
        }
        return out;
    }

    private static String both(Field f, boolean littleFirst, boolean signed) {
        byte[] b = f.bytes();
        if (b.length > 8) {
            return "(too wide)";
        }
        String le = signed ? Long.toString(signed(b, true)) : Long.toUnsignedString(unsigned(b, true));
        String be = signed ? Long.toString(signed(b, false)) : Long.toUnsignedString(unsigned(b, false));
        return littleFirst ? le + " / " + be : be + " / " + le;
    }

    private static String bothFloat(Field f, boolean littleFirst) {
        byte[] b = f.bytes();
        String le;
        String be;
        if (b.length == 4) {
            le = Float.toString(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getFloat());
            be = Float.toString(ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getFloat());
        } else if (b.length == 8) {
            le = Double.toString(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getDouble());
            be = Double.toString(ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getDouble());
        } else {
            return "(needs 4 or 8 bytes)";
        }
        return littleFirst ? le + " / " + be : be + " / " + le;
    }

    /** Unsigned value of up to 8 bytes (8-byte values may wrap into the sign bit; callers print unsigned). */
    static long unsigned(byte[] b, boolean little) {
        long v = 0;
        for (int i = 0; i < b.length; i++) {
            int idx = little ? b.length - 1 - i : i;
            v = (v << 8) | (b[idx] & 0xFF);
        }
        return v;
    }

    static long signed(byte[] b, boolean little) {
        long v = unsigned(b, little);
        int bits = b.length * 8;
        if (bits < 64 && (v & (1L << (bits - 1))) != 0) {
            v -= 1L << bits;
        }
        return v;
    }

    private static String unixTime(long seconds) {
        return seconds > 4_102_444_800L ? "-" : Instant.ofEpochSecond(seconds).toString();
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }
}
