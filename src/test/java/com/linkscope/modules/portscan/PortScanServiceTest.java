package com.linkscope.modules.portscan;

import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleStatus;
import com.linkscope.modules.portscan.PortScanService.PortResult;
import com.linkscope.modules.portscan.PortScanService.PortState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.linkscope.TestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortScanServiceTest {
    private final PortScanService service = new PortScanService();

    @BeforeEach
    void setUp() {
        LogSink.get().clear();
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void parsesRangesAndLists() {
        List<int[]> r = PortScanService.parseRanges(" 1-10, 22 ,8000-8002");
        assertEquals(3, r.size());
        assertEquals(14, PortScanService.countPorts(r));
        assertThrows(IllegalArgumentException.class, () -> PortScanService.parseRanges("0-10"));
        assertThrows(IllegalArgumentException.class, () -> PortScanService.parseRanges("10-5"));
        assertThrows(IllegalArgumentException.class, () -> PortScanService.parseRanges("abc"));
        assertThrows(IllegalArgumentException.class, () -> PortScanService.parseRanges(""));
        assertThrows(IllegalArgumentException.class, () -> PortScanService.parseRanges("1-70000"));
    }

    @Test
    void classifiesListeningInUseAndFree() throws IOException {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        try (ServerSocket listener = new ServerSocket(0, 50, lo);
             Socket client = new Socket()) {
            client.connect(new InetSocketAddress(lo, listener.getLocalPort()), 2000);
            int listeningPort = listener.getLocalPort();
            int inUsePort = client.getLocalPort();
            int freePort;
            try (ServerSocket probe = new ServerSocket(0, 1, lo)) {
                freePort = probe.getLocalPort();
            }

            PortOwners.Snapshot synthetic = new PortOwners.Snapshot(Map.of(listeningPort, "junit"), Set.of(inUsePort));
            PortResult a = service.probe(lo, listeningPort, synthetic);
            assertEquals(PortState.LISTENING, a.state());
            assertEquals("junit", a.owner());

            PortResult b = service.probe(lo, inUsePort, synthetic);
            assertEquals(PortState.IN_USE, b.state(), b.detail());

            PortResult c = service.probe(lo, freePort, synthetic);
            assertEquals(PortState.FREE, c.state(), c.detail());

            // The real socket table must see our established client connection and the listener.
            PortOwners.Snapshot real = PortOwners.snapshot();
            Assumptions.assumeFalse(real.isEmpty(), "netstat/ss/lsof not available here");
            assertTrue(real.activePorts().contains(inUsePort), "client port " + inUsePort + " in socket table");
            assertTrue(real.listeningOwners().containsKey(listeningPort), "listener " + listeningPort + " in socket table");
            assertEquals(PortState.IN_USE, service.probe(lo, inUsePort, real).state());
        }
    }

    @Test
    void scanRunsOverRangeAndReportsSummary() throws IOException {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        try (ServerSocket listener = new ServerSocket(0, 50, lo)) {
            int port = listener.getLocalPort();
            service.setAddress("127.0.0.1");
            service.setRanges((port - 1) + "-" + (port + 1));
            service.start();
            awaitTrue("scan done", Duration.ofSeconds(20), () -> service.status() == ModuleStatus.CONNECTED);
            assertEquals(3, service.results().size());
            assertTrue(service.results().stream().anyMatch(r -> r.port() == port && r.state() == PortState.LISTENING));
            assertTrue(LogSink.get().entries().stream().anyMatch(e -> e.note() != null && e.note().startsWith("Scan complete")));
        }
    }

    @Test
    void invalidRangeIsAnErrorNotACrash() {
        assertThrows(IllegalArgumentException.class, () -> service.setRanges("nope"));
    }

    @Test
    void localAddressesAlwaysIncludeLoopback() {
        List<PortScanService.LocalAddress> list = PortScanService.localAddresses();
        assertFalse(list.isEmpty());
        assertEquals("127.0.0.1", list.get(0).ip());
    }

    @Test
    void ownerLookupNeverThrowsAndParsesEndpoints() {
        assertNotNull(PortOwners.listening());
        assertEquals(135, PortOwners.portOf("0.0.0.0:135"));
        assertEquals(22, PortOwners.portOf("[::]:22"));
        assertEquals(8080, PortOwners.portOf("127.0.0.1:8080 (LISTEN)"));
        assertEquals(-1, PortOwners.portOf("nonsense"));
    }
}
