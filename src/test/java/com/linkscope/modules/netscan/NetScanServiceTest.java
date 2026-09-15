package com.linkscope.modules.netscan;

import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleStatus;
import com.linkscope.modules.netscan.NetScanService.HostResult;
import com.linkscope.modules.netscan.NetScanService.HostState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.linkscope.TestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetScanServiceTest {
    private final NetScanService service = new NetScanService();

    @BeforeEach
    void setUp() {
        LogSink.get().clear();
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void normalizesBaseSpellings() {
        assertEquals("192.168.1", NetScanService.normalizeBase("192.168.1.x"));
        assertEquals("192.168.1", NetScanService.normalizeBase("192.168.1"));
        assertEquals("192.168.1", NetScanService.normalizeBase("192.168.1.0/24"));
        assertEquals("10.0.0", NetScanService.normalizeBase(" 10.0.0.17 "));
        assertThrows(IllegalArgumentException.class, () -> NetScanService.normalizeBase("192.168"));
        assertThrows(IllegalArgumentException.class, () -> NetScanService.normalizeBase("192.168.1.0/16"));
        assertThrows(IllegalArgumentException.class, () -> NetScanService.normalizeBase("192.999.1.x"));
        assertThrows(IllegalArgumentException.class, () -> NetScanService.normalizeBase(""));
    }

    @Test
    void rangeValidation() {
        assertThrows(IllegalArgumentException.class, () -> service.setRange(10, 5));
        assertThrows(IllegalArgumentException.class, () -> service.setRange(0, 256));
        service.setRange(1, 1);
    }

    @Test
    void loopbackSweepFindsHostsUp() {
        service.setBase("127.0.0.x");
        service.setRange(1, 3);
        service.setTimeoutMs(1000);
        service.setProbePorts(false);
        service.start();
        awaitTrue("sweep done", Duration.ofSeconds(20), () -> service.status() == ModuleStatus.CONNECTED);
        assertEquals(3, service.results().size());
        assertEquals(3, service.progressProperty().get());
        boolean loopbackUp = com.linkscope.TestSupport.snapshot(service.results()).stream()
                .anyMatch(r -> r.ip().equals("127.0.0.1") && r.state() == HostState.UP);
        assertTrue(loopbackUp, "127.0.0.1 must be up");
        for (HostResult r : com.linkscope.TestSupport.snapshot(service.results())) {
            assertTrue(r.lastOctet() >= 1 && r.lastOctet() <= 3);
        }
        assertTrue(service.upCountProperty().get() >= 1);
    }

    @Test
    void stopEndsScanEarly() {
        service.setBase("10.255.255.x");
        service.setRange(0, 255);
        service.setTimeoutMs(2000);
        service.setProbePorts(true);
        service.start();
        awaitTrue("scanning", () -> service.status() == ModuleStatus.CONNECTING);
        service.stop();
        assertEquals(ModuleStatus.DISCONNECTED, service.status());
        assertTrue(!service.isRunning());
    }
}
