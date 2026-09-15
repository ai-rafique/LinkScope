package com.linkscope.core.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decodes Modbus RTU frames (unit, PDU, CRC-16) and Modbus TCP frames (MBAP header + PDU)
 * for the standard function codes. A frame is only reported when it is structurally
 * sound: a valid CRC for RTU, a consistent MBAP length for TCP. Requests and responses
 * share function codes, so the PDU layout is inferred from its length; when both
 * readings fit, both are shown.
 */
public final class ModbusDecoder {

    public enum Transport { RTU, TCP }

    /** One decoded field, e.g. "Start address" / "0 (0x0000)". */
    public record Field(String name, String value) {
    }

    /** A decoded frame. {@code summary} is one line for the log; {@link #describe()} is the full view. */
    public record Frame(Transport transport, Integer transactionId, int unitId, int functionCode,
                        boolean exception, String functionName, String summary, List<Field> fields) {

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("Modbus ").append(transport);
            if (transactionId != null) {
                sb.append(" · transaction ").append(transactionId);
            }
            sb.append(" · unit ").append(unitId).append('\n');
            sb.append("FC ").append(String.format("%02d (0x%02x) ", functionCode, functionCode)).append(functionName).append('\n');
            for (Field f : fields) {
                sb.append(f.name()).append(": ").append(f.value()).append('\n');
            }
            return sb.toString().stripTrailing();
        }
    }

    private static final int MAX_LISTED = 32;

    private ModbusDecoder() {
    }

    // --- CRC ------------------------------------------------------------------------

    /** Modbus CRC-16 (poly 0xA001, init 0xFFFF) over {@code data[offset..offset+length)}. */
    public static int crc16(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= data[i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    /** Appends the CRC (low byte first, as on the wire) to an RTU frame body. */
    public static byte[] withCrc(byte[] body) {
        int crc = crc16(body, 0, body.length);
        byte[] out = new byte[body.length + 2];
        System.arraycopy(body, 0, out, 0, body.length);
        out[body.length] = (byte) (crc & 0xFF);
        out[body.length + 1] = (byte) ((crc >>> 8) & 0xFF);
        return out;
    }

    // --- entry points ---------------------------------------------------------------

    /** Tries TCP (strict MBAP) first, then RTU (valid CRC). */
    public static Optional<Frame> decode(byte[] data) {
        Optional<Frame> tcp = decodeTcp(data);
        return tcp.isPresent() ? tcp : decodeRtu(data);
    }

    public static Optional<Frame> decodeRtu(byte[] data) {
        return decodeRtu(data, Crc16.STANDARD_MODBUS);
    }

    /** Tries TCP first, then RTU verified with the given CRC. */
    public static Optional<Frame> decode(byte[] data, Crc16 crc) {
        Optional<Frame> tcp = decodeTcp(data);
        return tcp.isPresent() ? tcp : decodeRtu(data, crc);
    }

    /** RTU with a custom CRC (parameters or pasted table); the frame must verify. */
    public static Optional<Frame> decodeRtu(byte[] data, Crc16 crc) {
        if (data == null || data.length < 4 || data.length > 256 || !crc.verify(data)) {
            return Optional.empty();
        }
        return Optional.of(decodeRtuUnchecked(data));
    }

    /** Decodes an RTU frame without checking its CRC (for showing what a bad-CRC frame was trying to say). */
    public static Frame decodeRtuUnchecked(byte[] data) {
        int unit = data[0] & 0xFF;
        int pduLength = Math.max(1, data.length - 3);
        byte[] pdu = new byte[pduLength];
        System.arraycopy(data, 1, pdu, 0, Math.min(pduLength, data.length - 1));
        return build(Transport.RTU, null, unit, pdu);
    }

    public static Optional<Frame> decodeTcp(byte[] data) {
        if (data == null || data.length < 8 || data.length > 260) {
            return Optional.empty();
        }
        int tid = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        int pid = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int len = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        if (pid != 0 || len != data.length - 6 || len < 2) {
            return Optional.empty();
        }
        int unit = data[6] & 0xFF;
        byte[] pdu = new byte[data.length - 7];
        System.arraycopy(data, 7, pdu, 0, pdu.length);
        return Optional.of(build(Transport.TCP, tid, unit, pdu));
    }

    // --- PDU --------------------------------------------------------------------------

    private static Frame build(Transport transport, Integer tid, int unit, byte[] pdu) {
        int fc = pdu[0] & 0xFF;
        List<Field> fields = new ArrayList<>();
        boolean exception = (fc & 0x80) != 0;
        String name;
        String detail;
        if (exception) {
            int baseFc = fc & 0x7F;
            int code = pdu.length > 1 ? pdu[1] & 0xFF : -1;
            name = "Exception response to FC " + String.format("%02d", baseFc) + " " + functionName(baseFc);
            detail = "exception " + code + " " + exceptionName(code);
            fields.add(new Field("Exception code", code + " (" + exceptionName(code) + ")"));
        } else {
            name = functionName(fc);
            detail = decodePdu(fc, pdu, fields);
        }
        String head = transport == Transport.TCP ? "TCP tid " + tid + " unit " + unit : "RTU unit " + unit;
        String summary = head + " · FC " + String.format("%02d", fc & 0x7F) + " " + (exception ? "EXCEPTION " : "") + name
                + (detail.isEmpty() ? "" : " · " + detail);
        return new Frame(transport, tid, unit, fc, exception, name, summary, List.copyOf(fields));
    }

    /** Fills {@code fields} and returns a one-line detail for the summary. */
    private static String decodePdu(int fc, byte[] pdu, List<Field> fields) {
        int len = pdu.length;
        switch (fc) {
            case 1, 2 -> {
                boolean request = len == 5;
                boolean response = len >= 2 && len == 2 + (pdu[1] & 0xFF);
                if (request && !response) {
                    return readRequest(pdu, fields, "coils");
                }
                if (response) {
                    int count = pdu[1] & 0xFF;
                    fields.add(new Field("Byte count", String.valueOf(count)));
                    fields.add(new Field("Bits (LSB first)", bits(pdu, 2, count)));
                    return "response " + count + " byte(s) " + bits(pdu, 2, Math.min(count, 4)) + (count > 4 ? "…" : "");
                }
                return raw(pdu, fields);
            }
            case 3, 4 -> {
                boolean request = len == 5;
                int count = len >= 2 ? pdu[1] & 0xFF : -1;
                boolean response = len >= 2 && len == 2 + count && count % 2 == 0 && count > 0;
                if (request && !response) {
                    return readRequest(pdu, fields, "registers");
                }
                if (response) {
                    fields.add(new Field("Byte count", String.valueOf(count)));
                    fields.add(new Field("Registers", registers(pdu, 2, count / 2)));
                    return "response " + count / 2 + " register(s) " + registersShort(pdu, 2, count / 2);
                }
                return raw(pdu, fields);
            }
            case 5 -> {
                if (len == 5) {
                    int addr = u16(pdu, 1);
                    int value = u16(pdu, 3);
                    String state = value == 0xFF00 ? "ON" : value == 0x0000 ? "OFF" : "invalid 0x" + Integer.toHexString(value);
                    fields.add(new Field("Coil address", addr + " (0x" + String.format("%04x", addr) + ")"));
                    fields.add(new Field("Value", state));
                    return "coil " + addr + " = " + state;
                }
                return raw(pdu, fields);
            }
            case 6 -> {
                if (len == 5) {
                    int addr = u16(pdu, 1);
                    int value = u16(pdu, 3);
                    fields.add(new Field("Register address", addr + " (0x" + String.format("%04x", addr) + ")"));
                    fields.add(new Field("Value", value + " (0x" + String.format("%04x", value) + ")"));
                    return "register " + addr + " = " + value;
                }
                return raw(pdu, fields);
            }
            case 15, 16 -> {
                if (len == 5) {
                    int addr = u16(pdu, 1);
                    int qty = u16(pdu, 3);
                    fields.add(new Field("Start address", addr + " (0x" + String.format("%04x", addr) + ")"));
                    fields.add(new Field("Quantity", String.valueOf(qty)));
                    return "response addr " + addr + " qty " + qty;
                }
                if (len >= 6 && len == 6 + (pdu[5] & 0xFF)) {
                    int addr = u16(pdu, 1);
                    int qty = u16(pdu, 3);
                    int count = pdu[5] & 0xFF;
                    fields.add(new Field("Start address", addr + " (0x" + String.format("%04x", addr) + ")"));
                    fields.add(new Field("Quantity", String.valueOf(qty)));
                    fields.add(new Field("Byte count", String.valueOf(count)));
                    if (fc == 16) {
                        fields.add(new Field("Registers", registers(pdu, 6, count / 2)));
                        return "request addr " + addr + " qty " + qty + " " + registersShort(pdu, 6, count / 2);
                    }
                    fields.add(new Field("Bits (LSB first)", bits(pdu, 6, count)));
                    return "request addr " + addr + " qty " + qty;
                }
                return raw(pdu, fields);
            }
            case 8 -> {
                if (len >= 3) {
                    int sub = u16(pdu, 1);
                    fields.add(new Field("Sub-function", sub + " (" + diagnosticName(sub) + ")"));
                    fields.add(new Field("Data", hex(pdu, 3, len - 3)));
                    return "sub-function " + sub + " " + diagnosticName(sub);
                }
                return raw(pdu, fields);
            }
            case 22 -> {
                if (len == 7) {
                    int addr = u16(pdu, 1);
                    fields.add(new Field("Register address", String.valueOf(addr)));
                    fields.add(new Field("AND mask", String.format("0x%04x", u16(pdu, 3))));
                    fields.add(new Field("OR mask", String.format("0x%04x", u16(pdu, 5))));
                    return "register " + addr + " AND 0x" + String.format("%04x", u16(pdu, 3)) + " OR 0x" + String.format("%04x", u16(pdu, 5));
                }
                return raw(pdu, fields);
            }
            case 23 -> {
                if (len >= 10 && len == 10 + (pdu[9] & 0xFF)) {
                    fields.add(new Field("Read start / quantity", u16(pdu, 1) + " / " + u16(pdu, 3)));
                    fields.add(new Field("Write start / quantity", u16(pdu, 5) + " / " + u16(pdu, 7)));
                    fields.add(new Field("Write registers", registers(pdu, 10, (pdu[9] & 0xFF) / 2)));
                    return "request read " + u16(pdu, 1) + "+" + u16(pdu, 3) + ", write " + u16(pdu, 5) + "+" + u16(pdu, 7);
                }
                int count = len >= 2 ? pdu[1] & 0xFF : -1;
                if (len >= 2 && len == 2 + count && count % 2 == 0) {
                    fields.add(new Field("Registers", registers(pdu, 2, count / 2)));
                    return "response " + count / 2 + " register(s)";
                }
                return raw(pdu, fields);
            }
            case 43 -> {
                if (len >= 2) {
                    int mei = pdu[1] & 0xFF;
                    fields.add(new Field("MEI type", mei + (mei == 14 ? " (Read Device Identification)" : mei == 13 ? " (CANopen)" : "")));
                    fields.add(new Field("Data", hex(pdu, 2, len - 2)));
                    return "MEI type " + mei;
                }
                return raw(pdu, fields);
            }
            default -> {
                return raw(pdu, fields);
            }
        }
    }

    private static String readRequest(byte[] pdu, List<Field> fields, String what) {
        int addr = u16(pdu, 1);
        int qty = u16(pdu, 3);
        fields.add(new Field("Start address", addr + " (0x" + String.format("%04x", addr) + ")"));
        fields.add(new Field("Quantity", qty + " " + what));
        return "request addr " + addr + " qty " + qty;
    }

    private static String raw(byte[] pdu, List<Field> fields) {
        if (pdu.length > 1) {
            fields.add(new Field("Data", hex(pdu, 1, pdu.length - 1)));
        }
        return pdu.length > 1 ? (pdu.length - 1) + " data byte(s)" : "";
    }

    // --- helpers --------------------------------------------------------------------

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && i < b.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String bits(byte[] b, int off, int count) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = off; i < off + count && i < b.length && shown < 64; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            int v = b[i] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                sb.append((v >> bit) & 1);
            }
            shown += 8;
        }
        if (count * 8 > shown) {
            sb.append(" …");
        }
        return sb.toString();
    }

    private static String registers(byte[] b, int off, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count && i < MAX_LISTED && off + 2 * i + 1 < b.length; i++) {
            int v = u16(b, off + 2 * i);
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('[').append(i).append("] ").append(v).append(" (0x").append(String.format("%04x", v)).append(')');
        }
        if (count > MAX_LISTED) {
            sb.append(", … (").append(count).append(" total)");
        }
        return sb.toString();
    }

    private static String registersShort(byte[] b, int off, int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count && i < 4 && off + 2 * i + 1 < b.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(u16(b, off + 2 * i));
        }
        if (count > 4) {
            sb.append(", …");
        }
        return sb.append(']').toString();
    }

    public static String functionName(int fc) {
        return switch (fc) {
            case 1 -> "Read Coils";
            case 2 -> "Read Discrete Inputs";
            case 3 -> "Read Holding Registers";
            case 4 -> "Read Input Registers";
            case 5 -> "Write Single Coil";
            case 6 -> "Write Single Register";
            case 7 -> "Read Exception Status";
            case 8 -> "Diagnostics";
            case 11 -> "Get Comm Event Counter";
            case 12 -> "Get Comm Event Log";
            case 15 -> "Write Multiple Coils";
            case 16 -> "Write Multiple Registers";
            case 17 -> "Report Server ID";
            case 20 -> "Read File Record";
            case 21 -> "Write File Record";
            case 22 -> "Mask Write Register";
            case 23 -> "Read/Write Multiple Registers";
            case 24 -> "Read FIFO Queue";
            case 43 -> "Encapsulated Interface Transport";
            default -> fc >= 65 && fc <= 72 || fc >= 100 && fc <= 110 ? "User-defined function" : "Unknown function";
        };
    }

    public static String exceptionName(int code) {
        return switch (code) {
            case 1 -> "Illegal Function";
            case 2 -> "Illegal Data Address";
            case 3 -> "Illegal Data Value";
            case 4 -> "Server Device Failure";
            case 5 -> "Acknowledge";
            case 6 -> "Server Device Busy";
            case 8 -> "Memory Parity Error";
            case 10 -> "Gateway Path Unavailable";
            case 11 -> "Gateway Target Device Failed to Respond";
            default -> "Unknown";
        };
    }

    private static String diagnosticName(int sub) {
        return switch (sub) {
            case 0 -> "Return Query Data";
            case 1 -> "Restart Communications";
            case 2 -> "Return Diagnostic Register";
            case 4 -> "Force Listen Only Mode";
            case 10 -> "Clear Counters";
            case 11 -> "Return Bus Message Count";
            case 12 -> "Return Bus Communication Error Count";
            case 13 -> "Return Bus Exception Error Count";
            case 14 -> "Return Server Message Count";
            default -> "sub-function";
        };
    }
}
