package com.linkscope.core.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Configurable 16-bit CRC: built from parameters (polynomial, init, reflection, xor-out)
 * or from a pasted lookup table, with a selectable byte order on the wire. Immutable.
 */
public final class Crc16 {

    /** Order of the two CRC bytes in a frame: Modbus RTU sends the low byte first. */
    public enum ByteOrder {
        LOW_FIRST("Low byte first (Modbus)"), HIGH_FIRST("High byte first");

        public final String label;

        ByteOrder(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Algorithm parameters in the usual "Rocksoft" form. */
    public record Params(String name, int poly, int init, boolean reflected, int xorOut) {
        @Override
        public String toString() {
            return name;
        }
    }

    public static final Params MODBUS = new Params("CRC-16/MODBUS", 0x8005, 0xFFFF, true, 0x0000);
    public static final Params ARC = new Params("CRC-16/ARC (IBM)", 0x8005, 0x0000, true, 0x0000);
    public static final Params CCITT_FALSE = new Params("CRC-16/CCITT-FALSE", 0x1021, 0xFFFF, false, 0x0000);
    public static final Params XMODEM = new Params("CRC-16/XMODEM", 0x1021, 0x0000, false, 0x0000);
    public static final Params KERMIT = new Params("CRC-16/KERMIT", 0x1021, 0x0000, true, 0x0000);
    public static final Params USB = new Params("CRC-16/USB", 0x8005, 0xFFFF, true, 0xFFFF);
    public static final List<Params> PRESETS = List.of(MODBUS, ARC, CCITT_FALSE, XMODEM, KERMIT, USB);

    /** The standard Modbus CRC, low byte first. */
    public static final Crc16 STANDARD_MODBUS = of(MODBUS, ByteOrder.LOW_FIRST);

    private final int[] table;
    private final boolean reflected;
    private final int init;
    private final int xorOut;
    private final ByteOrder order;
    private final String description;

    private Crc16(int[] table, boolean reflected, int init, int xorOut, ByteOrder order, String description) {
        this.table = table.clone();
        this.reflected = reflected;
        this.init = init & 0xFFFF;
        this.xorOut = xorOut & 0xFFFF;
        this.order = order;
        this.description = description;
    }

    // --- construction ---------------------------------------------------------------

    public static Crc16 of(Params p, ByteOrder order) {
        return new Crc16(buildTable(p.poly(), p.reflected()), p.reflected(), p.init(), p.xorOut(), order,
                p.name() + " (poly 0x" + hex4(p.poly()) + ", init 0x" + hex4(p.init()) + (p.reflected() ? ", reflected" : "")
                        + (p.xorOut() != 0 ? ", xorout 0x" + hex4(p.xorOut()) : "") + ")");
    }

    public static Crc16 ofParameters(int poly, int init, boolean reflected, int xorOut, ByteOrder order) {
        return of(new Params("Custom", poly, init, reflected, xorOut), order);
    }

    /** A pasted 256-entry 16-bit table, used with the given init/xor-out and shift direction. */
    public static Crc16 fromTable(int[] table256, boolean reflected, int init, int xorOut, ByteOrder order) {
        if (table256.length != 256) {
            throw new IllegalArgumentException("A CRC table needs exactly 256 entries, got " + table256.length);
        }
        return new Crc16(table256, reflected, init, xorOut, order, "Custom 256-entry table" + (reflected ? " (reflected)" : ""));
    }

    /**
     * The two 256-entry byte tables printed in the Modbus spec (auchCRCHi then auchCRCLo),
     * combined into one reflected 16-bit table with init 0xFFFF.
     */
    public static Crc16 fromHiLoTables(int[] hi, int[] lo, ByteOrder order) {
        if (hi.length != 256 || lo.length != 256) {
            throw new IllegalArgumentException("High and low tables need 256 entries each");
        }
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            table[i] = (hi[i] & 0xFF) | ((lo[i] & 0xFF) << 8);
        }
        return new Crc16(table, true, 0xFFFF, 0, order, "Custom Modbus hi/lo tables (reflected, init 0xffff)");
    }

    /**
     * Parses pasted text into a CRC: 256 hex/decimal entries are a 16-bit table; 512 entries
     * are the Modbus-style high table followed by the low table. Braces, commas, comments
     * and {@code 0x} prefixes are tolerated.
     */
    public static Crc16 parseTable(String text, boolean reflected, int init, int xorOut, ByteOrder order) {
        int[] values = parseNumbers(text);
        if (values.length == 512) {
            int[] hi = new int[256];
            int[] lo = new int[256];
            System.arraycopy(values, 0, hi, 0, 256);
            System.arraycopy(values, 256, lo, 0, 256);
            return fromHiLoTables(hi, lo, order);
        }
        if (values.length == 256) {
            return fromTable(values, reflected, init, xorOut, order);
        }
        throw new IllegalArgumentException("Expected 256 entries (16-bit table) or 512 (hi + lo byte tables), found " + values.length);
    }

