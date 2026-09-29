package com.linkscope.modules.portscan;

import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
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
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Ports tab: survey local TCP ports on one interface address. Results show as a strip
 * (one slice per port), state tiles that double as filters, and a table where runs of
 * free ports collapse into ranges so listening and in-use ports stand out.
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
    @FXML private ToggleButton listeningTile;
    @FXML private ToggleButton inUseTile;
    @FXML private ToggleButton reservedTile;
    @FXML private ToggleButton unknownTile;
    @FXML private ToggleButton freeTile;
    @FXML private StackPane stripHost;
    @FXML private TextField searchField;
    @FXML private TableView<PortRow> table;
    @FXML private TableColumn<PortRow, String> portColumn;
    @FXML private TableColumn<PortRow, String> stateColumn;
    @FXML private TableColumn<PortRow, String> serviceColumn;
    @FXML private TableColumn<PortRow, String> ownerColumn;
    @FXML private TableColumn<PortRow, String> detailColumn;

    private final PortScanService service = new PortScanService();
    private final PortStrip strip = new PortStrip();
    private final ObservableList<PortRow> rows = FXCollections.observableArrayList();
    private final Map<PortState, ToggleButton> tiles = new EnumMap<>(PortState.class);
    private final PauseTransition rebuild = new PauseTransition(Duration.millis(200));
    private String pendingAddress;

    @FXML
    private void initialize() {
        statusBadge.setLabels(LABELS);
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding scanning = service.statusProperty().isEqualTo(ModuleStatus.CONNECTING);
        addressCombo.disableProperty().bind(scanning);
        refreshButton.disableProperty().bind(scanning);
        rangeField.disableProperty().bind(scanning);
        presetsButton.disableProperty().bind(scanning);

        tiles.put(PortState.LISTENING, listeningTile);
        tiles.put(PortState.IN_USE, inUseTile);
        tiles.put(PortState.RESERVED, reservedTile);
        tiles.put(PortState.UNKNOWN, unknownTile);
        tiles.put(PortState.FREE, freeTile);
        tiles.values().forEach(t -> t.selectedProperty().addListener((obs, old, now) -> rebuildRows()));
        searchField.textProperty().addListener((obs, old, now) -> rebuildRows());

        stripHost.getChildren().add(strip);
        stripHost.widthProperty().addListener((obs, old, now) -> strip.setStripWidth(now.doubleValue() - 2));
        strip.setOnPick(this::jumpTo);

        portColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().label()));
        stateColumn.setCellValueFactory(c -> new SimpleStringProperty(marker(c.getValue().state()) + c.getValue().state().label
                + (c.getValue().count() > 1 ? "  (" + c.getValue().count() + " ports)" : "")));
        serviceColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().service()));
        ownerColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().owner()));
        detailColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().detail()));
        table.setItems(rows);
        table.setPlaceholder(new Label("Pick an interface and a range, then press Scan. Free ports fold into ranges; listening ports get their own row."));

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
                strip.update(added);
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
        scanButton.setOnAction(e -> toggleScan());
        rangeField.setOnAction(e -> toggleScan());
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

    /** Recomputes the collapsed rows from the results, the tile filters and the search text. */
    private void rebuildRows() {
        List<PortResult> all = new ArrayList<>(service.results());
        updateTiles();
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

    private void jumpTo(int port) {
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

    private void toggleScan() {
        if (service.isRunning()) {
            service.stop();
            return;
        }
        List<int[]> ranges;
        try {
            LocalAddress a = addressCombo.getValue();
            service.setAddress(a == null ? "127.0.0.1" : a.ip());
            service.setRanges(Fields.requireText(rangeField, "Port range"));
            ranges = PortScanService.parseRanges(Fields.text(rangeField));
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
        strip.reset(planned);
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
