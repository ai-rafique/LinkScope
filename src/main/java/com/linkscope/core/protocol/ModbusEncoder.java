package com.linkscope.core.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds Modbus PDUs and wraps them as RTU (with a configurable CRC) or TCP (MBAP) frames. */
public final class ModbusEncoder {

    /** Functions the request builder offers. */
    public enum Function {
        READ_COILS(1, "01 Read Coils", true, false),
        READ_DISCRETE_INPUTS(2, "02 Read Discrete Inputs", true, false),
        READ_HOLDING_REGISTERS(3, "03 Read Holding Registers", true, false),
        READ_INPUT_REGISTERS(4, "04 Read Input Registers", true, false),
        WRITE_SINGLE_COIL(5, "05 Write Single Coil", false, true),
        WRITE_SINGLE_REGISTER(6, "06 Write Single Register", false, true),
        WRITE_MULTIPLE_COILS(15, "15 Write Multiple Coils", false, true),
        WRITE_MULTIPLE_REGISTERS(16, "16 Write Multiple Registers", false, true),
        RAW_PDU(0, "Raw PDU (hex)", false, false);

        public final int code;
        public final String label;
        public final boolean usesQuantity;
        public final boolean usesValues;

        Function(int code, String label, boolean usesQuantity, boolean usesValues) {
            this.code = code;
            this.label = label;
            this.usesQuantity = usesQuantity;
            this.usesValues = usesValues;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private ModbusEncoder() {
    }

    public static byte[] readRequest(int functionCode, int address, int quantity) {
        requireRange(address, 0, 0xFFFF, "Address");
        requireRange(quantity, 1, functionCode <= 2 ? 2000 : 125, "Quantity");
        return new byte[] {(byte) functionCode, (byte) (address >> 8), (byte) address, (byte) (quantity >> 8), (byte) quantity};
    }

    public static byte[] writeSingleCoil(int address, boolean on) {
        requireRange(address, 0, 0xFFFF, "Address");
        return new byte[] {5, (byte) (address >> 8), (byte) address, (byte) (on ? 0xFF : 0x00), 0x00};
    }

    public static byte[] writeSingleRegister(int address, int value) {
        requireRange(address, 0, 0xFFFF, "Address");
        requireRange(value, 0, 0xFFFF, "Value");
        return new byte[] {6, (byte) (address >> 8), (byte) address, (byte) (value >> 8), (byte) value};
    }

    public static byte[] writeMultipleCoils(int address, boolean[] bits) {
        requireRange(address, 0, 0xFFFF, "Address");
        requireRange(bits.length, 1, 1968, "Number of coils");
        int byteCount = (bits.length + 7) / 8;
        byte[] pdu = new byte[6 + byteCount];
        pdu[0] = 15;
        pdu[1] = (byte) (address >> 8);
        pdu[2] = (byte) address;
        pdu[3] = (byte) (bits.length >> 8);
        pdu[4] = (byte) bits.length;
        pdu[5] = (byte) byteCount;
        for (int i = 0; i < bits.length; i++) {
            if (bits[i]) {
                pdu[6 + i / 8] |= (byte) (1 << (i % 8));
            }
        }
        return pdu;
    }

    public static byte[] writeMultipleRegisters(int address, int[] values) {
        requireRange(address, 0, 0xFFFF, "Address");
        requireRange(values.length, 1, 123, "Number of registers");
        byte[] pdu = new byte[6 + 2 * values.length];
        pdu[0] = 16;
        pdu[1] = (byte) (address >> 8);
        pdu[2] = (byte) address;
        pdu[3] = (byte) (values.length >> 8);
        pdu[4] = (byte) values.length;
        pdu[5] = (byte) (2 * values.length);
        for (int i = 0; i < values.length; i++) {
            requireRange(values[i], 0, 0xFFFF, "Register value");
            pdu[6 + 2 * i] = (byte) (values[i] >> 8);
            pdu[7 + 2 * i] = (byte) values[i];
        }
        return pdu;
    }

    /** Unit id + PDU + CRC (byte order per the CRC). */
    public static byte[] rtuFrame(int unitId, byte[] pdu, Crc16 crc) {
        requireRange(unitId, 0, 247, "Unit id");
        byte[] body = new byte[pdu.length + 1];
        body[0] = (byte) unitId;
        System.arraycopy(pdu, 0, body, 1, pdu.length);
        return crc.append(body);
    }

    /** MBAP header (transaction id, protocol 0, length, unit) + PDU. */
    public static byte[] tcpFrame(int transactionId, int unitId, byte[] pdu) {
        requireRange(unitId, 0, 255, "Unit id");
        int len = pdu.length + 1;
        byte[] out = new byte[7 + pdu.length];
        out[0] = (byte) (transactionId >> 8);
        out[1] = (byte) transactionId;
        out[2] = 0;
        out[3] = 0;
        out[4] = (byte) (len >> 8);
        out[5] = (byte) len;
        out[6] = (byte) unitId;
        System.arraycopy(pdu, 0, out, 7, pdu.length);
        return out;
    }

    /** "1, 2, 0x0A" or "1 2 10" to register values. */
    public static int[] parseValues(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Values are required, e.g. 1, 2, 0x0A");
        }
        List<Integer> out = new ArrayList<>();
        for (String token : text.split("[\\s,;]+")) {
            if (token.isBlank()) {
                continue;
            }
            String t = token.trim().toLowerCase(Locale.ROOT);
            try {
                out.add(t.startsWith("0x") ? Integer.parseInt(t.substring(2), 16) : Integer.parseInt(t));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a number: \"" + token + "\"");
            }
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /** "1 0 1 1", "on off", "true,false" to coil states. */
    public static boolean[] parseBits(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Coil values are required, e.g. 1 0 1 or on off");
        }
        List<Boolean> out = new ArrayList<>();
        for (String token : text.split("[\\s,;]+")) {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            switch (t) {
                case "1", "on", "true", "high" -> out.add(true);
                case "0", "off", "false", "low" -> out.add(false);
                default -> throw new IllegalArgumentException("Not a coil state: \"" + token + "\" (use 1/0 or on/off)");
            }
        }
        boolean[] arr = new boolean[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    private static void requireRange(int value, int min, int max, String label) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(label + " must be " + min + "-" + max + ", got " + value);
        }
    }
}
