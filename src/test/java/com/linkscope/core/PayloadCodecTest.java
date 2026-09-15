package com.linkscope.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayloadCodecTest {

    @Test
    void hexAcceptsCommonSpellings() {
        byte[] expected = {0x48, 0x65, (byte) 0xff};
        assertArrayEquals(expected, PayloadCodec.parseHex("48 65 ff"));
        assertArrayEquals(expected, PayloadCodec.parseHex("4865FF"));
        assertArrayEquals(expected, PayloadCodec.parseHex("0x48, 0x65, 0xff"));
        assertArrayEquals(expected, PayloadCodec.parseHex("48:65:ff"));
    }

    @Test
    void hexRejectsOddLengthAndBadDigits() {
        assertThrows(IllegalArgumentException.class, () -> PayloadCodec.parseHex("abc"));
        assertThrows(IllegalArgumentException.class, () -> PayloadCodec.parseHex("zz"));
    }

    @Test
    void asciiEscapesRoundTrip() {
        byte[] bytes = PayloadCodec.parseAscii("AT\\r\\n\\x00\\t\\\\end");
        assertArrayEquals(new byte[] {'A', 'T', '\r', '\n', 0, '\t', '\\', 'e', 'n', 'd'}, bytes);
        assertEquals("AT\\r\\n\\x00\\t\\\\end", PayloadCodec.toAscii(bytes));
    }

    @Test
    void asciiKeepsUnknownEscapesAndUtf8() {
        assertArrayEquals("a\\qb".getBytes(StandardCharsets.UTF_8), PayloadCodec.parseAscii("a\\qb"));
        assertArrayEquals("é".getBytes(StandardCharsets.UTF_8), PayloadCodec.parseAscii("é"));
        assertEquals("\\xc3\\xa9", PayloadCodec.toAscii("é".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void toHexFormatsPairs() {
        assertEquals("00 7f 80 ff", PayloadCodec.toHex(new byte[] {0, 0x7f, (byte) 0x80, (byte) 0xff}));
        assertEquals("", PayloadCodec.toHex(new byte[0]));
    }

    @Test
    void logEntryFormatIncludesTagsAndPayload() {
        LogEntry e = LogEntry.now("UDP", LogEntry.Kind.RX, new byte[] {'h', 'i'}, "127.0.0.1:5000");
        String ascii = e.format(false, false);
        assertEquals("[UDP] [RX] 127.0.0.1:5000 (2 B): hi", ascii);
        assertEquals("[UDP] [RX] 127.0.0.1:5000 (2 B): 68 69", e.format(true, false));
        LogEntry info = LogEntry.now("APP", LogEntry.Kind.INFO, null, "ready");
        assertEquals("[APP] [INFO] ready", info.format(false, false));
    }
}
