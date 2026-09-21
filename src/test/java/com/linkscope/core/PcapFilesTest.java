package com.linkscope.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcapFilesTest {

    @Test
    void writeThenReadAllRoundTrips(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("a.pcap");
        Instant t = Instant.parse("2026-09-21T10:00:00.123456Z");
        PcapFiles.write(f, 249, List.of(
                new PcapFiles.Record(t, new byte[] {1, 2, 3}, 3),
                new PcapFiles.Record(t.plusMillis(5), new byte[0], 0),
                new PcapFiles.Record(t.plusSeconds(1), new byte[] {9}, 40)));
        PcapFiles.Header h = PcapFiles.readHeader(f);
        assertEquals(249, h.linkType());
        assertEquals(ByteOrder.LITTLE_ENDIAN, h.order());
        assertEquals(65535, h.snaplen());
        List<PcapFiles.Record> back = PcapFiles.readAll(f);
        assertEquals(3, back.size());
        assertEquals(t, back.get(0).time());
        assertArrayEquals(new byte[] {1, 2, 3}, back.get(0).data());
        assertEquals(t.plusMillis(5), back.get(1).time());
        assertEquals(40, back.get(2).originalLength(), "original length survives truncation");
    }

    @Test
    void tailDeliversRecordsAsTheyBecomeComplete(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("grow.pcap");
        Files.write(f, new byte[0]);
        try (PcapFiles.Tail tail = new PcapFiles.Tail(f)) {
            assertTrue(tail.poll().isEmpty());
            assertNull(tail.header(), "no header yet");

            ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0xa1b2c3d4).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(249);
            Files.write(f, header.array(), StandardOpenOption.APPEND);
            assertTrue(tail.poll().isEmpty());
            assertNotNull(tail.header());
            assertEquals(249, tail.header().linkType());

            byte[] payload = {10, 20, 30, 40};
            ByteBuffer rec = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(1_700_000_000).putInt(250_000).putInt(4).putInt(4);
            Files.write(f, rec.array(), StandardOpenOption.APPEND);
            Files.write(f, new byte[] {10, 20}, StandardOpenOption.APPEND);
            assertTrue(tail.poll().isEmpty(), "half a record is not delivered");

            Files.write(f, new byte[] {30, 40}, StandardOpenOption.APPEND);
            List<PcapFiles.Record> got = tail.poll();
            assertEquals(1, got.size());
            assertArrayEquals(payload, got.get(0).data());
            assertEquals(Instant.ofEpochSecond(1_700_000_000L, 250_000_000L), got.get(0).time());

            Files.write(f, rec.array(), StandardOpenOption.APPEND);
            Files.write(f, payload, StandardOpenOption.APPEND);
            Files.write(f, rec.array(), StandardOpenOption.APPEND);
            Files.write(f, payload, StandardOpenOption.APPEND);
            assertEquals(2, tail.poll().size(), "two whole records at once");
            assertTrue(tail.poll().isEmpty());
        }
    }

    @Test
    void bigEndianAndNanosecondFilesAreRead(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("be.pcap");
        ByteBuffer b = ByteBuffer.allocate(24 + 16 + 2).order(ByteOrder.BIG_ENDIAN)
                .putInt(0xa1b23c4d).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(262144).putInt(1)
                .putInt(1_700_000_000).putInt(123_456_789).putInt(2).putInt(2).put((byte) 0xAA).put((byte) 0xBB);
        Files.write(f, b.array());
        PcapFiles.Header h = PcapFiles.readHeader(f);
        assertEquals(ByteOrder.BIG_ENDIAN, h.order());
        assertTrue(h.nanos());
        List<PcapFiles.Record> back = PcapFiles.readAll(f);
        assertEquals(Instant.ofEpochSecond(1_700_000_000L, 123_456_789L), back.get(0).time());
        assertArrayEquals(new byte[] {(byte) 0xAA, (byte) 0xBB}, back.get(0).data());
    }

    @Test
    void rejectsNonPcapFiles(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("x.txt");
        Files.writeString(f, "this is not a capture file at all");
        IOException e = assertThrows(IOException.class, () -> PcapFiles.readHeader(f));
        assertTrue(e.getMessage().contains("Not a pcap file"), e.getMessage());
    }
}
