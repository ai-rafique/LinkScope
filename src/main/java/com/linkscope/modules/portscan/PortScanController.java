package com.linkscope.modules.portscan;

import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.portscan.PortBlocks.Verdict;
import com.linkscope.modules.portscan.PortRows.PortRow;
import com.linkscope.modules.portscan.PortScanService.LocalAddress;
import com.linkscope.modules.portscan.PortScanService.PortResult;
import com.linkscope.modules.portscan.PortScanService.PortState;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Ports tab: survey local TCP ports on one interface address. Results show on a two-level
 * grid (blocks of 256, then single ports), as state tiles that double as filters, and in a
 * table where runs of free ports collapse into ranges. A "ports I need" list gets a verdict
 * per port: free, or taken and by whom.
 */
public class PortScanController implements ModuleController {
    public static final String ID = "ports";
    private static final Map<ModuleStatus, String> LABELS = Map.of(
            ModuleStatus.DISCONNECTED, "Idle",
            ModuleStatus.CONNECTING, "Scanning…",
            ModuleStatus.CONNECTED, "Done",
            ModuleStatus.ERROR, "Error");

    @FXML private ComboBox<LocalAddress> addressCombo;
    @FXML private Button refreshButton;
    @FXML private TextField rangeField;
    @FXML private MenuButton presetsButton;
    @FXML private Button scanButton;
    @FXML private StatusBadge statusBadge;
    @FXML private ProgressBar progressBar;
    @FXML private Label summaryLabel;
    @FXML private TextField neededField;
    @FXML private Button checkButton;
    @FXML private FlowPane verdictBox;
    @FXML private ToggleButton listeningTile;
    @FXML private ToggleButton inUseTile;
    @FXML private ToggleButton reservedTile;
    @FXML private ToggleButton unknownTile;
    @FXML private ToggleButton freeTile;
    @FXML private VBox gridHost;
    @FXML private TextField searchField;
    @FXML private TableView<PortRow> table;
    @FXML private TableColumn<PortRow, String> portColumn;
    @FXML private TableColumn<PortRow, String> stateColumn;
    @FXML private TableColumn<PortRow, String> serviceColumn;
    @FXML private TableColumn<PortRow, String> ownerColumn;
    @FXML private TableColumn<PortRow, String> detailColumn;

    private final PortScanService service = new PortScanService();
    private final PortGrid grid = new PortGrid();
    private final ObservableList<PortRow> rows = FXCollections.observableArrayList();
    private final Map<PortState, ToggleButton> tiles = new EnumMap<>(PortState.class);
    private final PauseTransition rebuild = new PauseTransition(Duration.millis(200));
    private List<Integer> needed = List.of();
    private String pendingAddress;