    /**
     * Tokenises pasted table text. The whole table is read as hex when any token carries a
     * {@code 0x} prefix or a hex letter, or when every token is exactly two or four digits
     * (the way spec and vendor tables are printed); otherwise as decimal.
     */
    static int[] parseNumbers(String text) {
        // Strip comments and array-size brackets so a pasted C declaration parses as-is.
        String cleaned = text.replaceAll("//[^\\n]*", " ")
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("\\[[^\\]]*\\]", " ");
        List<String> tokens = new ArrayList<>();
        for (String token : cleaned.split("[\\s,;{}()=]+")) {
            String t = token.trim();
            String lower = t.toLowerCase(Locale.ROOT);
            // keep only number-looking tokens; words like "static" or "uint16_t" are skipped
            if (lower.matches("0x[0-9a-f]+") || lower.matches("[0-9a-f]+h") || lower.matches("[0-9a-f]+")) {
                tokens.add(t);
            }
        }
        boolean hex = false;
        boolean allFixedWidth = !tokens.isEmpty();
        for (String t : tokens) {
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.startsWith("0x") || lower.endsWith("h") || lower.matches(".*[a-f].*")) {
                hex = true;
            }
            if (!(t.matches("[0-9a-fA-F]{2}") || t.matches("[0-9a-fA-F]{4}"))) {
                allFixedWidth = false;
            }
        }
        hex = hex || allFixedWidth;
        int[] arr = new int[tokens.size()];
        for (int i = 0; i < arr.length; i++) {
            String t = tokens.get(i);
            String lower = t.toLowerCase(Locale.ROOT);
            try {
                if (lower.startsWith("0x")) {
                    arr[i] = Integer.parseInt(t.substring(2), 16);
                } else if (lower.endsWith("h") && !lower.startsWith("0x")) {
                    arr[i] = Integer.parseInt(t.substring(0, t.length() - 1), 16);
                } else {
                    arr[i] = Integer.parseInt(t, hex ? 16 : 10);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a number in CRC table: \"" + t + "\"");
            }
        }
        return arr;
    }

    static int[] buildTable(int poly, boolean reflected) {
        int[] table = new int[256];
        if (reflected) {
            int rpoly = reflect16(poly);
            for (int i = 0; i < 256; i++) {
                int crc = i;
                for (int b = 0; b < 8; b++) {
                    crc = (crc & 1) != 0 ? (crc >>> 1) ^ rpoly : crc >>> 1;
                }
                table[i] = crc & 0xFFFF;
            }
        } else {
            for (int i = 0; i < 256; i++) {
                int crc = i << 8;
                for (int b = 0; b < 8; b++) {
                    crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ poly) & 0xFFFF : (crc << 1) & 0xFFFF;
                }
                table[i] = crc;
            }
        }
        return table;
    }

    private static int reflect16(int v) {
        int r = 0;
        for (int i = 0; i < 16; i++) {
            if ((v & (1 << i)) != 0) {
                r |= 1 << (15 - i);
            }
        }
        return r;
    }

    // --- use ------------------------------------------------------------------------

    public int compute(byte[] data, int offset, int length) {
        int crc = init;
        if (reflected) {
            for (int i = offset; i < offset + length; i++) {
                crc = (crc >>> 8) ^ table[(crc ^ data[i]) & 0xFF];
            }
        } else {
            for (int i = offset; i < offset + length; i++) {
                crc = ((crc << 8) ^ table[((crc >>> 8) ^ data[i]) & 0xFF]) & 0xFFFF;
            }
        }
        return (crc ^ xorOut) & 0xFFFF;
    }

    public int compute(byte[] data) {
        return compute(data, 0, data.length);
    }

    /** Body plus the two CRC bytes in this CRC's byte order. */
    public byte[] append(byte[] body) {
        int crc = compute(body);
        byte[] out = new byte[body.length + 2];
        System.arraycopy(body, 0, out, 0, body.length);
        byte lo = (byte) (crc & 0xFF);
        byte hi = (byte) ((crc >>> 8) & 0xFF);
        out[body.length] = order == ByteOrder.LOW_FIRST ? lo : hi;
        out[body.length + 1] = order == ByteOrder.LOW_FIRST ? hi : lo;
        return out;
    }

    /** True when the last two bytes of {@code frame} are the CRC of everything before them. */
    public boolean verify(byte[] frame, int length) {
        if (length < 3) {
            return false;
        }
        return compute(frame, 0, length - 2) == trailing(frame, length);
    }

    public boolean verify(byte[] frame) {
        return verify(frame, frame.length);
    }

    /** The CRC value carried in the last two bytes, per this CRC's byte order. */
    public int trailing(byte[] frame, int length) {
        int a = frame[length - 2] & 0xFF;
        int b = frame[length - 1] & 0xFF;
        return order == ByteOrder.LOW_FIRST ? a | (b << 8) : (a << 8) | b;
    }

    public ByteOrder order() {
        return order;
    }

    public String description() {
        return description;
    }

    public static String hex4(int v) {
        return String.format("%04x", v & 0xFFFF);
    }

    @Override
    public String toString() {
        return description + ", " + order.label.toLowerCase(Locale.ROOT);
    }
}
