package com.linkscope.core;

import com.linkscope.core.PayloadDecoder.Decoded;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadDecoderTest {

    private static String value(List<Decoded> list, String name) {
        for (Decoded d : list) {
            if (d.name().equals(name)) {
                return d.value();
            }
        }
        throw new AssertionError("no decoder named " + name + " in " + list);
    }

    @Test
    void hexDumpRowsHaveFixedWidth() {
        byte[] data = "Hello World\nsecond row here!!".getBytes(StandardCharsets.UTF_8);
        String dump = PayloadDecoder.hexDump(data);
        String[] rows = dump.split("\n");
        assertEquals(2, rows.length);
        assertEquals(PayloadDecoder.ROW_WIDTH, rows[0].length());
        assertEquals(PayloadDecoder.ROW_WIDTH, rows[1].length());
        assertTrue(rows[0].startsWith("00000000  48 65 6c 6c 6f 20 57 6f  72 6c 64 0a 73 65 63 6f  |Hello World.seco|"), rows[0]);
        assertTrue(rows[1].startsWith("00000010  6e 64 20 72 6f 77 20 68  65 72 65 21 21"), rows[1]);
        assertTrue(rows[1].endsWith("|nd row here!!   |"), rows[1]);
    }

    @Test
    void caretMapsToByteOffsets() {
        int n = 20;
        assertEquals(0, PayloadDecoder.offsetAt(10, n));      // first hex digit
        assertEquals(0, PayloadDecoder.offsetAt(11, n));
        assertEquals(-1, PayloadDecoder.offsetAt(12, n));     // space after byte 0
        assertEquals(7, PayloadDecoder.offsetAt(10 + 21, n));
        assertEquals(-1, PayloadDecoder.offsetAt(10 + 24, n)); // mid gap
        assertEquals(8, PayloadDecoder.offsetAt(10 + 25, n));
        assertEquals(15, PayloadDecoder.offsetAt(10 + 25 + 21, n));
        assertEquals(0, PayloadDecoder.offsetAt(61, n));      // ascii column
        assertEquals(15, PayloadDecoder.offsetAt(76, n));
        assertEquals(-1, PayloadDecoder.offsetAt(77, n));     // closing bar
        assertEquals(16, PayloadDecoder.offsetAt(PayloadDecoder.ROW_WIDTH + 1 + 10, n)); // second row
        assertEquals(-1, PayloadDecoder.offsetAt(PayloadDecoder.ROW_WIDTH + 1 + 10 + 3 * 5, n)); // beyond data
        assertArrayEquals(new int[] {1, 3}, PayloadDecoder.selectionToRange(13, 22, n));
        assertNull(PayloadDecoder.selectionToRange(0, 5, n));
    }

    @Test
    void decodesIntegersFloatsAndText() {
        byte[] data = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        List<Decoded> d = PayloadDecoder.decode(data, 0, 2);
        assertEquals("01 02", value(d, "Hex"));
        assertEquals("1 / 1", value(d, "uint8 / int8"));
        assertEquals("513 / 258", value(d, "uint16 LE / BE"));
        assertEquals("67305985 / 16909060", value(d, "uint32 LE / BE"));
        assertEquals("578437695752307201 / 72623859790382856", value(d, "uint64 LE / BE"));
        assertEquals("00000001", value(d, "Bits (first byte)"));

        byte[] negative = {(byte) 0xff, (byte) 0xff};
        List<Decoded> n = PayloadDecoder.decode(negative, 0, 2);
        assertEquals("255 / -1", value(n, "uint8 / int8"));
        assertEquals("-1 / -1", value(n, "int16 LE / BE"));

        byte[] pi = {(byte) 0xdb, 0x0f, 0x49, 0x40};
        assertTrue(value(PayloadDecoder.decode(pi, 0, 4), "float32 LE / BE").startsWith("3.1415927 /"));

        byte[] time = {0x00, 0x00, 0x00, 0x00};
        assertTrue(value(PayloadDecoder.decode(time, 0, 4), "unix time (uint32 LE / BE)").startsWith("1970-01-01T00:00:00Z"));
    }

    @Test
    void offsetBeyondDataYieldsNothingAndJsonIsPretty() {
        assertTrue(PayloadDecoder.decode(new byte[] {1}, 5, 1).isEmpty());
        byte[] json = "{\"a\":[1,2]}".getBytes(StandardCharsets.UTF_8);
        String pretty = PayloadDecoder.prettyJson(json);
        assertTrue(pretty.contains("\"a\" : [ 1, 2 ]"), pretty);
        assertNull(PayloadDecoder.prettyJson("hello".getBytes(StandardCharsets.UTF_8)));
        assertNull(PayloadDecoder.prettyJson("{oops".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void logEntryDeltaAndCsv() {
        java.time.LocalTime t0 = java.time.LocalTime.of(10, 0, 0, 0);
        java.time.LocalTime t1 = t0.plusNanos(12_500_000);
        LogEntry e = new LogEntry(t1, "UDP", LogEntry.Kind.RX, new byte[] {'a', '"'}, "127.0.0.1:1");
        assertEquals("[+  0.013s] [UDP] [RX] 127.0.0.1:1 (2 B): a\"", e.format(false, LogEntry.TimeMode.DELTA, t0));
        assertEquals("[UDP] [RX] 127.0.0.1:1 (2 B): 61 22", e.format(true, LogEntry.TimeMode.NONE, null));
        assertEquals("\"10:00:00.012\",\"UDP\",\"RX\",\"127.0.0.1:1\",2,\"61 22\",\"a\"\"\"", e.toCsvRow());
    }
}
