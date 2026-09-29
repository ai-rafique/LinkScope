package com.linkscope.modules.netscan;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ArpTableTest {

    @Test
    void parsesWindowsArpOutput() {
        String text = """
                Interface: 192.168.18.24 --- 0x10
                  Internet Address      Physical Address      Type
                  192.168.18.1          a4-2b-8c-11-22-33     dynamic
                  192.168.18.40         00-00-00-00-00-00     invalid
                  192.168.18.255        ff-ff-ff-ff-ff-ff     static
                  224.0.0.22            01-00-5e-00-00-16     static
                  239.255.255.250       01-00-5e-7f-ff-fa     static
                """;
        Map<String, String> arp = ArpTable.parse(text);
        assertEquals(Map.of("192.168.18.1", "a4:2b:8c:11:22:33"), arp);
    }

    @Test
    void parsesLinuxAndMacOutput() {
        String linux = """
                192.168.1.1 dev wlan0 lladdr a4:2b:8c:11:22:33 REACHABLE
                192.168.1.77 dev wlan0  FAILED
                fe80::1 dev wlan0 lladdr a4:2b:8c:11:22:33 router STALE
                """;
        assertEquals(Map.of("192.168.1.1", "a4:2b:8c:11:22:33"), ArpTable.parse(linux));
        String mac = "? (10.0.0.1) at 0:1c:42:0:0:8 on en0 ifscope [ethernet]\n? (10.0.0.9) at (incomplete) on en0 [ethernet]\n";
        assertEquals(Map.of("10.0.0.1", "00:1c:42:00:00:08"), ArpTable.parse(mac), "single-digit groups are padded");
    }

    @Test
    void readNeverThrows() {
        Map<String, String> arp = ArpTable.read();
        assertFalse(arp.containsKey("255.255.255.255"));
    }
}
