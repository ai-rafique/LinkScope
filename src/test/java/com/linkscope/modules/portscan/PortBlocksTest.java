package com.linkscope.modules.portscan;

import com.linkscope.modules.portscan.PortBlocks.Block;
import com.linkscope.modules.portscan.PortBlocks.Verdict;
import com.linkscope.modules.portscan.PortScanService.PortResult;
import com.linkscope.modules.portscan.PortScanService.PortState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortBlocksTest {

    private static PortResult r(int port, PortState state, String owner) {
        return new PortResult(port, state, owner, "");
    }

    @Test
    void blocksSummariseWhatIsInside() {
        List<Integer> planned = IntStream.rangeClosed(1, 1024).boxed().toList();
        Map<Integer, PortResult> results = new HashMap<>();
        for (int p = 1; p <= 700; p++) {
            results.put(p, r(p, PortState.FREE, ""));
        }
        results.put(22, r(22, PortState.LISTENING, "sshd (1234)"));
        results.put(23, r(23, PortState.IN_USE, ""));
        results.put(300, r(300, PortState.IN_USE, ""));
        for (int p = 513; p <= 600; p++) {
            results.put(p, r(p, PortState.RESERVED, ""));
        }
        List<Block> blocks = PortBlocks.blocks(planned, results);
        assertEquals(256, blocks.size());

        Block first = blocks.get(0);
        assertEquals(255, first.scanned(), "port 0 is not in the plan");
        assertEquals(1, first.listening());
        assertEquals(1, first.inUse());
        assertEquals("listening", first.stateClass());
        assertEquals("● 2", first.badge());
        assertTrue(first.tooltip().contains("1 listening, 1 in use"), first.tooltip());
        assertEquals("Well-known", first.band());

        Block second = blocks.get(1);
        assertEquals("inuse", second.stateClass());
        assertEquals("● 1", second.badge());

        Block third = blocks.get(2);
        assertEquals(88, third.reserved());
        assertEquals("reserved", third.stateClass(), "more reserved than free in 512-767 so far: " + third);
        assertEquals("■ 88", third.badge());
        assertEquals(101, third.free(), "512 and 601-700 answered free");
        assertEquals(67, third.pending(), "701-767 have no result yet");

        Block fourth = blocks.get(3);
        assertEquals(256, fourth.scanned());
        assertEquals(0, fourth.free());
        assertEquals("pending", fourth.stateClass(), "nothing answered yet in 768-1023");

        Block far = blocks.get(200);
        assertEquals("skipped", far.stateClass());
        assertEquals("", far.badge());
        assertEquals("Dynamic", far.band());
    }

    @Test
    void neededPortsParseAndGetVerdicts() {
        List<Integer> needed = PortBlocks.parseNeeded(" 502, 1883, 16000-16002 , 502 ");
        assertEquals(List.of(502, 1883, 16000, 16001, 16002), needed);
        assertTrue(PortBlocks.parseNeeded("").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> PortBlocks.parseNeeded("abc"));
        assertThrows(IllegalArgumentException.class, () -> PortBlocks.parseNeeded("1-65535"));

        Map<Integer, PortResult> results = Map.of(
                502, r(502, PortState.FREE, ""),
                1883, r(1883, PortState.LISTENING, "mosquitto.exe (4321)"),
                16000, new PortResult(16000, PortState.IN_USE, "", "held by a non-listening socket (per socket table)"),
                16001, r(16001, PortState.RESERVED, ""));
        List<Verdict> verdicts = PortBlocks.verdicts(needed, results);
        assertEquals(4, verdicts.size(), "16002 has no result yet and is left out");
        assertTrue(verdicts.get(0).available());
        assertEquals("502 (Modbus)  free", verdicts.get(0).text());
        assertFalse(verdicts.get(1).available());
        assertEquals("1883 (MQTT)  taken: listening · mosquitto.exe (4321)", verdicts.get(1).text());
        assertEquals("16000  taken: in use · held by a non-listening socket (per socket table)", verdicts.get(2).text());
        assertEquals("16001  blocked: reserved by the OS", verdicts.get(3).text());
    }
}
