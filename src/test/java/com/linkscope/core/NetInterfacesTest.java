package com.linkscope.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetInterfacesTest {

    @Test
    void base24DropsTheLastOctet() {
        assertEquals("192.168.18", NetInterfaces.base24("192.168.18.24"));
        assertEquals("10.0.0", NetInterfaces.base24("10.0.0.1"));
    }

    @Test
    void ipv4NetworksNeverThrowsAndOrdersLoopbackLast() {
        List<NetInterfaces.Ipv4Network> nets = NetInterfaces.ipv4Networks();
        assertNotNull(nets);
        boolean seenLoopback = false;
        for (NetInterfaces.Ipv4Network n : nets) {
            if (n.loopback()) {
                seenLoopback = true;
            } else {
                assertFalse(seenLoopback, "a non-loopback address after loopback: " + n.label());
            }
            assertTrue(n.prefix() >= 0 && n.prefix() <= 32, n.cidr());
            assertEquals(NetInterfaces.base24(n.address()), n.base24());
        }
    }

    @Test
    void parsesRoutingTables() {
        String windows = """
                IPv4 Route Table
                ===========================================================================
                Active Routes:
                Network Destination        Netmask          Gateway       Interface  Metric
                          0.0.0.0          0.0.0.0     192.168.18.1    192.168.18.24     50
                        127.0.0.0        255.0.0.0         On-link         127.0.0.1    331
                """;
        List<NetInterfaces.Gateway> w = NetInterfaces.parseWindowsRoutes(windows);
        assertEquals(1, w.size());
        assertEquals("192.168.18.1", w.get(0).address());
        assertEquals("192.168.18.24", w.get(0).viaAddress());

        List<NetInterfaces.Gateway> l = NetInterfaces.parseLinuxRoutes(
                "default via 192.168.1.1 dev wlan0 proto dhcp src 192.168.1.5 metric 600\n192.168.1.0/24 dev wlan0 proto kernel scope link\n");
        assertEquals("192.168.1.1", l.get(0).address());
        assertEquals("192.168.1.5", l.get(0).viaAddress());

        List<NetInterfaces.Gateway> m = NetInterfaces.parseMacRoutes("Destination        Gateway            Flags\ndefault            10.0.0.1           UGScg\n");
        assertEquals("10.0.0.1", m.get(0).address());
    }

    @Test
    void defaultGatewaysNeverThrows() {
        assertNotNull(NetInterfaces.defaultGateways());
    }
}
