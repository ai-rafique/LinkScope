package com.linkscope.modules.netscan;

import com.linkscope.core.ui.CellGrid;
import com.linkscope.modules.netscan.NetScanService.HostResult;
import com.linkscope.modules.netscan.NetScanService.HostState;

/**
 * The /24 as a grid of 256 cells, one per last octet, filling whatever width the tab has.
 * Cells fill as results arrive: up hosts are filled, hosts seen only through ARP are filled
 * in the warning colour, down hosts stay hollow, addresses outside the scanned range are
 * dashed. This PC and the gateway get an accent border. Every cell carries a tooltip with
 * the full text, and a click hands the octet to the owner so the table can jump to it.
 */
public final class HostGrid extends CellGrid {
    private String base = "";

    public HostGrid() {
        super(256, 36, 22, 30);
        getStyleClass().add("ls-hostgrid");
        for (int i = 0; i < 256; i++) {
            setState(i, "pending");
        }
    }

    /** Prepares the grid for a sweep of {@code base.from} to {@code base.to}. */
    public void reset(String base, int from, int to) {
        this.base = base;
        select(-1);
        for (int i = 0; i < 256; i++) {
            boolean inRange = i >= from && i <= to;
            setState(i, inRange ? "pending" : "skipped");
            setTooltip(i, base + "." + i + (inRange ? "  ·  waiting" : "  ·  not in range"));
        }
    }

    public void update(HostResult r) {
        int i = r.lastOctet();
        if (i < 0 || i > 255) {
            return;
        }
        String state = r.state() == HostState.UP ? ("arp".equals(r.via()) ? "arp" : "up") : "down";
        if ("this PC".equals(r.role())) {
            setState(i, state, "self");
        } else if ("gateway".equals(r.role())) {
            setState(i, state, "gateway");
        } else {
            setState(i, state);
        }
        setTooltip(i, describe(r));
    }

    static String describe(HostResult r) {
        StringBuilder sb = new StringBuilder(r.ip());
        if (!r.role().isEmpty()) {
            sb.append("  ·  ").append(r.role());
        }
        sb.append("\n").append(r.state() == HostState.UP ? "up via " + r.via() : "no answer");
        if (r.rttMs() >= 0) {
            sb.append(", ").append(r.rttMs()).append(" ms");
        }
        if (!r.hostname().isEmpty()) {
            sb.append("\n").append(r.hostname());
        }
        if (!r.mac().isEmpty()) {
            sb.append("\nMAC ").append(r.mac());
        }
        if (!r.openPorts().isEmpty()) {
            sb.append("\nTCP ").append(r.openPorts().toString().replaceAll("[\\[\\]]", ""));
        }
        return sb.toString();
    }

    public String base() {
        return base;
    }
}
