package com.linkscope.modules.portscan;

import com.linkscope.core.KnownPorts;
import com.linkscope.core.ui.CellGrid;
import com.linkscope.modules.portscan.PortBlocks.Block;
import com.linkscope.modules.portscan.PortScanService.PortResult;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Two-level, width-filling view of a port survey. The overview shows the 65 536 ports as
 * 256 blocks of 256, each labelled with its first port and a badge for how many ports are
 * occupied inside; the colour says what matters most in the block. Clicking a block opens
 * it as 256 individual port cells. A scan that fits in one block opens that block directly.
 * Ports from the "ports I need" list get an accent ring on both levels.
 */
public final class PortGrid extends VBox {
    private final CellGrid overview = new CellGrid(PortBlocks.BLOCKS, 60, 34, 44);
    private final CellGrid detail = new CellGrid(PortBlocks.BLOCK_SIZE, 36, 22, 30);
    private final Hyperlink allLink = new Hyperlink("All ports");
    private final Label crumb = new Label();
    private final Label bands = new Label("Well-known 0 – 1023   ·   Registered 1024 – 49151   ·   Dynamic 49152 – 65535");
    private final Button backButton = new Button("", new FontIcon("fth-arrow-left"));
    private final Pane gridSlot = new VBox();

    private List<Integer> planned = List.of();
    private final Set<Integer> plannedSet = new HashSet<>();
    private final Map<Integer, PortResult> results = new HashMap<>();
    private Set<Integer> needed = Set.of();
    private int openBlock = -1;
    private boolean singleBlockScan;
    private IntConsumer onPortPick = p -> { };

    public PortGrid() {
        setSpacing(6);
        getStyleClass().add("ls-portgrid");
        allLink.setOnAction(e -> showOverview());
        backButton.getStyleClass().addAll("button-icon", "small");
        backButton.setOnAction(e -> showOverview());
        crumb.getStyleClass().add("ls-section-title");
        bands.getStyleClass().addAll("text-muted", "small");
        HBox crumbs = new HBox(8, backButton, allLink, new Label("›"), crumb, spacer(), bands);
        crumbs.setAlignment(Pos.CENTER_LEFT);
        overview.setOnPick(this::showBlock);
        detail.setOnPick(i -> {
            if (openBlock >= 0) {
                onPortPick.accept(openBlock * PortBlocks.BLOCK_SIZE + i);
            }
        });
        getChildren().addAll(crumbs, gridSlot);
        VBox.setVgrow(gridSlot, Priority.NEVER);
        showOverview();
    }

    private static Pane spacer() {
        Pane p = new Pane();
        HBox.setHgrow(p, Priority.ALWAYS);
        return p;
    }

    public void setOnPortPick(IntConsumer handler) {
        this.onPortPick = handler == null ? p -> { } : handler;
    }

    /** New scan: forgets old results, decides which level to show. */
    public void reset(List<Integer> plannedPorts) {
        planned = List.copyOf(plannedPorts);
        plannedSet.clear();
        plannedSet.addAll(planned);
        results.clear();
        int firstBlock = planned.isEmpty() ? -1 : planned.get(0) / PortBlocks.BLOCK_SIZE;
        int lastBlock = planned.isEmpty() ? -1 : planned.get(planned.size() - 1) / PortBlocks.BLOCK_SIZE;
        singleBlockScan = firstBlock >= 0 && firstBlock == lastBlock;
        if (singleBlockScan) {
            showBlock(firstBlock);
        } else {
            showOverview();
        }
    }

    public void update(Collection<? extends PortResult> fresh) {
        for (PortResult r : fresh) {
            results.put(r.port(), r);
        }
        refresh();
    }

    public void setNeeded(Set<Integer> ports) {
        this.needed = ports == null ? Set.of() : Set.copyOf(ports);
        refresh();
    }

    /** Opens the block that contains the port and selects it. */
    public void reveal(int port) {
        showBlock(port / PortBlocks.BLOCK_SIZE);
        detail.select(port % PortBlocks.BLOCK_SIZE);
    }

    public void showOverview() {
        openBlock = -1;
        gridSlot.getChildren().setAll(overview);
        backButton.setVisible(false);
        backButton.setManaged(false);
        allLink.setDisable(true);
        crumb.setText(planned.isEmpty() ? "no scan yet" : planned.size() + " ports in " + blocksInScan() + " block(s) of 256 · click a block to open it");
        refresh();
    }

    public void showBlock(int block) {
        if (block < 0 || block >= PortBlocks.BLOCKS) {
            return;
        }
        openBlock = block;
        gridSlot.getChildren().setAll(detail);
        boolean canGoUp = !singleBlockScan;
        backButton.setVisible(canGoUp);
        backButton.setManaged(canGoUp);
        allLink.setDisable(!canGoUp);
        int from = block * PortBlocks.BLOCK_SIZE;
        crumb.setText("Ports " + from + " – " + (from + PortBlocks.BLOCK_SIZE - 1) + "  ·  " + PortBlocks.band(from));
        detail.select(-1);
        refresh();
    }

    private int blocksInScan() {
        Set<Integer> blocks = new HashSet<>();
        for (int p : planned) {
            blocks.add(p / PortBlocks.BLOCK_SIZE);
        }
        return blocks.size();
    }

    private void refresh() {
        if (openBlock < 0) {
            refreshOverview();
        } else {
            refreshDetail();
        }
    }

    private void refreshOverview() {
        List<Block> blocks = PortBlocks.blocks(planned, results);
        for (Block b : blocks) {
            int i = b.index();
            String badge = b.badge();
            overview.setLabel(i, badge.isEmpty() ? String.valueOf(b.from()) : b.from() + "\n" + badge);
            overview.setState(i, b.stateClass());
            overview.setTooltip(i, b.tooltip() + (neededIn(b) ? "\ncontains port(s) you need" : ""));
            overview.mark(i, "needed", neededIn(b));
        }
    }

    private boolean neededIn(Block b) {
        for (int p : needed) {
            if (p >= b.from() && p <= b.to()) {
                return true;
            }
        }
        return false;
    }

    private void refreshDetail() {
        int base = openBlock * PortBlocks.BLOCK_SIZE;
        for (int i = 0; i < PortBlocks.BLOCK_SIZE; i++) {
            int port = base + i;
            detail.setLabel(i, String.valueOf(port));
            detail.mark(i, "needed", needed.contains(port));
            String name = KnownPorts.nameOf(port);
            String head = "Port " + port + (name.isEmpty() ? "" : " (" + name + ")");
            if (!plannedSet.contains(port)) {
                detail.setState(i, "skipped");
                detail.setTooltip(i, head + "\nnot in this scan");
                continue;
            }
            PortResult r = results.get(port);
            if (r == null) {
                detail.setState(i, "pending");
                detail.setTooltip(i, head + "\nwaiting");
                continue;
            }
            String state = switch (r.state()) {
                case LISTENING -> "listening";
                case IN_USE -> "inuse";
                case RESERVED -> "reserved";
                case UNKNOWN -> "unknown";
                default -> "free";
            };
            detail.setState(i, state);
            detail.setTooltip(i, head + "\n" + r.state().label + (r.owner().isEmpty() ? "" : " · " + r.owner())
                    + (r.detail().isEmpty() ? "" : "\n" + r.detail()));
        }
    }
}
