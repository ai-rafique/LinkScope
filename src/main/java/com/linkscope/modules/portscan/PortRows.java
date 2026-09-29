package com.linkscope.modules.portscan;

import com.linkscope.core.KnownPorts;
import com.linkscope.modules.portscan.PortScanService.PortResult;
import com.linkscope.modules.portscan.PortScanService.PortState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Collapses a port survey into rows people can read: every listening or in-use port stays
 * on its own row, while runs of consecutive free, reserved or unknown ports fold into one
 * range row ("1024 – 2047 free, 1024 ports").
 */
public final class PortRows {

    /** A single port or an inclusive range sharing one state. */
    public record PortRow(int from, int to, PortState state, String owner, String detail) {
        public int count() {
            return to - from + 1;
        }

        public String label() {
            return from == to ? String.valueOf(from) : from + " – " + to;
        }

        /** Well-known service name for a single port, or the number of known services inside a range. */
        public String service() {
            if (from == to) {
                return KnownPorts.nameOf(from);
            }
            int known = 0;
            for (int p = from; p <= to; p++) {
                if (!KnownPorts.nameOf(p).isEmpty()) {
                    known++;
                }
            }
            return known == 0 ? "" : known + " well-known port(s)";
        }

        public boolean contains(int port) {
            return port >= from && port <= to;
        }
    }

    private PortRows() {
    }

    static boolean collapsible(PortState s) {
        return s == PortState.FREE || s == PortState.RESERVED || s == PortState.UNKNOWN;
    }

    public static List<PortRow> collapse(List<PortResult> results) {
        List<PortResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparingInt(PortResult::port));
        List<PortRow> out = new ArrayList<>();
        PortRow open = null;
        for (PortResult r : sorted) {
            if (open != null && collapsible(r.state()) && open.state() == r.state() && r.port() == open.to() + 1) {
                open = new PortRow(open.from(), r.port(), open.state(), "", open.detail());
                continue;
            }
            if (open != null) {
                out.add(open);
                open = null;
            }
            if (collapsible(r.state())) {
                open = new PortRow(r.port(), r.port(), r.state(), "", r.state() == PortState.UNKNOWN ? r.detail() : r.detail());
            } else {
                out.add(new PortRow(r.port(), r.port(), r.state(), r.owner(), r.detail()));
            }
        }
        if (open != null) {
            out.add(open);
        }
        return out;
    }
}
