package com.linkscope.modules.capture;

import org.junit.jupiter.api.Test;
import org.pcap4j.packet.UnknownPacket;
import org.pcap4j.packet.namednumber.DataLinkType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** USBPcap records built by hand; no driver involved. */
public class UsbPcapDissectorTest {

    /** Builds one USBPcap record: 27-byte header, optional transfer-specific header bytes, then data. */
    public static byte[] record(int bus, int device, int endpoint, int transfer, int function, boolean fromDevice, int status,
                         byte[] extraHeader, byte[] data) {
        int headerLen = UsbPcapDissector.HEADER_MIN + extraHeader.length;
        ByteBuffer b = ByteBuffer.allocate(headerLen + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) headerLen);
        b.putLong(0xFFFFAB01L);
        b.putInt(status);
        b.putShort((short) function);
        b.put((byte) (fromDevice ? 1 : 0));
        b.putShort((short) bus);
        b.putShort((short) device);
        b.put((byte) endpoint);
        b.put((byte) transfer);
        b.putInt(data.length);
        b.put(extraHeader);
        b.put(data);
        return b.array();
    }

    public static byte[] setup(int bmRequestType, int bRequest, int wValue, int wIndex, int wLength) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) bmRequestType).put((byte) bRequest).putShort((short) wValue).putShort((short) wIndex).putShort((short) wLength);
        return b.array();
    }

    /** FTDI FT232R device descriptor. */
    public static byte[] ftdiDeviceDescriptor() {
        return new byte[] {18, 1, 0x00, 0x02, 0, 0, 0, 8, 0x03, 0x04, 0x01, 0x60, 0x00, 0x06, 1, 2, 3, 1};
    }

    private static PacketRow row(byte[] raw) {
        return UsbPcapDissector.dissect(raw, 1, Instant.EPOCH, 0);
    }

    @Test
    void bulkInFromSerialAdapter() {
        byte[] data = "01 03 00 00 00 0A C5 CD".getBytes(StandardCharsets.US_ASCII);
        PacketRow r = row(record(1, 5, 0x81, UsbPcapDissector.TRANSFER_BULK, 0x0009, true, 0, new byte[0], data));
        assertEquals("1.5.1", r.src());
        assertEquals("host", r.dst());
        assertEquals("USB", r.protocol());
        assertEquals("USB Bulk", r.app());
        assertEquals("USB Bulk", r.protocolColumn());
        assertNull(r.srcPort());
        assertEquals("URB_BULK in Len=" + data.length, r.info());
        assertArrayEquals(data, r.payload());
        assertEquals("1.5", r.field("usb.addr"));
        assertEquals("1", r.field("usb.endpoint"));
        assertEquals("in", r.field("usb.dir"));
        assertEquals("bulk", r.field("usb.type"));
        assertEquals("bulk_or_interrupt_transfer", r.field("usb.function"));
        assertEquals("success", r.field("usb.status"));
        assertTrue(r.details().contains("USB: bus 1, device 5, endpoint 1 (IN), device → host"), r.details());
        assertTrue(r.details().contains("Payload: " + data.length + " byte(s)"), r.details());
    }

    @Test
    void controlSetupGetDeviceDescriptor() {
        byte[] raw = record(1, 5, 0x80, UsbPcapDissector.TRANSFER_CONTROL, 0x000B, false, 0, new byte[] {0},
                setup(0x80, 6, 0x0100, 0, 18));
        PacketRow r = row(raw);
        assertEquals("host", r.src());
        assertEquals("1.5.0", r.dst());
        assertEquals("GET DESCRIPTOR Request DEVICE Len=8", r.info());
        assertEquals("setup", r.field("usb.stage"));
        assertEquals("get_descriptor", r.field("usb.request"));
        assertEquals("standard", r.field("usb.request_type"));
        assertEquals("device", r.field("usb.descriptor"));
        assertTrue(r.details().contains("bRequest 0x06 GET_DESCRIPTOR, wValue 0x0100, wIndex 0x0000, wLength 18"), r.details());
    }

    @Test
    void controlResponseWithDeviceDescriptor() {
        PacketRow r = row(record(1, 5, 0x80, UsbPcapDissector.TRANSFER_CONTROL, 0x000B, true, 0, new byte[] {3},
                ftdiDeviceDescriptor()));
        assertEquals("GET DESCRIPTOR Response DEVICE (0403:6001) Len=18", r.info());
        assertEquals("complete", r.field("usb.stage"));
        assertTrue(r.details().contains("idVendor 0x0403, idProduct 0x6001"), r.details());
        assertTrue(r.details().contains("USB 2.00"), r.details());
        assertTrue(r.details().contains("device 6.00"), r.details());
        assertTrue(r.details().contains("Defined at interface level"), r.details());
    }

    @Test
    void configurationDescriptorChainIsWalked() {
        byte[] config = {
            9, 2, 32, 0, 1, 1, 0, (byte) 0xA0, 45,          // configuration: 1 interface, bus powered + remote wakeup, 90 mA
            9, 4, 0, 0, 2, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 2,  // interface 0: vendor specific, 2 endpoints
            7, 5, (byte) 0x81, 2, 64, 0, 0,                  // endpoint 0x81 bulk IN 64
            7, 5, 0x02, 2, 64, 0, 0                          // endpoint 0x02 bulk OUT 64
        };
        PacketRow r = row(record(1, 5, 0x80, UsbPcapDissector.TRANSFER_CONTROL, 0x000B, true, 0, new byte[] {3}, config));
        assertEquals("GET DESCRIPTOR Response CONFIGURATION Len=32", r.info());
        assertTrue(r.details().contains("CONFIGURATION descriptor: value 1, 1 interface(s), total 32 byte(s), attributes 0xA0 bus-powered remote-wakeup, max power 90 mA"),
                r.details());
        assertTrue(r.details().contains("INTERFACE 0 alt 0: Vendor specific (class 0xFF, sub 0xFF, proto 0xFF), 2 endpoint(s)"), r.details());
        assertTrue(r.details().contains("ENDPOINT 0x81 IN Bulk, max packet 64, interval 0"), r.details());
        assertTrue(r.details().contains("ENDPOINT 0x02 OUT Bulk, max packet 64, interval 0"), r.details());
    }

    @Test
    void stringDescriptorIsDecoded() {
        byte[] text = "FTDI".getBytes(StandardCharsets.UTF_16LE);
        byte[] desc = new byte[2 + text.length];
        desc[0] = (byte) desc.length;
        desc[1] = 3;
        System.arraycopy(text, 0, desc, 2, text.length);
        PacketRow r = row(record(1, 5, 0x80, UsbPcapDissector.TRANSFER_CONTROL, 0x000B, true, 0, new byte[] {3}, desc));
        assertEquals("GET DESCRIPTOR Response STRING Len=" + desc.length, r.info());
        assertTrue(r.details().contains("STRING descriptor: \"FTDI\""), r.details());
    }

    @Test
    void vendorRequestCarriesSerialBridgeHint() {
        PacketRow r = row(record(1, 5, 0x00, UsbPcapDissector.TRANSFER_CONTROL, 0x0017, false, 0, new byte[] {0},
                setup(0x40, 0x03, 0x4138, 0, 0)));
        assertEquals("VENDOR REQUEST 0x03 Request Len=8", r.info());
        assertTrue(r.details().contains("FTDI SET_BAUD_RATE / CP210x SET_LINE_CTL?"), r.details());
        assertEquals("vendor", r.field("usb.request_type"));
    }

    @Test
    void classRequestAndFeatureNames() {
        PacketRow setIdle = row(record(1, 6, 0x00, UsbPcapDissector.TRANSFER_CONTROL, 0x001B, false, 0, new byte[] {0},
                setup(0x21, 0x0A, 0, 0, 0)));
        assertEquals("SET IDLE (HID) Request Len=8", setIdle.info());
        PacketRow clearHalt = row(record(1, 6, 0x00, UsbPcapDissector.TRANSFER_CONTROL, 0x0012, false, 0, new byte[] {0},
                setup(0x02, 0x01, 0, 0x81, 0)));
        assertEquals("CLEAR FEATURE Request ENDPOINT_HALT on endpoint 129 Len=8", clearHalt.info());
    }

    @Test
    void interruptWithStallStatus() {
        PacketRow r = row(record(2, 3, 0x81, UsbPcapDissector.TRANSFER_INTERRUPT, 0x0009, true, 0xC0000004, new byte[0], new byte[0]));
        assertEquals("URB_INTERRUPT in Len=0 [STALL_PID]", r.info());
        assertEquals("stall_pid", r.field("usb.status"));
        assertEquals("interrupt", r.field("usb.type"));
        assertEquals("2.3", r.field("usb.addr"));
    }

    @Test
    void isochronousHeaderAndIrpInfo() {
        ByteBuffer iso = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(1000).putInt(0).putInt(0);
        PacketRow r = row(record(1, 4, 0x82, UsbPcapDissector.TRANSFER_ISOCH, 0x000A, true, 0, iso.array(), new byte[0]));
        assertEquals("URB_ISOCHRONOUS in 0 pkt Len=0", r.info());
        assertTrue(r.details().contains("Isochronous: start frame 1000, 0 packet(s), 0 error(s)"), r.details());

        PacketRow irp = row(record(1, 5, 0x00, UsbPcapDissector.TRANSFER_IRP_INFO, 0x0000, false, 0, new byte[0], new byte[0]));
        assertEquals("URB_FUNCTION_SELECT_CONFIGURATION", irp.info());
        assertEquals("USB IRP info", irp.protocolColumn());
    }

    @Test
    void truncatedRecordDoesNotThrow() {
        PacketRow r = row(new byte[] {1, 2, 3});
        assertEquals("USB", r.protocol());
        assertTrue(r.info().startsWith("Truncated USBPcap header"));
    }

    @Test
    void packetDissectorRoutesByLinkType() {
        byte[] raw = record(1, 5, 0x81, UsbPcapDissector.TRANSFER_BULK, 0x0009, true, 0, new byte[0], new byte[] {1, 2, 3});
        UnknownPacket packet = UnknownPacket.newPacket(raw, 0, raw.length);
        PacketRow usb = PacketDissector.dissect(packet, DataLinkType.getInstance(249), 1, Instant.EPOCH, 0);
        assertEquals("USB", usb.protocol());
        assertEquals("1.5.1", usb.src());
        PacketRow eth = PacketDissector.dissect(packet, DataLinkType.EN10MB, 1, Instant.EPOCH, 0);
        assertEquals("?", eth.protocol(), "the same bytes are not an Ethernet frame");
    }
}
