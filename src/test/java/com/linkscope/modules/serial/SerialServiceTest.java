package com.linkscope.modules.serial;

import com.linkscope.TestSupport;
import com.linkscope.core.LogEntry;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.linkscope.TestSupport.awaitTrue;
import static com.linkscope.TestSupport.logged;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hardware-dependent: needs a connected virtual (or physical) port pair named in
 * LINKSCOPE_SERIAL_PAIR, e.g. {@code COM5,COM6} or {@code /dev/pts/3,/dev/pts/4}.
 * Skipped otherwise, and always when SKIP_INTEGRATION is set.
 */
class SerialServiceTest {
    private final SerialService a = new SerialService();
    private final SerialService b = new SerialService();
    private String portA;
    private String portB;

    @BeforeEach
    void setUp() {
        Assumptions.assumeFalse(TestSupport.integrationSkipped(), "SKIP_INTEGRATION set");
        String pair = System.getenv("LINKSCOPE_SERIAL_PAIR");
        Assumptions.assumeTrue(pair != null && pair.contains(","), "LINKSCOPE_SERIAL_PAIR not set");
        String[] parts = pair.split(",");
        portA = parts[0].trim();
        portB = parts[1].trim();
        LogSink.get().clear();
    }

    @AfterEach
    void tearDown() {
        a.stop();
        b.stop();
    }

    private static void open(SerialService s, String port) {
        s.setPortName(port);
        s.setBaudRate(115200);
        s.start();
        awaitTrue("open " + port, () -> s.status() == ModuleStatus.CONNECTED || s.status() == ModuleStatus.ERROR);
        Assumptions.assumeTrue(s.status() == ModuleStatus.CONNECTED, "could not open " + port + ":\n" + TestSupport.dumpLog());
    }

    @Test
    void roundTripAcrossVirtualPair() {
        open(a, portA);
        open(b, portB);

        byte[] toB = "AT+TEST\r\n".getBytes(StandardCharsets.UTF_8);
        a.send(toB);
        awaitTrue("TX logged", () -> logged(SerialService.TAG, LogEntry.Kind.TX, toB));
        awaitTrue("B received", () -> receivedTotal(portB, toB.length));

        byte[] toA = {0x00, (byte) 0xff, 0x7f};
        b.send(toA);
        awaitTrue("A received", () -> receivedTotal(portA, toA.length));

        a.stop();
        awaitTrue("A closed", () -> a.status() == ModuleStatus.DISCONNECTED);
    }

    @Test
    void openingMissingPortReportsError() {
        a.setPortName("LINKSCOPE_NO_SUCH_PORT");
        a.start();
        awaitTrue("error", () -> a.status() == ModuleStatus.ERROR);
        assertTrue(LogSink.get().entries().stream().anyMatch(e -> e.kind() == LogEntry.Kind.ERROR));
    }

    @Test
    void settingsSummaryReads8N1() {
        assertEquals("9600 8N1, flow None", new SerialService().settingsSummary());
    }

    /** RX may arrive in several chunks; count bytes logged for that port. */
    private static boolean receivedTotal(String port, int expected) {
        int total = 0;
        for (LogEntry e : LogSink.get().entries().toArray(new LogEntry[0])) {
            if (e.kind() == LogEntry.Kind.RX && SerialService.TAG.equals(e.module()) && port.equals(e.note())) {
                total += e.payload().length;
            }
        }
        return total >= expected;
    }
}
