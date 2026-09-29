package com.linkscope.modules.netscan;

import com.linkscope.modules.netscan.NetScanService.HostResult;
import com.linkscope.modules.netscan.NetScanService.HostState;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * The /24 as a grid of cells, one per last octet, laid out to fill whatever width the tab
 * has: 8, 16, 32 or 64 columns depending on the window, with the cells stretched so the
 * rows span the full width. Cells fill as results arrive: up hosts are filled, hosts seen
 * only through ARP are filled in the warning colour, down hosts stay hollow, addresses
 * outside the scanned range are dashed. This PC and the gateway get an accent border.
 * Every cell carries a tooltip with the full text, and a click hands the octet to the
 * owner so the table can jump to it.
 */
public final class HostGrid extends Pane {
    private static final int[] COLUMN_CHOICES = {64, 32, 16, 8};
    private static final double MIN_CELL_WIDTH = 36;
    private static final double GAP = 3;
    private static final List<String> STATE_CLASSES = List.of("pending", "skipped", "up", "arp", "down");
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final StackPane[] cells = new StackPane[256];
    private final Tooltip[] tips = new Tooltip[256];
    private String base = "";
    private int selected = -1;
    private IntConsumer onPick = octet -> { };

    public HostGrid() {
        getStyleClass().add("ls-hostgrid");
        for (int i = 0; i < 256; i++) {
            Label text = new Label(String.valueOf(i));
            text.getStyleClass().add("ls-hostcell-text");
            StackPane cell = new StackPane(text);
            cell.getStyleClass().addAll("ls-hostcell", "pending");
            Tooltip tip = new Tooltip("." + i);
            tip.setShowDelay(javafx.util.Duration.millis(150));
            Tooltip.install(cell, tip);
            final int octet = i;
            cell.setOnMouseClicked(e -> {
                select(octet);
                onPick.accept(octet);
            });
            cells[i] = cell;
            tips[i] = tip;
            getChildren().add(cell);
        }
    }

    // --- responsive layout -----------------------------------------------------------------

    private int columnsFor(double width) {
        for (int cols : COLUMN_CHOICES) {
            if (cols * MIN_CELL_WIDTH + (cols - 1) * GAP <= width) {
                return cols;
            }
        }
        return 8;
    }

    private double cellHeightFor(double cellWidth) {
        return Math.max(22, Math.min(30, cellWidth * 0.6));
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth() - snappedLeftInset() - snappedRightInset();
        int cols = columnsFor(width);
        double cellWidth = (width - (cols - 1) * GAP) / cols;
        double cellHeight = cellHeightFor(cellWidth);
        double left = snappedLeftInset();
        double top = snappedTopInset();
        for (int i = 0; i < 256; i++) {
            int col = i % cols;
            int row = i / cols;
            cells[i].resizeRelocate(snapPositionX(left + col * (cellWidth + GAP)), snapPositionY(top + row * (cellHeight + GAP)),
                    snapSizeX(cellWidth), snapSizeY(cellHeight));
        }
    }

    @Override
    protected double computePrefHeight(double width) {
        double w = (width < 0 ? getWidth() : width) - snappedLeftInset() - snappedRightInset();
        int cols = columnsFor(w);
        double cellWidth = (w - (cols - 1) * GAP) / cols;
        int rows = (int) Math.ceil(256.0 / cols);
        return snappedTopInset() + snappedBottomInset() + rows * cellHeightFor(cellWidth) + (rows - 1) * GAP;
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computeMinWidth(double height) {
        return 8 * MIN_CELL_WIDTH + 7 * GAP;
    }

    @Override
    protected double computePrefWidth(double height) {
        return 16 * MIN_CELL_WIDTH + 15 * GAP;
    }

    // --- state -----------------------------------------------------------------------------

    public void setOnPick(IntConsumer handler) {
        this.onPick = handler == null ? octet -> { } : handler;
    }

    /** Prepares the grid for a sweep of {@code base.from} to {@code base.to}. */
    public void reset(String base, int from, int to) {
        this.base = base;
        select(-1);
        for (int i = 0; i < 256; i++) {
            boolean inRange = i >= from && i <= to;
            setState(i, inRange ? "pending" : "skipped");
            cells[i].getStyleClass().removeAll("self", "gateway");
            tips[i].setText(base + "." + i + (inRange ? "  ·  waiting" : "  ·  not in range"));
        }
    }

    public void update(HostResult r) {
        int i = r.lastOctet();
        if (i < 0 || i > 255) {
            return;
        }
        String state = r.state() == HostState.UP ? ("arp".equals(r.via()) ? "arp" : "up") : "down";
        setState(i, state);
        cells[i].getStyleClass().removeAll("self", "gateway");
        if ("this PC".equals(r.role())) {
            cells[i].getStyleClass().add("self");
        } else if ("gateway".equals(r.role())) {
            cells[i].getStyleClass().add("gateway");
        }
        tips[i].setText(describe(r));
    }

    /** Highlights one cell (or none with -1). */
    public void select(int octet) {
        if (selected >= 0) {
            cells[selected].pseudoClassStateChanged(SELECTED, false);
        }
        selected = octet;
        if (selected >= 0) {
            cells[selected].pseudoClassStateChanged(SELECTED, true);
        }
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

    private void setState(int i, String state) {
        cells[i].getStyleClass().removeAll(STATE_CLASSES);
        cells[i].getStyleClass().add(state);
    }

    public String base() {
        return base;
    }
}
