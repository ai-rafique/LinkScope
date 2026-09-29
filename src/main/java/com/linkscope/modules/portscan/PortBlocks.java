package com.linkscope.modules.portscan;

import com.linkscope.core.KnownPorts;
import com.linkscope.modules.portscan.PortScanService.PortResult;
import com.linkscope.modules.portscan.PortScanService.PortState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aggregation behind the two-level port grid: the 65 536 ports as 256 blocks of 256, each
 * summarised by what is occupied inside it, plus per-port verdicts for "the ports I need".
 * Pure functions so the view stays thin and the logic is tested.
 */
public final class PortBlocks {
    public static final int BLOCK_SIZE = 256;
    public static final int BLOCKS = 256;

    /** One block of 256 ports and what the scan found inside it. */
    public record Block(int index, int scanned, int listening, int inUse, int reserved, int unknown, int free) {
        public int from() {
            return index * BLOCK_SIZE;
        }

        public int to() {
            return from() + BLOCK_SIZE - 1;
        }

        public int occupied() {
            return listening + inUse;
        }

        public int pending() {
            return scanned - listening - inUse - reserved - unknown - free;
        }

        /** Style class: what matters most inside the block. */
        public String stateClass() {
            if (scanned == 0) {
                return "skipped";
            }
            if (listening > 0) {
                return "listening";
            }
            if (inUse > 0) {
                return "inuse";
            }
            if (reserved > 0) {
                return "reserved";
            }
            if (pending() == scanned) {
                return "pending";
            }
            return free > 0 || reserved > 0 || unknown > 0 ? "free" : "pending";
        }

        /** Second label line: the occupied count, or a short marker for reserved-only blocks. */
        public String badge() {
            if (scanned == 0) {
                return "";
            }
            if (occupied() > 0) {
                return "● " + occupied();
            }
            if (reserved > 0) {
                return "■ " + reserved;
            }
            return "";
        }

        public String tooltip() {
            StringBuilder sb = new StringBuilder("Ports " + from() + " – " + to());
            if (scanned == 0) {
                return sb.append("\nnot in this scan").toString();
            }
            sb.append("\n").append(scanned).append(" scanned");
            if (pending() > 0) {
                sb.append(", ").append(pending()).append(" waiting");
            }
            sb.append("\n").append(listening).append(" listening, ").append(inUse).append(" in use, ")
                    .append(reserved).append(" reserved");
            if (unknown > 0) {
                sb.append(", ").append(unknown).append(" unknown");
            }
            sb.append(", ").append(free).append(" free");
            return sb.toString();
        }

        public String band() {
            return PortBlocks.band(from());
        }
    }

    /** The verdict for one requested port. */
    public record Verdict(int port, PortState state, String owner, String detail) {
        public boolean available() {
            return state == PortState.FREE;
        }

        public String text() {
            String name = KnownPorts.nameOf(port);
            String head = port + (name.isEmpty() ? "" : " (" + name + ")");
            return switch (state) {
                case FREE -> head + "  free";
                case LISTENING -> head + "  taken: listening" + (owner.isEmpty() ? "" : " · " + owner);
                case IN_USE -> head + "  taken: in use" + (detail.isEmpty() ? "" : " · " + detail);
                case RESERVED -> head + "  blocked: reserved by the OS";
                default -> head + "  unclear" + (detail.isEmpty() ? "" : " · " + detail);
            };
        }
    }

    private PortBlocks() {
    }

    public static String band(int port) {
        return port < 1024 ? "Well-known" : port < 49152 ? "Registered" : "Dynamic";
    }

    /** Aggregates the planned ports and the results so far into the 256 blocks. */
    public static List<Block> blocks(List<Integer> planned, Map<Integer, PortResult> results) {
        int[] scanned = new int[BLOCKS];
        int[] listening = new int[BLOCKS];
        int[] inUse = new int[BLOCKS];
        int[] reserved = new int[BLOCKS];
        int[] unknown = new int[BLOCKS];
        int[] free = new int[BLOCKS];
        for (int port : planned) {
            int b = port / BLOCK_SIZE;
            if (b < 0 || b >= BLOCKS) {
                continue;
            }
            scanned[b]++;
            PortResult r = results.get(port);
            if (r == null) {
                continue;
            }
            switch (r.state()) {
                case LISTENING -> listening[b]++;
                case IN_USE -> inUse[b]++;
                case RESERVED -> reserved[b]++;
                case UNKNOWN -> unknown[b]++;
                default -> free[b]++;
            }
        }
        List<Block> out = new ArrayList<>(BLOCKS);
        for (int b = 0; b < BLOCKS; b++) {
            out.add(new Block(b, scanned[b], listening[b], inUse[b], reserved[b], unknown[b], free[b]));
        }
        return out;
    }

    /** Parses "502, 1883, 16000-16010" into distinct ascending ports; throws on nonsense. */
    public static List<Integer> parseNeeded(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (int[] range : PortScanService.parseRanges(text)) {
            for (int p = range[0]; p <= range[1]; p++) {
                if (!out.contains(p)) {
                    out.add(p);
                }
            }
        }
        out.sort(null);
        if (out.size() > 4096) {
            throw new IllegalArgumentException("That is " + out.size() + " ports; keep the needed list under 4096");
        }
        return out;
    }

    public static List<Verdict> verdicts(List<Integer> needed, Map<Integer, PortResult> results) {
        List<Verdict> out = new ArrayList<>();
        for (int port : needed) {
            PortResult r = results.get(port);
            if (r == null) {
                continue;
            }
            out.add(new Verdict(port, r.state(), r.owner(), r.detail()));
        }
        return out;
    }
}
