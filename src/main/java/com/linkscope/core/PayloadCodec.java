package com.linkscope.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Converts between user-typed payload text and bytes, in both directions, for the
 * hex and ASCII display/input modes shared by every module.
 * <p>
 * ASCII text supports the escapes {@code \n \r \t \0 \\ \xNN}; the ASCII renderer
 * emits the same escapes for non-printable bytes so a logged line can be pasted
 * back into a send field unchanged.
 */
public final class PayloadCodec {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PayloadCodec() {
    }

    /** Parses "48 65 6c", "48656c", "0x48,0x65" and similar into bytes. */
    public static byte[] parseHex(String text) {
        String cleaned = text.replaceAll("(?i)0x", "").replaceAll("[\\s,:;\\-]", "");
        if (cleaned.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex payload has an odd number of digits");
        }
        byte[] out = new byte[cleaned.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(cleaned.charAt(2 * i), 16);
            int lo = Character.digit(cleaned.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException(
                        "Invalid hex digit near '" + cleaned.substring(2 * i, 2 * i + 2) + "'");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Parses text with C-style escapes into UTF-8 bytes. */
    public static byte[] parseAscii(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                appendUtf8(out, String.valueOf(c));
                i++;
                continue;
            }
            char e = text.charAt(i + 1);
            switch (e) {
                case 'n' -> { out.write('\n'); i += 2; }
                case 'r' -> { out.write('\r'); i += 2; }
                case 't' -> { out.write('\t'); i += 2; }
                case '0' -> { out.write(0); i += 2; }
                case '\\' -> { out.write('\\'); i += 2; }
                case 'x' -> {
                    if (i + 3 >= text.length()) {
                        throw new IllegalArgumentException("Incomplete \\x escape at end of payload");
                    }
                    int hi = Character.digit(text.charAt(i + 2), 16);
                    int lo = Character.digit(text.charAt(i + 3), 16);
                    if (hi < 0 || lo < 0) {
                        throw new IllegalArgumentException("Invalid \\x escape: " + text.substring(i, i + 4));
                    }
                    out.write((hi << 4) | lo);
                    i += 4;
                }
                default -> { appendUtf8(out, "\\" + e); i += 2; }
            }
        }
        return out.toByteArray();
    }

    /** Parses according to the chosen mode. */
    public static byte[] parse(String text, boolean hex) {
        return hex ? parseHex(text) : parseAscii(text);
    }

    /** Space-separated lowercase hex pairs: "48 65 6c 6c 6f". */
    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(HEX[(data[i] >> 4) & 0xF]).append(HEX[data[i] & 0xF]);
        }
        return sb.toString();
    }

    /** Printable ASCII as-is, control/high bytes as {@code \n \r \t \xNN} escapes. */
    public static String toAscii(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length);
        for (byte value : data) {
            int b = value & 0xFF;
            switch (b) {
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (b >= 0x20 && b < 0x7F) {
                        sb.append((char) b);
                    } else {
                        sb.append("\\x").append(HEX[b >> 4]).append(HEX[b & 0xF]);
                    }
                }
            }
        }
        return sb.toString();
    }

    public static String format(byte[] data, boolean hex) {
        return hex ? toHex(data) : toAscii(data);
    }

    private static void appendUtf8(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
