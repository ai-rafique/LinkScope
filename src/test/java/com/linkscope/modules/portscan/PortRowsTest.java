package com.linkscope.modules.portscan;

import com.linkscope.modules.portscan.PortRows.PortRow;
import com.linkscope.modules.portscan.PortScanService.PortResult;
import com.linkscope.modules.portscan.PortScanService.PortState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortRowsTest {

    private static PortResult r(int port, PortState state, String owner) {
        return new PortResult(port, state, owner, state == PortState.FREE ? "" : state.label.toLowerCase());
    }

    @Test
    void freeRunsCollapseAndListenersStayApart() {
        List<PortResult> results = new ArrayList<>();
        for (int p = 1; p <= 30; p++) {
            results.add(r(p, PortState.FREE, ""));
        }
        results.add(r(22, PortState.LISTENING, "sshd (1234)"));
        results.removeIf(x -> x.port() == 22 && x.state() == PortState.FREE);
        results.add(r(23, PortState.IN_USE, ""));
        results.removeIf(x -> x.port() == 23 && x.state() == PortState.FREE);
        results.add(r(31, PortState.RESERVED, ""));
        results.add(r(32, PortState.RESERVED, ""));
        java.util.Collections.shuffle(results, new java.util.Random(7));

        List<PortRow> rows = PortRows.collapse(results);
        assertEquals(List.of("1 – 21", "22", "23", "24 – 30", "31 – 32"), rows.stream().map(PortRow::label).toList());
        assertEquals(PortState.LISTENING, rows.get(1).state());
        assertEquals("sshd (1234)", rows.get(1).owner());
        assertEquals("SSH", rows.get(1).service());
        assertEquals(21, rows.get(0).count());
        assertEquals(PortState.RESERVED, rows.get(4).state());
        assertTrue(rows.get(0).contains(7));
        assertTrue(rows.get(0).service().contains("well-known"), "the 1-21 range holds FTP, SSH... : " + rows.get(0).service());
    }

    @Test
    void gapsInTheScannedSetBreakRanges() {
        List<PortResult> results = List.of(r(80, PortState.FREE, ""), r(81, PortState.FREE, ""), r(443, PortState.FREE, ""));
        List<PortRow> rows = PortRows.collapse(results);
        assertEquals(List.of("80 – 81", "443"), rows.stream().map(PortRow::label).toList());
        assertEquals("TLS", rows.get(1).service());
    }
}
