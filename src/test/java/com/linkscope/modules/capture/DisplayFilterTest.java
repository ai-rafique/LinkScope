package com.linkscope.modules.capture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayFilterTest {

    private static PacketRow row(String src, String dst, Integer sp, Integer dp, String proto, String app, int len, String info, String payload) {
        byte[] p = payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8);
        return new PacketRow(1, Instant.EPOCH, 0, src, dst, sp, dp, proto, app, len, info, new byte[len], p, "");
    }

    private static final PacketRow MQTT = row("192.168.1.5", "192.168.1.10", 51000, 1883, "TCP", "MQTT", 120, "51000 → 1883 [PSH,ACK]", "MQTT hello");
    private static final PacketRow MODBUS_UDP = row("10.0.0.7", "239.192.10.4", 40000, 16004, "UDP", "", 64, "40000 → 16004 Len=22", "");
    private static final PacketRow ARP = row("192.168.1.1", "192.168.1.5", null, null, "ARP", "", 42, "Who has 192.168.1.5? Tell 192.168.1.1", null);
    private static final PacketRow USB_BULK = new PacketRow(9, Instant.EPOCH, 0, "1.5.1", "host", null, null, "USB", "USB Bulk", 91,
            "URB_BULK in Len=64", new byte[91], "serial bytes".getBytes(StandardCharsets.UTF_8), "",
            java.util.Map.of("usb.bus", "1", "usb.device", "5", "usb.addr", "1.5", "usb.endpoint", "1", "usb.dir", "in",
                    "usb.type", "bulk", "usb.status", "success"));

    private static boolean m(String filter, PacketRow r) {
        Predicate<PacketRow> p = DisplayFilter.compile(filter);
        return p.test(r);
    }

    @Test
    void emptyFilterMatchesEverything() {
        assertTrue(m("", MQTT));
        assertTrue(m("   ", ARP));
    }

    @Test
    void bareProtocolAndAppNames() {
        assertTrue(m("tcp", MQTT));
        assertTrue(m("mqtt", MQTT));
        assertFalse(m("udp", MQTT));
        assertTrue(m("udp", MODBUS_UDP));
        assertTrue(m("arp", ARP));
        assertTrue(m("ip", MQTT));
        assertFalse(m("ip", ARP));
        assertFalse(m("ipv6", MQTT));
    }

    @Test
    void addressAndPortComparisons() {
        assertTrue(m("ip.addr == 192.168.1.10", MQTT));
        assertTrue(m("ip.src == 192.168.1.5", MQTT));
        assertFalse(m("ip.dst == 192.168.1.5", MQTT));
        assertTrue(m("ip.addr == 192.168.1.", MQTT), "prefix match with trailing dot");
        assertTrue(m("ip.addr != 10.0.0.1", MQTT));
        assertFalse(m("ip.addr != 192.168.1.5", MQTT), "!= must hold for both ends");
        assertTrue(m("port == 1883", MQTT));
        assertTrue(m("dstport == 16004", MODBUS_UDP));
        assertFalse(m("srcport == 16004", MODBUS_UDP));
        assertTrue(m("port >= 1024 and port < 2000", MQTT));
        assertFalse(m("port == 80", ARP), "no ports on ARP");
        assertTrue(m("len > 100", MQTT));
        assertFalse(m("len > 100", ARP));
    }

    @Test
    void booleanLogicAndParentheses() {
        assertTrue(m("udp and port == 16004", MODBUS_UDP));
        assertFalse(m("udp and port == 16004", MQTT));
        assertTrue(m("mqtt or nats", MQTT));
        assertTrue(m("not arp", MQTT));
        assertTrue(m("(tcp or udp) and not port == 22", MODBUS_UDP));
        assertTrue(m("tcp && !arp || udp", ARP) == false);
        assertTrue(m("ip.addr == 239.192.10.4 and udp", MODBUS_UDP));
    }

    @Test
    void containsSearchesPayloadInfoAndHex() {
        assertTrue(m("contains \"hello\"", MQTT));
        assertTrue(m("contains hello", MQTT));
        assertTrue(m("contains PSH", MQTT), "info text counts too");
        assertFalse(m("contains hello", ARP));
        assertTrue(m("contains 0x0103", MODBUS_UDP), "hex needle against payload bytes");
        assertTrue(m("info contains \"Who has\"", ARP));
        assertTrue(m("payload contains hello", MQTT));
    }

    @Test
    void usbFieldsAndBareWords() {
        assertTrue(m("usb", USB_BULK));
        assertFalse(m("usb", MQTT));
        assertTrue(m("bulk", USB_BULK));
        assertFalse(m("control", USB_BULK));
        assertFalse(m("ip", USB_BULK), "bus.device.endpoint is not an IPv4 address");
        assertFalse(m("tcp", USB_BULK));
        assertTrue(m("usb.addr == 1.5", USB_BULK));
        assertTrue(m("usb.addr == 1.", USB_BULK), "prefix match selects a whole bus");
        assertFalse(m("usb.addr == 1.6", USB_BULK));
        assertTrue(m("usb.endpoint == 1", USB_BULK));
        assertTrue(m("usb.endpoint > 0 and usb.dir == in", USB_BULK));
        assertFalse(m("usb.dir == out", USB_BULK));
        assertTrue(m("usb.type == bulk and contains serial", USB_BULK));
        assertTrue(m("usb.status != stall_pid", USB_BULK));
        assertFalse(m("usb.addr == 1.5", MQTT), "network rows have no USB fields");
        assertFalse(m("usb.endpoint == 1", MQTT));
        assertThrows(IllegalArgumentException.class, () -> DisplayFilter.compile("bogus.addr == 1.5"));
    }

    @Test
    void errorsAreReadable() {
        assertThrows(IllegalArgumentException.class, () -> DisplayFilter.compile("ip.addr =="));
        assertThrows(IllegalArgumentException.class, () -> DisplayFilter.compile("(tcp"));
        assertThrows(IllegalArgumentException.class, () -> DisplayFilter.compile("port == abc"));
        assertThrows(IllegalArgumentException.class, () -> DisplayFilter.compile("bogus.field == 1"));
        assertThrows(IllegalArgumentException.class, () -> DisplayFilter.compile("ip.addr > 1"));
        assertThrows(IllegalArgumentException.class, () -> DisplayFilter.compile("contains \"open"));
    }
}