    @FXML
    private void initialize() {
        statusBadge.setLabels(LABELS);
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding scanning = service.statusProperty().isEqualTo(ModuleStatus.CONNECTING);
        for (var n : List.of(addressCombo, refreshButton, rangeField, presetsButton, neededField, checkButton)) {
            n.disableProperty().bind(scanning);
        }

        tiles.put(PortState.LISTENING, listeningTile);
        tiles.put(PortState.IN_USE, inUseTile);
        tiles.put(PortState.RESERVED, reservedTile);
        tiles.put(PortState.UNKNOWN, unknownTile);
        tiles.put(PortState.FREE, freeTile);
        tiles.values().forEach(t -> t.selectedProperty().addListener((obs, old, now) -> rebuildRows()));
        searchField.textProperty().addListener((obs, old, now) -> rebuildRows());

        gridHost.getChildren().add(grid);
        grid.setOnPortPick(this::jumpTo);

        portColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().label()));
        stateColumn.setCellValueFactory(c -> new SimpleStringProperty(marker(c.getValue().state()) + c.getValue().state().label
                + (c.getValue().count() > 1 ? "  (" + c.getValue().count() + " ports)" : "")));
        serviceColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().service()));
        ownerColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().owner()));
        detailColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().detail()));
        table.setItems(rows);
        table.setPlaceholder(new Label("Pick an interface and a range, then press Scan. Free ports fold into ranges; occupied ports get their own row."));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (now != null && now.from() == now.to()) {
                grid.reveal(now.from());
            }
        });

        for (String preset : List.of("1-1024", "1-65535", "1024-49151", "49152-65535")) {
            MenuItem item = new MenuItem(preset.equals("1-1024") ? "Well-known (1-1024)"
                    : preset.equals("1-65535") ? "Everything (1-65535)"
                    : preset.equals("1024-49151") ? "Registered (1024-49151)" : "Dynamic (49152-65535)");
            item.setOnAction(e -> rangeField.setText(preset));
            presetsButton.getItems().add(item);
        }
        MenuItem common = new MenuItem("Common services");
        common.setOnAction(e -> rangeField.setText("21-23,25,53,80,110,135,139,143,161,443,445,502,1433,1883,3306,3389,4222,5432,5900,8080,8443,8883,9092"));
        presetsButton.getItems().add(common);

        service.results().addListener((ListChangeListener<PortResult>) change -> {
            List<PortResult> added = new ArrayList<>();
            while (change.next()) {
                if (change.wasAdded()) {
                    added.addAll(change.getAddedSubList());
                }
            }
            if (!added.isEmpty()) {
                grid.update(added);
            }
            rebuild.playFromStart();
        });
        rebuild.setOnFinished(e -> rebuildRows());

        progressBar.progressProperty().bind(Bindings.createDoubleBinding(() -> service.totalProperty().get() == 0 ? 0
                : (double) service.progressProperty().get() / service.totalProperty().get(),
                service.progressProperty(), service.totalProperty()));
        summaryLabel.textProperty().bind(Bindings.createStringBinding(() -> service.totalProperty().get() == 0 ? ""
                : service.progressProperty().get() + " / " + service.totalProperty().get() + " ports scanned",
                service.progressProperty(), service.totalProperty()));

        service.statusProperty().addListener((obs, old, now) -> updateScanButton(now));
        updateScanButton(service.statusProperty().get());
        scanButton.setOnAction(e -> toggleScan(false));
        rangeField.setOnAction(e -> toggleScan(false));
        checkButton.setOnAction(e -> toggleScan(true));
        neededField.setOnAction(e -> toggleScan(true));
        neededField.textProperty().addListener((obs, old, now) -> applyNeeded(false));
        refreshButton.setOnAction(e -> loadAddresses());
        addressCombo.setOnShowing(e -> loadAddresses());
        loadAddresses();
        updateTiles();
    }

    private static String marker(PortState s) {
        return switch (s) {
            case LISTENING -> "● ";
            case IN_USE -> "◐ ";
            case RESERVED -> "■ ";
            case FREE -> "○ ";
            default -> "? ";
        };
    }

    /** Parses the needed list; complains only when asked to (typing should not toast on every keystroke). */
    private boolean applyNeeded(boolean loud) {
        try {
            needed = PortBlocks.parseNeeded(Fields.text(neededField));
            grid.setNeeded(new HashSet<>(needed));
            refreshVerdicts();
            return true;
        } catch (IllegalArgumentException ex) {
            if (loud) {
                Toasts.error(ex.getMessage());
            }
            return false;
        }
    }

    /** Recomputes the collapsed rows from the results, the tile filters and the search text. */
    private void rebuildRows() {
        List<PortResult> all = new ArrayList<>(service.results());
        updateTiles();
        refreshVerdicts();
        String needle = Fields.text(searchField).toLowerCase(Locale.ROOT);
        Integer needlePort = null;
        if (!needle.isEmpty() && needle.chars().allMatch(Character::isDigit)) {
            needlePort = Integer.parseInt(needle);
        }
        List<PortRow> next = new ArrayList<>();
        for (PortRow r : PortRows.collapse(all)) {
            ToggleButton tile = tiles.get(r.state());
            if (tile != null && !tile.isSelected()) {
                continue;
            }
            if (needlePort != null) {
                if (!r.contains(needlePort)) {
                    continue;
                }
            } else if (!needle.isEmpty()) {
                String hay = (r.owner() + " " + r.service() + " " + r.state().label + " " + r.detail()).toLowerCase(Locale.ROOT);
                if (!hay.contains(needle)) {
                    continue;
                }
            }
            next.add(r);
        }
        rows.setAll(next);
    }

    private void updateTiles() {
        Map<PortState, Integer> counts = new EnumMap<>(PortState.class);
        for (PortResult r : service.results()) {
            counts.merge(r.state(), 1, Integer::sum);
        }
        tiles.forEach((state, tile) -> tile.setText(counts.getOrDefault(state, 0) + "  " + state.label.toLowerCase(Locale.ROOT)));
    }

    private void refreshVerdicts() {
        verdictBox.getChildren().clear();
        if (needed.isEmpty()) {
            return;
        }
        Map<Integer, PortResult> byPort = new HashMap<>();
        for (PortResult r : service.results()) {
            byPort.put(r.port(), r);
        }
        List<Verdict> verdicts = PortBlocks.verdicts(needed, byPort);
        if (verdicts.isEmpty()) {
            Label hint = new Label(needed.size() + " port(s) listed · press Check to test them");
            hint.getStyleClass().addAll("text-muted", "small");
            verdictBox.getChildren().add(hint);
            return;
        }
        int taken = 0;
        for (Verdict v : verdicts) {
            Label chip = new Label(v.text());
            chip.getStyleClass().add("ls-verdict");
            chip.getStyleClass().add(v.available() ? "ok" : v.state() == PortState.UNKNOWN ? "unclear" : "taken");
            chip.setTooltip(new Tooltip(v.available() ? "Nothing holds this port on the chosen address; you can bind it."
                    : v.state() == PortState.RESERVED ? "The OS refuses binds here (excluded range or privileged). Pick another port."
                    : "Something already holds this port. Stop that process or pick another port."));
            chip.setOnMouseClicked(e -> jumpTo(v.port()));
            verdictBox.getChildren().add(chip);
            if (!v.available()) {
                taken++;
            }
        }
        int missing = needed.size() - verdicts.size();
        if (missing > 0) {
            Label more = new Label(missing + " not scanned yet");
            more.getStyleClass().addAll("text-muted", "small");
            verdictBox.getChildren().add(more);
        }
        if (taken == 0 && missing == 0 && !verdicts.isEmpty()) {
            Label all = new Label("all " + verdicts.size() + " available");
            all.getStyleClass().addAll("text-muted", "small");
            verdictBox.getChildren().add(all);
        }
    }

    private void jumpTo(int port) {
        grid.reveal(port);
        for (PortRow r : rows) {
            if (r.contains(port)) {
                table.getSelectionModel().select(r);
                table.scrollTo(r);
                return;
            }
        }
        for (PortResult r : service.results()) {
            if (r.port() == port) {
                Toasts.info("Port " + port + ": " + r.state().label + (r.owner().isEmpty() ? "" : " (" + r.owner() + ")")
                        + " · hidden by the current filter");
                return;
            }
        }
    }

    /** Interface enumeration can block briefly, so it runs off the FX thread. */
    private void loadAddresses() {
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            List<LocalAddress> list = PortScanService.localAddresses();
            FxThread.run(() -> {
                String keep = pendingAddress != null ? pendingAddress
                        : addressCombo.getValue() != null ? addressCombo.getValue().ip() : null;
                pendingAddress = null;
                List<String> current = addressCombo.getItems().stream().map(LocalAddress::ip).toList();
                if (!current.equals(list.stream().map(LocalAddress::ip).toList())) {
                    addressCombo.getItems().setAll(list);
                }
                selectAddress(keep);
            });
        });
    }

    private void selectAddress(String ip) {
        for (LocalAddress a : addressCombo.getItems()) {
            if (a.ip().equals(ip)) {
                addressCombo.setValue(a);
                return;
            }
        }
        if (!addressCombo.getItems().isEmpty()) {
            addressCombo.getSelectionModel().selectFirst();
        }
    }

    /** Scans the range field, or only the needed ports when {@code onlyNeeded}. */
    private void toggleScan(boolean onlyNeeded) {
        if (service.isRunning()) {
            service.stop();
            return;
        }
        List<int[]> ranges;
        try {
            LocalAddress a = addressCombo.getValue();
            service.setAddress(a == null ? "127.0.0.1" : a.ip());
            String spec;
            if (onlyNeeded) {
                if (!applyNeeded(true)) {
                    return;
                }
                if (needed.isEmpty()) {
                    Toasts.info("List the ports you need first, e.g. 502, 1883, 16000-16010");
                    return;
                }
                spec = Fields.text(neededField);
            } else {
                applyNeeded(false);
                spec = Fields.requireText(rangeField, "Port range");
            }
            service.setRanges(spec);
            ranges = PortScanService.parseRanges(spec);
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
        List<Integer> planned = new ArrayList<>();
        for (int[] r : ranges) {
            for (int p = r[0]; p <= r[1]; p++) {
                planned.add(p);
            }
        }
        planned.sort(null);
        grid.reset(planned);
        grid.setNeeded(new HashSet<>(needed));
        rows.clear();
        service.start();
    }

    private void updateScanButton(ModuleStatus s) {
        boolean scanning = s == ModuleStatus.CONNECTING;
        scanButton.setText(scanning ? "Stop" : "Scan");
        scanButton.setGraphic(new FontIcon(scanning ? "fth-square" : "fth-search"));
    }

    // --- ModuleController -----------------------------------------------------------

    @Override
    public String moduleId() {
        return ID;
    }

    @Override
    public ReadOnlyObjectProperty<ModuleStatus> statusProperty() {
        return service.statusProperty();
    }

    @Override
    public Map<ModuleStatus, String> statusLabels() {
        return LABELS;
    }

    @Override
    public Map<String, String> captureFields() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("address", addressCombo.getValue() == null ? "" : addressCombo.getValue().ip());
        m.put("ranges", Fields.text(rangeField));
        m.put("needed", Fields.text(neededField));
        m.put("showFree", Boolean.toString(freeTile.isSelected()));
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        String ip = fields.get("address");
        if (ip != null && !ip.isBlank()) {
            pendingAddress = ip;
            selectAddress(ip);
        }
        Fields.apply(fields, "ranges", rangeField);
        Fields.apply(fields, "needed", neededField);
        if (fields.containsKey("showFree")) {
            freeTile.setSelected(Boolean.parseBoolean(fields.get("showFree")));
        } else if (fields.containsKey("hideFree")) {
            freeTile.setSelected(!Boolean.parseBoolean(fields.get("hideFree")));
        }
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
