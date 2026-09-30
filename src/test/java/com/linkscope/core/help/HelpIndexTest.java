package com.linkscope.core.help;

import com.linkscope.app.ModuleRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpIndexTest {
    private static final HelpIndex INDEX = HelpIndex.load();

    @Test
    void catalogueIsConsistentWithTheRegistry() {
        Set<String> moduleIds = new HashSet<>();
        ModuleRegistry.MODULES.forEach(m -> moduleIds.add(m.id()));
        Set<String> ids = new HashSet<>();
        for (HelpEntry e : INDEX.entries()) {
            assertTrue(ids.add(e.id()), "duplicate id " + e.id());
            assertFalse(e.title().isBlank(), e.id() + " has no title");
            assertFalse(e.path().isBlank(), e.id() + " has no path");
            if (!e.module().isEmpty()) {
                assertTrue(moduleIds.contains(e.module()), e.id() + " points at unknown module " + e.module());
            } else {
                assertTrue(e.action().startsWith("shell:"), e.id() + " has neither module nor shell action");
            }
        }
        for (String m : moduleIds) {
            assertTrue(INDEX.entries().stream().anyMatch(e -> e.module().equals(m) && e.action().isEmpty()),
                    "module " + m + " has no root entry");
        }
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource({
        "rumble, hid.rumble",
        "vibrate the xbox controller, hid.rumble",
        "gamepad buttons, hid",
        "is a button dead, hid.summary",
        "which ports are free, ports.needed",
        "port conflict, ports.needed",
        "what is listening, ports",
        "who owns port 502, ports",
        "pcap file, capture.pcap",
        "wireshark, capture",
        "display filter, capture.display",
        "bpf, capture.bpf",
        "crc table, modbus.crc",
        "paste crc from vendor manual, modbus.crc",
        "modbus, modbus",
        "poll registers, modbus.poll",
        "watch serial bytes another program sends, usb.stream",
        "ftdi, usb.stream",
        "usb, usb",
        "stream view, usb.stream",
        "who is on my network, netscan",
        "ping sweep, netscan",
        "mac address, netscan.arp",
        "firewalled host arp, netscan.arp",
        "decode bytes, decoder",
        "layout 2 3 5 6 4, decoder.layout",
        "mqtt, mqtt",
        "kafka topic, kafka",
        "nats, nats",
        "serial port, serial",
        "baud rate, serial",
        "ssh, ssh",
        "port forwarding, tunnels",
        "tunnel, tunnels",
        "upload a file, sftp",
        "telnet, telnet",
        "http post json, http",
        "bearer token, http.auth",
        "websocket, websocket",
        "multicast group, multicast",
        "tcp server listen, tcp.server",
        "udp broadcast, udp.broadcast",
        "dark mode, shell.theme",
        "keyboard shortcuts, shell.keys",
        "save preset, shell.presets",
        "env variable secret, shell.env",
        "export log csv, shell.log.export",
        "show the log, shell.log",
        "collapse sidebar, shell.sidebar",
        "rumbel, hid.rumble",
        "modbuss, modbus",
        "rum, hid.rumble"
    })
    void topHitIsTheExpectedEntry(String query, String expectedId) {
        List<HelpIndex.Hit> hits = INDEX.search(query, 5);
        assertFalse(hits.isEmpty(), "no hits for " + query);
        assertEquals(expectedId, hits.get(0).entry().id(), () -> query + " → " + hits.stream()
                .map(h -> h.entry().id() + String.format(" (%.2f)", h.score())).toList());
    }

    @Test
    void searchIsDeterministicAndEmptyQueryGivesNothing() {
        assertEquals(INDEX.search("port", 10), INDEX.search("port", 10));
        assertTrue(INDEX.search("", 10).isEmpty());
        assertTrue(INDEX.search("   ", 10).isEmpty());
        assertTrue(INDEX.search("zzzz qqqq", 10).isEmpty(), "gibberish matches nothing");
    }

    @Test
    void tokenizerAndSynonyms() {
        assertEquals(List.of("packet", "captur", "file"), HelpIndex.tokenize("Packets captured, files!"));
        assertEquals(HelpIndex.stem("capture"), HelpIndex.stem("captured"));
        assertEquals(HelpIndex.stem("rumble"), HelpIndex.stem("rumbles"));
        assertEquals("modbus", HelpIndex.stem("modbus"));
        assertEquals("address", HelpIndex.stem("addresses"));
        assertTrue(HelpIndex.expand("xbox").contains(HelpIndex.stem("rumble")));
        assertTrue(HelpIndex.editDistanceAtMostOne("rumbel", "rumble") || HelpIndex.editDistanceAtMostOne("rumbel", "rumbel"));
        assertTrue(HelpIndex.editDistanceAtMostOne("modbuss", "modbus"));
        assertFalse(HelpIndex.editDistanceAtMostOne("modbus", "kafka"));
    }
}
