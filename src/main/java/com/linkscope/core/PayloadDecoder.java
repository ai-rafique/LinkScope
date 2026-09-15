package com.linkscope.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Pure functions behind the payload inspector: a classic hex dump and a set of
 * interpretations of the bytes at a given offset (integers in both endiannesses,
 * floats, text, base64, unix time, JSON).
 */
public final class PayloadDecoder {
    public static final int BYTES_PER_ROW = 16;
    /** Characters per dump row excluding the newline: offset (8) + 2 spaces + hex block (49) + " |" + 16 + "|". */
    public static final int ROW_WIDTH = 10 + 49 + 2 + BYTES_PER_ROW + 1;
    private static final int HEX_COL = 10;
    private static final int ASCII_COL = HEX_COL + 49 + 2;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TEXT = 4096;

    /** One decoded interpretation, e.g. name "uint16 LE", value "513". */
    public record Decoded(String name, String value) {
    }

    private PayloadDecoder() {
    }

    /** {@code 00000000  48 65 6c 6c 6f 20 57 6f  72 6c 64 0a              |Hello World.|} rows. */
    public static String hexDump(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 5 + 16);
        for (int row = 0; row < data.length; row += BYTES_PER_ROW) {
            sb.append(String.format("%08x  ", row));
            for (int i = 0; i < BYTES_PER_ROW; i++) {
                int idx = row + i;
                if (idx < data.length) {
                    sb.append(String.format("%02x ", data[idx] & 0xff));
                } else {
                    sb.append("   ");
                }
                if (i == 7) {
                    sb.append(' ');
                }
            }
            sb.append(" |");
            for (int i = 0; i < BYTES_PER_ROW; i++) {
                int idx = row + i;
                if (idx < data.length) {
                    int b = data[idx] & 0xff;
                    sb.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
                } else {
                    sb.append(' ');
                }
            }
            sb.append('|');
            if (row + BYTES_PER_ROW < data.length) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Maps a caret position in {@link #hexDump} output to a byte offset, or -1 when the
     * position sits on row prefix/padding rather than a byte.
     */
    public static int offsetAt(int caret, int dataLength) {
        if (caret < 0) {
            return -1;
        }
        int row = caret / (ROW_WIDTH + 1);
        int col = caret % (ROW_WIDTH + 1);
        int idx;
        if (col >= HEX_COL && col < HEX_COL + 49) {
            int c = col - HEX_COL;
            if (c == 24) {
                return -1; // the gap between the two groups of eight
            }
            if (c > 24) {
                c--;
            }
            if (c % 3 == 2) {
                return -1; // space after a byte
            }
            idx = row * BYTES_PER_ROW + c / 3;
        } else if (col >= ASCII_COL && col < ASCII_COL + BYTES_PER_ROW) {
            idx = row * BYTES_PER_ROW + (col - ASCII_COL);
        } else {
            return -1;
        }
        return idx < dataLength ? idx : -1;
    }

    /**
     * Converts a text selection [start, end) in the dump to an inclusive byte range
     * {offset, length}, or null when the selection touches no bytes.
     */
    public static int[] selectionToRange(int start, int end, int dataLength) {
        if (end <= start) {
            return null;
        }
        int first = -1;
        int last = -1;
        for (int pos = start; pos < end; pos++) {
            int o = offsetAt(pos, dataLength);
            if (o >= 0) {
                if (first < 0 || o < first) {
                    first = o;
                }
                if (o > last) {
                    last = o;
                }
            }
        }
        return first < 0 ? null : new int[] {first, last - first + 1};
    }

    /** Interpretations of the bytes starting at {@code offset}; {@code length} bounds the text/hex views. */
    public static List<Decoded> decode(byte[] data, int offset, int length) {
        List<Decoded> out = new ArrayList<>();
        if (data == null || offset < 0 || offset >= data.length) {
            return out;
        }
        int len = Math.max(1, Math.min(length, data.length - offset));
        byte[] sel = new byte[len];
        System.arraycopy(data, offset, sel, 0, len);
        int avail = data.length - offset;

        out.add(new Decoded("Selection", len + " byte(s) at offset " + offset + " (0x" + Integer.toHexString(offset) + ")"));
        out.add(new Decoded("Hex", PayloadCodec.toHex(sel)));
        out.add(new Decoded("ASCII (escaped)", PayloadCodec.toAscii(sel)));
        out.add(new Decoded("UTF-8", truncate(new String(sel, StandardCharsets.UTF_8))));
        out.add(new Decoded("Base64", Base64.getEncoder().encodeToString(sel)));
        out.add(new Decoded("Bits (first byte)", String.format("%8s", Integer.toBinaryString(sel[0] & 0xff)).replace(' ', '0')));
        out.add(new Decoded("uint8 / int8", (sel[0] & 0xff) + " / " + sel[0]));

        ByteBuffer le = ByteBuffer.wrap(data, offset, avail).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer be = ByteBuffer.wrap(data, offset, avail).order(ByteOrder.BIG_ENDIAN);
        if (avail >= 2) {
            out.add(new Decoded("uint16 LE / BE", (le.getShort(offset) & 0xffff) + " / " + (be.getShort(offset) & 0xffff)));
            out.add(new Decoded("int16 LE / BE", le.getShort(offset) + " / " + be.getShort(offset)));
        }
        if (avail >= 4) {
            long ule = le.getInt(offset) & 0xffffffffL;
            long ube = be.getInt(offset) & 0xffffffffL;
            out.add(new Decoded("uint32 LE / BE", ule + " / " + ube));
            out.add(new Decoded("int32 LE / BE", le.getInt(offset) + " / " + be.getInt(offset)));
            out.add(new Decoded("float32 LE / BE", le.getFloat(offset) + " / " + be.getFloat(offset)));
            out.add(new Decoded("unix time (uint32 LE / BE)", unixTime(ule) + " / " + unixTime(ube)));
        }
        if (avail >= 8) {
            out.add(new Decoded("uint64 LE / BE", Long.toUnsignedString(le.getLong(offset)) + " / "
                    + Long.toUnsignedString(be.getLong(offset))));
            out.add(new Decoded("int64 LE / BE", le.getLong(offset) + " / " + be.getLong(offset)));
            out.add(new Decoded("float64 LE / BE", le.getDouble(offset) + " / " + be.getDouble(offset)));
        }
        String json = prettyJson(data);
        if (json != null) {
            out.add(new Decoded("JSON (whole payload)", json));
        }
        return out;
    }

    private static String unixTime(long seconds) {
        if (seconds > 4_102_444_800L) { // beyond year 2100: almost certainly not a timestamp
            return "-";
        }
        return Instant.ofEpochSecond(seconds).toString();
    }

    /** Pretty-printed JSON if the whole payload parses as a JSON object or array, else null. */
    public static String prettyJson(byte[] data) {
        if (data.length < 2) {
            return null;
        }
        int first = -1;
        for (byte b : data) {
            if (b != ' ' && b != '\n' && b != '\r' && b != '\t') {
                first = b & 0xff;
                break;
            }
        }
        if (first != '{' && first != '[') {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(data);
            return truncate(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static String truncate(String s) {
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "…" : s;
    }
}
