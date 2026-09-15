package com.linkscope.core.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModbusEncoderTest {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    @Test
    void readRequestFramedAsRtuMatchesSpecExample() {
        byte[] pdu = ModbusEncoder.readRequest(3, 0, 10);
        assertArrayEquals(b(0x01, 0x03, 0x00, 0x00, 0x00, 0x0A, 0xC5, 0xCD), ModbusEncoder.rtuFrame(1, pdu, Crc16.STANDARD_MODBUS));
        assertArrayEquals(b(0x00, 0x07, 0x00, 0x00, 0x00, 0x06, 0x01, 0x03, 0x00, 0x00, 0x00, 0x0A), ModbusEncoder.tcpFrame(7, 1, pdu));
    }

    @Test
    void writeFunctionsEncodeAndDecodeRoundTrip() {
        assertArrayEquals(b(0x06, 0x00, 0x01, 0x00, 0x03), ModbusEncoder.writeSingleRegister(1, 3));
        assertArrayEquals(b(0x05, 0x00, 0xAC, 0xFF, 0x00), ModbusEncoder.writeSingleCoil(172, true));
        assertArrayEquals(b(0x10, 0x00, 0x10, 0x00, 0x02, 0x04, 0x00, 0x0A, 0x01, 0x02),
                ModbusEncoder.writeMultipleRegisters(16, new int[] {10, 258}));
        byte[] coils = ModbusEncoder.writeMultipleCoils(19, new boolean[] {true, false, true, true, false, false, true, true, true, false});
        assertArrayEquals(b(0x0F, 0x00, 0x13, 0x00, 0x0A, 0x02, 0xCD, 0x01), coils);

        byte[] frame = ModbusEncoder.rtuFrame(17, coils, Crc16.STANDARD_MODBUS);
        ModbusDecoder.Frame decoded = ModbusDecoder.decodeRtu(frame).orElseThrow();
        assertEquals(17, decoded.unitId());
        assertTrue(decoded.summary().contains("FC 15 Write Multiple Coils · request addr 19 qty 10"), decoded.summary());
    }

    @Test
    void customCrcIsUsedForRtuFrames() {
        Crc16 ccitt = Crc16.of(Crc16.CCITT_FALSE, Crc16.ByteOrder.HIGH_FIRST);
        byte[] frame = ModbusEncoder.rtuFrame(1, ModbusEncoder.readRequest(3, 0, 10), ccitt);
        assertTrue(ccitt.verify(frame));
        assertTrue(ModbusDecoder.decodeRtu(frame).isEmpty(), "standard CRC must reject it");
        assertTrue(ModbusDecoder.decodeRtu(frame, ccitt).isPresent());
    }

    @Test
    void parsersAndRangeChecks() {
        assertArrayEquals(new int[] {1, 2, 10}, ModbusEncoder.parseValues("1, 2, 0x0A"));
        assertArrayEquals(new boolean[] {true, false, true}, ModbusEncoder.parseBits("on 0 true"));
        assertThrows(IllegalArgumentException.class, () -> ModbusEncoder.parseBits("1 maybe"));
        assertThrows(IllegalArgumentException.class, () -> ModbusEncoder.readRequest(3, 0, 126));
        assertThrows(IllegalArgumentException.class, () -> ModbusEncoder.writeSingleRegister(0, 70000));
        assertThrows(IllegalArgumentException.class, () -> ModbusEncoder.rtuFrame(248, new byte[] {3}, Crc16.STANDARD_MODBUS));
    }
}
