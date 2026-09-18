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
    void errorsAreReadable() {
        assertThrows(IllegalArgumentException.class, () -> DisplayFilter.compile("ip.addr =="));
        assertThrows(IllegalArgumentException.class, () -> DisplayFilter.compile("(tcp"));
        assertThrows(IllegalArgumentException.class, () -> DisplayFilter.compile("port == abc"));
        assertThrows(IllegalArgumentException.class, () -> DisplayFilter.compile("bogus.field == 1"));
        assertThrows(IllegalArgumentException.class, () -> DisplayFilter.compile("ip.addr > 1"));
        assertThrows(IllegalArgumentException.class, () -> DisplayFilter.compile("contains \"open"));
    }
}
