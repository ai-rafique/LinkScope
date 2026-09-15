package com.linkscope.modules.multicast;

import com.linkscope.TestSupport;
import com.linkscope.core.LogEntry;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.linkscope.TestSupport.awaitTrue;
import static com.linkscope.TestSupport.logged;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Needs a multicast-capable interface; skipped when SKIP_INTEGRATION is set or when the
 * host has none (typical of minimal CI containers).
 */
class MulticastServiceTest {
    private final MulticastService service = new MulticastService();

    @BeforeEach
    void setUp() {
        Assumptions.assumeFalse(TestSupport.integrationSkipped(), "SKIP_INTEGRATION set");
        List<NetworkInterface> candidates = MulticastService.candidateInterfaces();
        Assumptions.assumeFalse(candidates.isEmpty(), "no multicast-capable interface");
        LogSink.get().clear();
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void joinSendReceiveWithLoopback() {
        service.setGroup("239.255.77.1");
        service.setPort(0);
        service.setTtl(0);
        service.setLoopback(true);
        service.start();
        awaitTrue("joined or failed", () -> service.status() == ModuleStatus.CONNECTED
                || service.status() == ModuleStatus.ERROR);
        Assumptions.assumeTrue(service.status() == ModuleStatus.CONNECTED,
                "multicast join not possible on this host:\n" + TestSupport.dumpLog());

        // Port 0 bound an ephemeral port; send to the group on that port.
        int boundPort = service.localPort();
        assertTrue(boundPort > 0, "bound port");
        service.setPort(boundPort);
        byte[] hello = "mcast".getBytes(StandardCharsets.UTF_8);
        service.send(hello);
        awaitTrue("TX logged", () -> logged(MulticastService.TAG, LogEntry.Kind.TX, hello));
        awaitTrue("own datagram looped back", () -> logged(MulticastService.TAG, LogEntry.Kind.RX, hello));

        service.stop();
        awaitTrue("left group", () -> service.status() == ModuleStatus.DISCONNECTED);
    }

    @Test
    void rejectsNonMulticastAddress() {
        service.setGroup("192.168.1.1");
        service.setPort(0);
        service.start();
        awaitTrue("error status", () -> service.status() == ModuleStatus.ERROR);
        assertFalse(LogSink.get().entries().isEmpty());
        // Other modules' late INFO lines can land in the shared log, so look for our error rather than the last line.
        assertTrue(LogSink.get().entries().stream().anyMatch(e -> e.kind() == LogEntry.Kind.ERROR
                && MulticastService.TAG.equals(e.module()) && e.note().contains("not a multicast address")));
    }
}
