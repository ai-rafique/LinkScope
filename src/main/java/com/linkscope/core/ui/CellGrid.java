package com.linkscope.core.ui;

import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A fixed number of small labelled cells laid out to fill the available width: the column
 * count is the largest power of two that keeps every cell at least {@code minCellWidth}
 * wide, and the cells stretch so each row spans the full width. Cells carry style classes
 * for their state, a tooltip, and report clicks by index. Used for the /24 host grid and
 * the port grids.
 */
public class CellGrid extends Pane {
    private static final int[] COLUMN_CHOICES = {64, 32, 16, 8, 4, 2, 1};
    private static final double GAP = 3;
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final StackPane[] cells;
    private final Label[] labels;
    private final Tooltip[] tips;
    private final List<List<String>> stateClasses;
    private final double minCellWidth;
    private final double minCellHeight;
    private final double maxCellHeight;
    private int selected = -1;
    private IntConsumer onPick = i -> { };

    public CellGrid(int count, double minCellWidth, double minCellHeight, double maxCellHeight) {
        this.minCellWidth = minCellWidth;
        this.minCellHeight = minCellHeight;
        this.maxCellHeight = maxCellHeight;
        cells = new StackPane[count];
        labels = new Label[count];
        tips = new Tooltip[count];
        stateClasses = new ArrayList<>(count);
        getStyleClass().add("ls-cellgrid");
        for (int i = 0; i < count; i++) {
            Label text = new Label(String.valueOf(i));
            text.getStyleClass().add("ls-cell-text");
            text.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            StackPane cell = new StackPane(text);
            cell.getStyleClass().add("ls-cell");
            Tooltip tip = new Tooltip(String.valueOf(i));
            tip.setShowDelay(javafx.util.Duration.millis(150));
            Tooltip.install(cell, tip);
            final int index = i;
            cell.setOnMouseClicked(e -> {
                select(index);
                onPick.accept(index);
            });
            cells[i] = cell;
            labels[i] = text;
            tips[i] = tip;
            stateClasses.add(new ArrayList<>());
            getChildren().add(cell);
        }
    }

    public int size() {
        return cells.length;
    }

    public void setOnPick(IntConsumer handler) {
        this.onPick = handler == null ? i -> { } : handler;
    }

    public void setLabel(int i, String text) {
        labels[i].setText(text);
    }

    public void setTooltip(int i, String text) {
        tips[i].setText(text);
    }

    /** Replaces the cell's state classes (everything set through this method before). */
    public void setState(int i, String... classes) {
        List<String> current = stateClasses.get(i);
        cells[i].getStyleClass().removeAll(current);
        current.clear();
        for (String c : classes) {
            current.add(c);
            cells[i].getStyleClass().add(c);
        }
    }

    /** Adds one extra class without touching the state classes (rings, marks). */
    public void mark(int i, String cls, boolean on) {
        if (on) {
            if (!cells[i].getStyleClass().contains(cls)) {
                cells[i].getStyleClass().add(cls);
            }
        } else {
            cells[i].getStyleClass().remove(cls);
        }
    }

    /** Highlights one cell (or none with -1). */
    public void select(int i) {
        if (selected >= 0 && selected < cells.length) {
            cells[selected].pseudoClassStateChanged(SELECTED, false);
        }
        selected = i;
        if (selected >= 0 && selected < cells.length) {
            cells[selected].pseudoClassStateChanged(SELECTED, true);
        }
    }

    public int selected() {
        return selected;
    }

    // --- responsive layout -----------------------------------------------------------------

    private int columnsFor(double width) {
        for (int cols : COLUMN_CHOICES) {
            if (cols <= cells.length && cols * minCellWidth + (cols - 1) * GAP <= width) {
                return cols;
            }
        }
        return 1;
    }

    private double cellHeightFor(double cellWidth) {
        return Math.max(minCellHeight, Math.min(maxCellHeight, cellWidth * 0.6));
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth() - snappedLeftInset() - snappedRightInset();
        int cols = columnsFor(width);
        double cellWidth = (width - (cols - 1) * GAP) / cols;
        double cellHeight = cellHeightFor(cellWidth);
        double left = snappedLeftInset();
        double top = snappedTopInset();
        for (int i = 0; i < cells.length; i++) {
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
        int rows = (int) Math.ceil((double) cells.length / cols);
        return snappedTopInset() + snappedBottomInset() + rows * cellHeightFor(cellWidth) + Math.max(0, rows - 1) * GAP;
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computeMinWidth(double height) {
        return Math.min(cells.length, 8) * minCellWidth + 7 * GAP;
    }

    @Override
    protected double computePrefWidth(double height) {
        return Math.min(cells.length, 16) * minCellWidth + 15 * GAP;
    }
}
