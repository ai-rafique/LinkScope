package com.linkscope.modules.hid;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The live picture of a device, built from its {@link HidLayout}: buttons as round lights
 * that switch on while held, stick pairs as pads with a moving dot, single axes as bars
 * (centred bars for sticks and wheels, filling bars for triggers and sliders), and hats as
 * an eight-way compass. {@link #show(HidReport)} updates it in place.
 */
public final class ControllerView extends VBox {
    private final Map<String, StackPane> buttons = new LinkedHashMap<>();
    private final Map<String, StickPad> sticks = new LinkedHashMap<>();
    private final Map<String, AxisBar> bars = new LinkedHashMap<>();
    private final Map<String, HatView> hats = new LinkedHashMap<>();
    private final Set<String> everPressed = new HashSet<>();
    private final Label pressedLabel = new Label();

    public ControllerView(HidLayout layout) {
        setSpacing(12);
        getStyleClass().add("ls-controller");

        if (!layout.buttons().isEmpty()) {
            FlowPane row = new FlowPane(8, 8);
            row.setAlignment(Pos.CENTER_LEFT);
            for (HidLayout.Button b : layout.buttons()) {
                StackPane light = new StackPane(new Label(shortName(b.name())));
                light.getStyleClass().add("ls-hid-button");
                Tooltip.install(light, new Tooltip(b.name() + "  (" + HidUsages.pageName(b.page()) + " 0x" + Integer.toHexString(b.usage()) + ")"));
                buttons.put(b.name(), light);
                row.getChildren().add(light);
            }
            pressedLabel.getStyleClass().addAll("text-muted", "small");
            pressedLabel.setText("no button held");
            getChildren().addAll(section("Buttons", row), pressedLabel);
        }

        List<HidLayout.Value> loose = new ArrayList<>();
        Set<String> paired = new HashSet<>();
        HBox padRow = new HBox(24);
        padRow.setAlignment(Pos.CENTER_LEFT);
        for (HidLayout.Value v : layout.values()) {
            String partner = HidLayout.partnerOf(v.name());
            if (partner != null && !paired.contains(v.name()) && HidLayout.isHorizontal(v.name())) {
                HidLayout.Value y = find(layout, partner);
                if (y == null) {
                    continue;
                }
                StickPad pad = new StickPad(v.name() + " / " + y.name(), layout.xinput());
                sticks.put(v.name(), pad);
                sticks.put(y.name(), pad);
                paired.add(v.name());
                paired.add(y.name());
                padRow.getChildren().add(pad);
            }
        }
        for (HidLayout.Value v : layout.values()) {
            if (paired.contains(v.name())) {
                continue;
            }
            if (v.hat()) {
                HatView hat = new HatView(v.name());
                hats.put(v.name(), hat);
                padRow.getChildren().add(hat);
            } else {
                loose.add(v);
            }
        }
        if (!padRow.getChildren().isEmpty()) {
            getChildren().add(section("Sticks and hats", padRow));
        }
        if (!loose.isEmpty()) {
            VBox column = new VBox(6);
            for (HidLayout.Value v : loose) {
                AxisBar bar = new AxisBar(v);
                bars.put(v.name(), bar);
                column.getChildren().add(bar);
            }
            getChildren().add(section("Axes", column));
        }
        if (layout.buttons().isEmpty() && layout.values().isEmpty()) {
            Label none = new Label("This device reports no buttons or values Windows can name; watch the raw report below.");
            none.getStyleClass().add("text-muted");
            getChildren().add(none);
        }
    }

    private static HidLayout.Value find(HidLayout layout, String name) {
        for (HidLayout.Value v : layout.values()) {
            if (v.name().equals(name)) {
                return v;
            }
        }
        return null;
    }

    private static VBox section(String title, Region content) {
        Label t = new Label(title);
        t.getStyleClass().add("ls-section-title");
        return new VBox(6, t, content);
    }

    static String shortName(String name) {
        if (name.startsWith("Button ")) {
            return name.substring(7);
        }
        return switch (name) {
            case "D-pad up" -> "▲";
            case "D-pad down" -> "▼";
            case "D-pad left" -> "◀";
            case "D-pad right" -> "▶";
            default -> name.length() > 5 ? name.substring(0, 5) : name;
        };
    }

    /** Applies a report: lights, dots, bars and compass move; nothing is rebuilt. */
    public void show(HidReport r) {
        for (Map.Entry<String, StackPane> e : buttons.entrySet()) {
            boolean on = r.isPressed(e.getKey());
            StackPane light = e.getValue();
            boolean was = light.getStyleClass().contains("on");
            if (on && !was) {
                light.getStyleClass().add("on");
                everPressed.add(e.getKey());
            } else if (!on && was) {
                light.getStyleClass().remove("on");
            }
        }
        pressedLabel.setText(r.pressed().isEmpty() ? "no button held" : "held: " + String.join(", ", r.pressed()));
        for (HidReport.Axis a : r.axes()) {
            StickPad pad = sticks.get(a.name());
            if (pad != null) {
                pad.set(a);
                continue;
            }
            AxisBar bar = bars.get(a.name());
            if (bar != null) {
                bar.set(a);
                continue;
            }
            HatView hat = hats.get(a.name());
            if (hat != null) {
                hat.set(a);
            }
        }
    }

    /** Buttons that never registered a press this session, for the diagnosis summary. */
    public Set<String> neverPressed() {
        Set<String> out = new HashSet<>(buttons.keySet());
        out.removeAll(everPressed);
        return out;
    }

    // --- widgets ---------------------------------------------------------------------------

    /** A round pad with a crosshair and a dot that follows a stick pair. */
    static final class StickPad extends VBox {
        private static final double SIZE = 120;
        private final Circle dot = new Circle(7);
        private final Label value = new Label("+0.00, +0.00");
        private final boolean yUp;
        private double x;
        private double y;

        StickPad(String title, boolean yUp) {
            this.yUp = yUp;
            setSpacing(4);
            setAlignment(Pos.CENTER);
            Pane pad = new Pane();
            pad.setMinSize(SIZE, SIZE);
            pad.setPrefSize(SIZE, SIZE);
            pad.setMaxSize(SIZE, SIZE);
            pad.getStyleClass().add("ls-stick");
            Line h = new Line(0, SIZE / 2, SIZE, SIZE / 2);
            Line v = new Line(SIZE / 2, 0, SIZE / 2, SIZE);
            h.getStyleClass().add("ls-stick-line");
            v.getStyleClass().add("ls-stick-line");
            Circle inner = new Circle(SIZE / 2, SIZE / 2, SIZE / 4);
            inner.getStyleClass().add("ls-stick-ring");
            dot.getStyleClass().add("ls-stick-dot");
            dot.setCenterX(SIZE / 2);
            dot.setCenterY(SIZE / 2);
            pad.getChildren().addAll(h, v, inner, dot);
            Label t = new Label(title);
            t.getStyleClass().addAll("text-muted", "small");
            value.getStyleClass().addAll("ls-mono", "small");
            getChildren().addAll(t, pad, value);
        }

        void set(HidReport.Axis a) {
            if (HidLayout.isHorizontal(a.name())) {
                x = a.normalized();
            } else {
                y = a.normalized();
            }
            double dy = yUp ? -y : y;
            dot.setCenterX(SIZE / 2 + x * (SIZE / 2 - 8));
            dot.setCenterY(SIZE / 2 + dy * (SIZE / 2 - 8));
            value.setText(String.format("%+.2f, %+.2f", x, yUp ? y : -y));
        }
    }

    /** A labelled bar: centred fill for stick-like axes, left-to-right fill for triggers and sliders. */
    static final class AxisBar extends HBox {
        private final Region fill = new Region();
        private final Pane bar = new Pane();
        private final Label value = new Label();
        private final boolean centered;
        private double fraction;

        AxisBar(HidLayout.Value v) {
            this.centered = v.centered();
            this.fraction = centered ? 0.5 : 0;
            setSpacing(10);
            setAlignment(Pos.CENTER_LEFT);
            Label name = new Label(v.name());
            name.setMinWidth(110);
            name.getStyleClass().add("text-muted");
            Tooltip.install(name, new Tooltip(HidUsages.pageName(v.page()) + " 0x" + Integer.toHexString(v.usage())
                    + "  ·  logical " + v.min() + " – " + v.max() + "  ·  " + v.bitSize() + " bit"));
            bar.setMinHeight(14);
            bar.setPrefHeight(14);
            bar.getStyleClass().add("ls-axis-track");
            HBox.setHgrow(bar, Priority.ALWAYS);
            fill.getStyleClass().add("ls-axis-fill");
            fill.setManaged(false);
            bar.getChildren().add(fill);
            bar.widthProperty().addListener((obs, old, now) -> layoutFill());
            value.getStyleClass().addAll("ls-mono", "small");
            value.setMinWidth(70);
            value.setAlignment(Pos.CENTER_RIGHT);
            value.setText(centered ? "+0.00" : "0");
            getChildren().addAll(name, bar, value);
        }

        void set(HidReport.Axis a) {
            fraction = a.fraction();
            value.setText(a.centered() ? String.format("%+.2f", a.normalized()) : a.display());
            layoutFill();
        }

        private void layoutFill() {
            double width = bar.getWidth();
            double h = 14;
            if (centered) {
                double mid = width / 2;
                double end = width * fraction;
                fill.resizeRelocate(Math.min(mid, end), 0, Math.max(2, Math.abs(end - mid)), h);
            } else {
                fill.resizeRelocate(0, 0, Math.max(0, width * fraction), h);
            }
        }
    }

    /** Eight-way compass for a hat switch. */
    static final class HatView extends VBox {
        private final List<StackPane> cells = new ArrayList<>();
        private final Label value = new Label("released");

        HatView(String title) {
            setSpacing(4);
            setAlignment(Pos.CENTER);
            GridPane grid = new GridPane();
            grid.setHgap(3);
            grid.setVgap(3);
            String[] glyphs = {"↖", "↑", "↗", "←", "·", "→", "↙", "↓", "↘"};
            for (int i = 0; i < 9; i++) {
                StackPane cell = new StackPane(new Label(glyphs[i]));
                cell.getStyleClass().add("ls-hat-cell");
                if (i == 4) {
                    cell.getStyleClass().add("center");
                }
                cells.add(cell);
                grid.add(cell, i % 3, i / 3);
            }
            Label t = new Label(title);
            t.getStyleClass().addAll("text-muted", "small");
            value.getStyleClass().addAll("ls-mono", "small");
            getChildren().addAll(t, grid, value);
        }

        void set(HidReport.Axis a) {
            int deg = a.hatDegrees();
            int index = -1;
            if (deg >= 0) {
                int sector = (int) Math.round(deg / 45.0) % 8;
                index = switch (sector) {
                    case 0 -> 1;
                    case 1 -> 2;
                    case 2 -> 5;
                    case 3 -> 8;
                    case 4 -> 7;
                    case 5 -> 6;
                    case 6 -> 3;
                    default -> 0;
                };
            }
            for (int i = 0; i < cells.size(); i++) {
                boolean on = i == index;
                boolean was = cells.get(i).getStyleClass().contains("on");
                if (on && !was) {
                    cells.get(i).getStyleClass().add("on");
                } else if (!on && was) {
                    cells.get(i).getStyleClass().remove("on");
                }
            }
            value.setText(a.display());
        }
    }
}
