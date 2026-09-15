package com.linkscope.core.protocol;

import com.linkscope.core.protocol.ModbusDecoder.Frame;
import com.linkscope.core.protocol.ModbusDecoder.Transport;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModbusDecoderTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    void crcMatchesKnownVectors() {
        // Classic examples from the Modbus spec and common references.
        assertEquals(0xCDC5, ModbusDecoder.crc16(bytes(0x01, 0x03, 0x00, 0x00, 0x00, 0x0A), 0, 6));
        assertEquals(0x0B98, ModbusDecoder.crc16(bytes(0x01, 0x06, 0x00, 0x01, 0x00, 0x03), 0, 6));
        assertArrayEquals(bytes(0x01, 0x83, 0x02, 0xC0, 0xF1), ModbusDecoder.withCrc(bytes(0x01, 0x83, 0x02)));
    }

    @Test
    void rtuReadHoldingRegistersRequest() {
        Frame f = ModbusDecoder.decodeRtu(bytes(0x01, 0x03, 0x00, 0x00, 0x00, 0x0A, 0xC5, 0xCD)).orElseThrow();
        assertEquals(Transport.RTU, f.transport());
        assertEquals(1, f.unitId());
        assertEquals(3, f.functionCode());
        assertFalse(f.exception());
        assertEquals("RTU unit 1 · FC 03 Read Holding Registers · request addr 0 qty 10", f.summary());
        assertTrue(f.describe().contains("Quantity: 10 registers"), f.describe());
    }

    @Test
    void rtuReadHoldingRegistersResponse() {
        byte[] frame = ModbusDecoder.withCrc(bytes(0x01, 0x03, 0x04, 0x00, 0x0A, 0x01, 0x02));
        Frame f = ModbusDecoder.decode(frame).orElseThrow();
        assertEquals("RTU unit 1 · FC 03 Read Holding Registers · response 2 register(s) [10, 258]", f.summary());
        assertTrue(f.describe().contains("[1] 258 (0x0102)"), f.describe());
    }

    @Test
    void rtuWriteSingleRegisterAndCoil() {
        Frame reg = ModbusDecoder.decodeRtu(bytes(0x01, 0x06, 0x00, 0x01, 0x00, 0x03, 0x98, 0x0B)).orElseThrow();
        assertEquals("RTU unit 1 · FC 06 Write Single Register · register 1 = 3", reg.summary());
        Frame coil = ModbusDecoder.decode(ModbusDecoder.withCrc(bytes(0x11, 0x05, 0x00, 0xAC, 0xFF, 0x00))).orElseThrow();
        assertEquals("RTU unit 17 · FC 05 Write Single Coil · coil 172 = ON", coil.summary());
    }

    @Test
    void rtuExceptionResponse() {
        Frame f = ModbusDecoder.decodeRtu(bytes(0x01, 0x83, 0x02, 0xC0, 0xF1)).orElseThrow();
        assertTrue(f.exception());
        assertEquals(0x83, f.functionCode());
        assertEquals("RTU unit 1 · FC 03 EXCEPTION Exception response to FC 03 Read Holding Registers · exception 2 Illegal Data Address",
                f.summary());
    }

    @Test
    void rtuWriteMultipleRegistersRequestAndResponse() {
        byte[] request = ModbusDecoder.withCrc(bytes(0x01, 0x10, 0x00, 0x10, 0x00, 0x02, 0x04, 0x00, 0x0A, 0x01, 0x02));
        Frame req = ModbusDecoder.decode(request).orElseThrow();
        assertEquals("RTU unit 1 · FC 16 Write Multiple Registers · request addr 16 qty 2 [10, 258]", req.summary());
        byte[] response = ModbusDecoder.withCrc(bytes(0x01, 0x10, 0x00, 0x10, 0x00, 0x02));
        Frame res = ModbusDecoder.decode(response).orElseThrow();
        assertEquals("RTU unit 1 · FC 16 Write Multiple Registers · response addr 16 qty 2", res.summary());
    }

    @Test
    void rtuReadCoilsResponseBits() {
        Frame f = ModbusDecoder.decode(ModbusDecoder.withCrc(bytes(0x01, 0x01, 0x01, 0x05))).orElseThrow();
        assertTrue(f.describe().contains("Bits (LSB first): 10100000"), f.describe());
    }

    @Test
    void badCrcAndShortFramesAreRejected() {
        assertEquals(Optional.empty(), ModbusDecoder.decodeRtu(bytes(0x01, 0x03, 0x00, 0x00, 0x00, 0x0A, 0xC5, 0xCE)));
        assertEquals(Optional.empty(), ModbusDecoder.decodeRtu(bytes(0x01, 0x03)));
        assertEquals(Optional.empty(), ModbusDecoder.decode("hello world".getBytes()));
    }

    @Test
    void tcpFrameWithMbapHeader() {
        Frame f = ModbusDecoder.decode(bytes(0x00, 0x05, 0x00, 0x00, 0x00, 0x06, 0x01, 0x03, 0x00, 0x00, 0x00, 0x0A)).orElseThrow();
        assertEquals(Transport.TCP, f.transport());
        assertEquals(5, f.transactionId());
        assertEquals("TCP tid 5 unit 1 · FC 03 Read Holding Registers · request addr 0 qty 10", f.summary());
        // wrong MBAP length -> not TCP, and no valid CRC either
        assertEquals(Optional.empty(), ModbusDecoder.decode(bytes(0x00, 0x05, 0x00, 0x00, 0x00, 0x09, 0x01, 0x03, 0x00, 0x00, 0x00, 0x0A)));
    }
}
