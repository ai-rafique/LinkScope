package com.linkscope.modules.usb;

import com.linkscope.modules.capture.PacketRow;
import com.linkscope.modules.capture.UsbPcapDissector;
import com.linkscope.modules.capture.UsbPcapDissectorTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsbStreamTest {
    private static final Instant T0 = Instant.parse("2026-09-21T12:00:00Z");
    private static final byte[] MODBUS_REQUEST = {1, 3, 0, 0, 0, 10, (byte) 0xC5, (byte) 0xCD};

    private static PacketRow row(long n, long millis, byte[] raw) {
        return UsbPcapDissector.dissect(raw, n, T0.plusMillis(millis), millis / 1000.0);
    }

    /** A Modbus poll over an FTDI adapter (device 1.7): OUT request, IN response split across two transfers with status bytes. */
    private static List<PacketRow> ftdiConversation() {
        List<PacketRow> rows = new ArrayList<>();
        byte[] descriptor = UsbPcapDissectorTest.record(1, 7, 0x80, UsbPcapDissector.TRANSFER_CONTROL, 0x000B, true, 0, new byte[] {3},
                UsbPcapDissectorTest.ftdiDeviceDescriptor());
        rows.add(row(1, 0, descriptor));
        rows.add(row(2, 5, UsbPcapDissectorTest.record(1, 7, 0x02, UsbPcapDissector.TRANSFER_BULK, 0x0009, false, 0, new byte[0], MODBUS_REQUEST)));
        // completion of the OUT transfer carries no data
        rows.add(row(3, 6, UsbPcapDissectorTest.record(1, 7, 0x02, UsbPcapDissector.TRANSFER_BULK, 0x0009, true, 0, new byte[0], new byte[0])));
        // FTDI status polls with nothing behind them
        rows.add(row(4, 10, UsbPcapDissectorTest.record(1, 7, 0x81, UsbPcapDissector.TRANSFER_BULK, 0x0009, true, 0, new byte[0], new byte[] {0x01, 0x60})));
        // response: 01 03 14 <20 bytes> crc, delivered as 2+? then the rest 3 ms later
        byte[] response = new byte[3 + 20 + 2];
        response[0] = 1;
        response[1] = 3;
        response[2] = 20;
        for (int i = 0; i < 20; i++) {
            response[3 + i] = (byte) i;
        }
        int crc = com.linkscope.core.protocol.ModbusDecoder.crc16(response, 0, 23);
        response[23] = (byte) (crc & 0xFF);
        response[24] = (byte) (crc >> 8);
        byte[] first = new byte[2 + 10];
        first[0] = 0x01;
        first[1] = 0x60;
        System.arraycopy(response, 0, first, 2, 10);
        byte[] second = new byte[2 + 15];
        second[0] = 0x01;
        second[1] = 0x60;
        System.arraycopy(response, 10, second, 2, 15);
        rows.add(row(5, 20, UsbPcapDissectorTest.record(1, 7, 0x81, UsbPcapDissector.TRANSFER_BULK, 0x0009, true, 0, new byte[0], first)));
        rows.add(row(6, 23, UsbPcapDissectorTest.record(1, 7, 0x81, UsbPcapDissector.TRANSFER_BULK, 0x0009, true, 0, new byte[0], second)));
        // another device's HID report must not leak in
        rows.add(row(7, 24, UsbPcapDissectorTest.record(1, 5, 0x81, UsbPcapDissector.TRANSFER_INTERRUPT, 0x0009, true, 0, new byte[0], new byte[] {0, 0, 4, 0, 0, 0, 0, 0})));
        // a stalled transfer carries no usable data
        rows.add(row(8, 30, UsbPcapDissectorTest.record(1, 7, 0x81, UsbPcapDissector.TRANSFER_BULK, 0x0009, true, 0xC0000004, new byte[0], new byte[] {9, 9, 9})));
        return rows;
    }

    @Test
    void devicesEndpointsAndVendorDetection() {
        List<PacketRow> rows = ftdiConversation();
        assertEquals(List.of("1.7", "1.5"), UsbStream.devices(rows));
        assertEquals(List.of(1, 2), UsbStream.endpoints(rows, "1.7"));
        assertEquals("0403:6001", UsbStream.vendorProduct(rows, "1.7"));
        assertTrue(UsbStream.looksLikeFtdi(rows, "1.7"));
        assertFalse(UsbStream.looksLikeFtdi(rows, "1.5"));
    }

    @Test
    void ftdiStatusBytesAreStrippedAndCloseTransfersMerge() {
        List<PacketRow> rows = ftdiConversation();
        UsbStream.Options o = new UsbStream.Options(true, 64, 10, true, true);
        List<UsbStream.Segment> segs = UsbStream.build(rows, "1.7", null, o);
        assertEquals(2, segs.size(), "OUT request, then one merged IN response; bare status polls vanish");
        assertEquals(UsbStream.Direction.OUT, segs.get(0).direction());
        assertEquals(2, segs.get(0).endpoint());
        assertArrayEquals(MODBUS_REQUEST, segs.get(0).data());
        UsbStream.Segment in = segs.get(1);
        assertEquals(UsbStream.Direction.IN, in.direction());
        assertEquals(2, in.transfers());
        assertEquals(25, in.data().length);
        assertEquals(0.020, in.relativeSeconds(), 1e-9);
        assertArrayEquals(in.data(), UsbStream.concat(segs, UsbStream.Direction.IN));
        assertEquals("OUT 8 B in 1 transfer(s) · IN 25 B in 2 transfer(s) · 2 segment(s)", UsbStream.summary(segs));
    }

    @Test
    void withoutStrippingTheStatusBytesStayAndAGapSplitsSegments() {
        List<PacketRow> rows = ftdiConversation();
        List<UsbStream.Segment> raw = UsbStream.build(rows, "1.7", null, new UsbStream.Options(false, 64, 10, true, true));
        assertEquals(2, raw.size(), "status poll + response merge into one IN segment (each within 10 ms of the last)");
        assertEquals(2 + 12 + 17, raw.get(1).data().length, "status bytes stay when not stripped");
        assertEquals(3, raw.get(1).transfers());
        List<UsbStream.Segment> tight = UsbStream.build(rows, "1.7", null, new UsbStream.Options(true, 64, 1, true, true));
        assertEquals(3, tight.size(), "with a 1 ms gap the two response transfers stay apart");
        List<UsbStream.Segment> inOnly = UsbStream.build(rows, "1.7", 1, new UsbStream.Options(true, 64, 10, true, false));
        assertEquals(1, inOnly.size());
        assertEquals(UsbStream.Direction.IN, inOnly.get(0).direction());
    }

    @Test
    void stripFtdiHandlesMultiPacketTransfers() {
        byte[] data = new byte[64 + 5];
        data[0] = 1;
        data[1] = 0x60;
        for (int i = 2; i < 64; i++) {
            data[i] = (byte) 0xAA;
        }
        data[64] = 1;
        data[65] = 0x60;
        data[66] = 0x11;
        data[67] = 0x22;
        data[68] = 0x33;
        byte[] out = UsbStream.stripFtdi(data, 64);
        assertEquals(62 + 3, out.length);
        assertEquals((byte) 0xAA, out[0]);
        assertEquals(0x11, out[62]);
        assertEquals(0x33, out[64]);
        assertEquals(0, UsbStream.stripFtdi(new byte[] {1, 0x60}, 64).length);
    }

    @Test
    void renderShowsHexAsciiAndModbusDecoding() {
        List<PacketRow> rows = ftdiConversation();
        List<UsbStream.Segment> segs = UsbStream.build(rows, "1.7", null, new UsbStream.Options(true, 64, 10, true, true));
        String text = UsbStream.render(segs, UsbStream.Format.HEX_ASCII, UsbStream.Decode.MODBUS_RTU, 1000);
        assertTrue(text.contains("+0000.005000  OUT  ep2       8 B"), text);
        assertTrue(text.contains("01 03 00 00 00 0a c5 cd"), text);
        assertTrue(text.contains("⇒ Modbus RTU: "), text);
        assertTrue(text.contains("FC 03"), text);
        assertTrue(text.contains("+0000.020000  IN   ep1      25 B  (2 transfers)"), text);
        String ascii = UsbStream.render(segs, UsbStream.Format.ASCII, UsbStream.Decode.NONE, 1);
        assertTrue(ascii.startsWith("… 1 earlier segment(s) not shown"), ascii);
        assertFalse(ascii.contains("⇒"));
        String hex = UsbStream.render(segs, UsbStream.Format.HEX, UsbStream.Decode.NONE, 1000);
        assertFalse(hex.contains("|"), "hex-only has no ASCII gutter");
    }
}
