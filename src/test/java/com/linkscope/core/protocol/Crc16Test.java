package com.linkscope.core.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Crc16Test {
    private static final byte[] CHECK = "123456789".getBytes(StandardCharsets.US_ASCII);

    @Test
    void presetsMatchTheStandardCheckValues() {
        assertEquals(0x4B37, Crc16.of(Crc16.MODBUS, Crc16.ByteOrder.LOW_FIRST).compute(CHECK));
        assertEquals(0xBB3D, Crc16.of(Crc16.ARC, Crc16.ByteOrder.LOW_FIRST).compute(CHECK));
        assertEquals(0x29B1, Crc16.of(Crc16.CCITT_FALSE, Crc16.ByteOrder.HIGH_FIRST).compute(CHECK));
        assertEquals(0x31C3, Crc16.of(Crc16.XMODEM, Crc16.ByteOrder.HIGH_FIRST).compute(CHECK));
        assertEquals(0x2189, Crc16.of(Crc16.KERMIT, Crc16.ByteOrder.LOW_FIRST).compute(CHECK));
        assertEquals(0xB4C8, Crc16.of(Crc16.USB, Crc16.ByteOrder.LOW_FIRST).compute(CHECK));
    }

    @Test
    void standardModbusAgreesWithModbusDecoderAndByteOrder() {
        byte[] body = {0x01, 0x03, 0x00, 0x00, 0x00, 0x0A};
        assertEquals(ModbusDecoder.crc16(body, 0, body.length), Crc16.STANDARD_MODBUS.compute(body));
        assertArrayEquals(new byte[] {0x01, 0x03, 0x00, 0x00, 0x00, 0x0A, (byte) 0xC5, (byte) 0xCD}, Crc16.STANDARD_MODBUS.append(body));
        assertArrayEquals(new byte[] {0x01, 0x03, 0x00, 0x00, 0x00, 0x0A, (byte) 0xCD, (byte) 0xC5},
                Crc16.of(Crc16.MODBUS, Crc16.ByteOrder.HIGH_FIRST).append(body));
        assertTrue(Crc16.STANDARD_MODBUS.verify(Crc16.STANDARD_MODBUS.append(body)));
        assertFalse(Crc16.STANDARD_MODBUS.verify(new byte[] {0x01, 0x03, 0x00, 0x00, 0x00, 0x0A, (byte) 0xC5, (byte) 0xCE}));
    }

    @Test
    void pastedSixteenBitTableReproducesTheAlgorithm() {
        int[] table = Crc16.buildTable(0x8005, true);
        StringBuilder text = new StringBuilder("static const uint16_t crc_table[256] = {\n");
        for (int i = 0; i < 256; i++) {
            text.append(String.format("0x%04X,%s", table[i], (i % 8 == 7) ? "\n" : " "));
        }
        text.append("};");
        Crc16 pasted = Crc16.parseTable(text.toString(), true, 0xFFFF, 0, Crc16.ByteOrder.LOW_FIRST);
        assertEquals(0x4B37, pasted.compute(CHECK));
        assertEquals(Crc16.STANDARD_MODBUS.compute(CHECK), pasted.compute(CHECK));
    }

    @Test
    void pastedModbusHiLoTablesReproduceTheAlgorithm() {
        int[] table = Crc16.buildTable(0x8005, true);
        StringBuilder hi = new StringBuilder("/* Table of CRC values for high-order byte */\n");
        StringBuilder lo = new StringBuilder("// low-order byte\n");
        for (int i = 0; i < 256; i++) {
            hi.append(String.format("%02X ", table[i] & 0xFF));
            lo.append(String.format("%02X ", (table[i] >> 8) & 0xFF));
        }
        Crc16 pasted = Crc16.parseTable(hi + "\n" + lo, false, 0, 0, Crc16.ByteOrder.LOW_FIRST);
        assertEquals(0x4B37, pasted.compute(CHECK));
        assertEquals("Custom Modbus hi/lo tables (reflected, init 0xffff)", pasted.description());
    }

    @Test
    void decimalTablesAndBadInputAreHandled() {
        int[] table = Crc16.buildTable(0x1021, false);
        StringBuilder text = new StringBuilder();
        for (int v : table) {
            text.append(v).append(", ");
        }
        Crc16 pasted = Crc16.parseTable(text.toString(), false, 0xFFFF, 0, Crc16.ByteOrder.HIGH_FIRST);
        assertEquals(0x29B1, pasted.compute(CHECK));

        assertThrows(IllegalArgumentException.class, () -> Crc16.parseTable("1 2 3", true, 0, 0, Crc16.ByteOrder.LOW_FIRST));
        assertThrows(IllegalArgumentException.class, () -> Crc16.parseTable("0x00 0xZZ", true, 0, 0, Crc16.ByteOrder.LOW_FIRST));
    }

    @Test
    void customParametersMatchPreset() {
        Crc16 custom = Crc16.ofParameters(0x1021, 0xFFFF, false, 0x0000, Crc16.ByteOrder.HIGH_FIRST);
        assertEquals(0x29B1, custom.compute(CHECK));
    }
}
