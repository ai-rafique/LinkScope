package com.linkscope.modules.portscan;

import com.linkscope.core.KnownPorts;
import com.linkscope.modules.portscan.PortScanService.PortResult;
import com.linkscope.modules.portscan.PortScanService.PortState;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.paint.Color;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The scanned ports as one horizontal strip: each port (or, when there are more ports than
 * pixels, each bucket of ports) is a coloured slice. Listening wins over in use over reserved
 * over unknown over free within a bucket, so the rare states never disappear. Hovering names
 * the port under the pointer; clicking hands it to the owner.
 */
public final class PortStrip extends Canvas {
    static final Color LISTENING = Color.web("#2da44e");
    static final Color IN_USE = Color.web("#bf8700");
    static final Color RESERVED = Color.web("#cf222e");
    static final Color UNKNOWN = Color.web("#8250df");
    static final Color FREE = Color.web("#8b949e", 0.30);
    static final Color PENDING = Color.web("#8b949e", 0.10);

    private List<Integer> ports = List.of();
    private final Map<Integer, PortResult> byPort = new HashMap<>();
    private IntConsumer onPick = p -> { };
    private final Tooltip tip = new Tooltip();

    public PortStrip() {
        super(600, 22);
        Tooltip.install(this, tip);
        tip.setShowDelay(javafx.util.Duration.millis(100));
        setOnMouseMoved(e -> {
            int idx = indexAt(e.getX());
            tip.setText(idx < 0 ? "" : describe(idx));
        });
        setOnMouseClicked(e -> {
            int idx = indexAt(e.getX());
            if (idx >= 0) {
                onPick.accept(ports.get(idx));
            }
        });
    }

    public void setOnPick(IntConsumer handler) {
        this.onPick = handler == null ? p -> { } : handler;
    }

    /** The ports the scan will visit, in ascending order; results arrive later through {@link #update}. */
    public void reset(List<Integer> plannedPorts) {
        this.ports = List.copyOf(plannedPorts);
        byPort.clear();
        redraw();
    }

    public void update(List<? extends PortResult> results) {
        for (PortResult r : results) {
            byPort.put(r.port(), r);
        }
        redraw();
    }

    public void setStripWidth(double width) {
        setWidth(Math.max(50, width));
        redraw();
    }

    private double slice() {
        return ports.isEmpty() ? 0 : getWidth() / ports.size();
    }

    private int indexAt(double x) {
        if (ports.isEmpty()) {
            return -1;
        }
        int idx = (int) (x / slice());
        return idx < 0 || idx >= ports.size() ? -1 : idx;
    }

    private String describe(int idx) {
        double s = slice();
        if (s >= 1) {
            int port = ports.get(idx);
            PortResult r = byPort.get(port);
            String name = KnownPorts.nameOf(port);
            return "Port " + port + (name.isEmpty() ? "" : " (" + name + ")") + ": "
                    + (r == null ? "not scanned yet" : r.state().label + (r.owner().isEmpty() ? "" : ", " + r.owner()));
        }
        int perPixel = (int) Math.ceil(1 / s);
        int first = idx - idx % perPixel;
        int last = Math.min(ports.size() - 1, first + perPixel - 1);
        int listening = 0;
        int inUse = 0;
        int reserved = 0;
        for (int i = first; i <= last; i++) {
            PortResult r = byPort.get(ports.get(i));
            if (r == null) {
                continue;
            }
            switch (r.state()) {
                case LISTENING -> listening++;
                case IN_USE -> inUse++;
                case RESERVED -> reserved++;
                default -> { }
            }
        }
        return "Ports " + ports.get(first) + " – " + ports.get(last) + ": " + listening + " listening, " + inUse + " in use, "
                + reserved + " reserved";
    }

    void redraw() {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();
        g.clearRect(0, 0, w, h);
        if (ports.isEmpty()) {
            return;
        }
        double s = slice();
        if (s >= 1) {
            for (int i = 0; i < ports.size(); i++) {
                g.setFill(colorOf(byPort.get(ports.get(i))));
                g.fillRect(i * s, 2, Math.max(1, s - (s > 3 ? 1 : 0)), h - 4);
            }
            return;
        }
        int perPixel = (int) Math.ceil(1 / s);
        for (int first = 0; first < ports.size(); first += perPixel) {
            int last = Math.min(ports.size() - 1, first + perPixel - 1);
            int best = -1;
            boolean any = false;
            for (int i = first; i <= last; i++) {
                PortResult r = byPort.get(ports.get(i));
                if (r == null) {
                    continue;
                }
                any = true;
                best = Math.max(best, rank(r.state()));
            }
            g.setFill(!any ? PENDING : colorOf(best));
            double x = first * s;
            g.fillRect(x, 2, Math.max(1, perPixel * s), h - 4);
        }
    }

    private static int rank(PortState s) {
        return switch (s) {
            case LISTENING -> 4;
            case IN_USE -> 3;
            case RESERVED -> 2;
            case UNKNOWN -> 1;
            default -> 0;
        };
    }

    private static Color colorOf(int rank) {
        return switch (rank) {
            case 4 -> LISTENING;
            case 3 -> IN_USE;
            case 2 -> RESERVED;
            case 1 -> UNKNOWN;
            default -> FREE;
        };
    }

    private static Color colorOf(PortResult r) {
        return r == null ? PENDING : colorOf(rank(r.state()));
    }

    @Override
    public boolean isResizable() {
        return false;
    }
}
