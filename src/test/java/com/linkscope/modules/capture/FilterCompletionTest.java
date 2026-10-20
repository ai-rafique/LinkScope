package com.linkscope.modules.capture;

import com.linkscope.modules.capture.FilterCompletion.Context;
import com.linkscope.modules.capture.FilterCompletion.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterCompletionTest {
    private static final Context CTX = new Context() {
        public Set<String> addresses() {
            return Set.of("192.168.1.5", "192.168.1.10", "10.0.0.7");
        }

        public Set<Integer> ports() {
            return Set.of(1883, 51000);
        }

        public Set<String> apps() {
            return Set.of("MQTT");
        }

        public Set<String> usbAddresses() {
            return Set.of("1.5", "1.7");
        }

        public Set<String> protocols() {
            return Set.of("TCP", "UDP");
        }
    };

    private static List<String> labels(String text) {
        return labels(text, text.length());
    }

    private static List<String> labels(String text, int caret) {
        return FilterCompletion.suggest(text, caret, () -> CTX).stream().map(Suggestion::label).toList();
    }

    @Test
    void emptyOffersFieldsAndBareWords() {
        List<String> l = labels("");
        assertTrue(l.contains("ip."));
        assertTrue(l.contains("ip.addr"));
        assertTrue(l.contains("tcp"));
        assertTrue(l.contains("usb.type"));
        assertTrue(l.contains("not"));
        assertFalse(l.contains("and"), "no connector before the first term");
    }

    @Test
    void dottedPrefixNarrowsToTheFamily() {
        assertEquals(List.of("ip.addr", "ip.src", "ip.dst"), labels("ip."));
        List<String> usb = labels("usb.");
        assertTrue(usb.contains("usb.addr") && usb.contains("usb.dir") && !usb.contains("ip.addr"));
        assertEquals(List.of("ip.addr"), labels("ip.a"));
        assertEquals(List.of("tcp"), labels("tc"));
    }

    @Test
    void afterAFieldComeOperators() {
        List<String> l = labels("ip.addr ");
        assertEquals("==", l.get(0));
        assertTrue(l.contains("!=") && l.contains("contains"));
        assertFalse(l.contains("<"), "no numeric operators on addresses");
        assertTrue(labels("port ").contains(">="));
        assertEquals(List.of("=="), labels("port ="), "a partial operator narrows");
    }

    @Test
    void afterAnOperatorComeValuesFromTheCapture() {
        List<String> addrs = labels("ip.addr == ");
        assertEquals(List.of("10.0.0.7", "192.168.1.10", "192.168.1.5", "10.0.0.", "192.168.1."), addrs);
        assertEquals(List.of("192.168.1.10", "192.168.1.5", "192.168.1."), labels("ip.addr == 192"));
        List<String> ports = labels("port == ");
        assertEquals("1883", ports.get(0), "seen ports first (sorted), then well-known");
        assertTrue(ports.contains("502"));
        assertTrue(labels("dstport == 50").containsAll(List.of("500", "502")), "prefix on port numbers");
        assertEquals(List.of("in", "out"), labels("usb.dir == "));
        assertEquals(List.of("bulk", "control", "interrupt", "isoch"), labels("usb.type == "));
        assertEquals(List.of("1.5", "1.7"), labels("usb.addr == "));
        assertTrue(labels("app == ").get(0).equals("MQTT"), "seen apps come first");
        assertTrue(labels("proto == ").containsAll(List.of("TCP", "UDP", "ARP", "USB")));
    }

    @Test
    void afterACompleteTermComeConnectors() {
        assertEquals(List.of("and", "or", ")"), labels("tcp "));
        assertEquals(List.of("and", "or", ")"), labels("ip.addr == 192.168.1.5 "));
        assertEquals(List.of("and"), labels("tcp an"));
        List<String> afterAnd = labels("tcp and ");
        assertTrue(afterAnd.contains("udp") && afterAnd.contains("port") && !afterAnd.contains("and"));
        List<String> afterParen = labels("(");
        assertTrue(afterParen.contains("tcp"));
    }

    @Test
    void containsAndQuotes() {
        List<String> l = labels("contains ");
        assertEquals(List.of("\"text\"", "0xHEX"), l);
        assertTrue(labels("info contains ").get(0).equals("\"text\""));
        assertTrue(labels("contains \"hel").isEmpty(), "nothing while inside a quoted string");
    }

    @Test
    void applyReplacesTheTokenUnderTheCaret() {
        String[] r = FilterCompletion.apply("tcp and ip.a", 12, new Suggestion("ip.addr ", "ip.addr", ""));
        assertEquals("tcp and ip.addr ", r[0]);
        assertEquals("16", r[1]);
        String[] mid = FilterCompletion.apply("ip.a and udp", 4, new Suggestion("ip.addr ", "ip.addr", ""));
        assertEquals("ip.addr  and udp", mid[0]);
        FilterCompletion.Edit e = FilterCompletion.edit("port == 18", 10);
        assertEquals(8, e.start());
        assertEquals("18", e.token());
    }
}
