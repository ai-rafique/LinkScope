package com.linkscope.modules.portscan;

import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.portscan.PortScanService.LocalAddress;
import com.linkscope.modules.portscan.PortScanService.PortResult;
import com.linkscope.modules.portscan.PortScanService.PortState;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Ports tab: survey local TCP ports on one interface address. */
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
    @FXML private Button scanButton;
    @FXML private StatusBadge statusBadge;
    @FXML private ProgressBar progressBar;
    @FXML private Label summaryLabel;
    @FXML private ToggleButton hideFreeToggle;
    @FXML private TableView<PortResult> table;
    @FXML private TableColumn<PortResult, String> portColumn;
    @FXML private TableColumn<PortResult, String> stateColumn;
    @FXML private TableColumn<PortResult, String> ownerColumn;
    @FXML private TableColumn<PortResult, String> detailColumn;

    private final PortScanService service = new PortScanService();
    private final FilteredList<PortResult> filtered = new FilteredList<>(service.results());
    private String pendingAddress;

    @FXML
    private void initialize() {
        statusBadge.setLabels(LABELS);
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding scanning = service.statusProperty().isEqualTo(ModuleStatus.CONNECTING);
        addressCombo.disableProperty().bind(scanning);
        refreshButton.disableProperty().bind(scanning);
        rangeField.disableProperty().bind(scanning);

        portColumn.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().port())));
        portColumn.setComparator(Comparator.comparingInt(Integer::parseInt));
        stateColumn.setCellValueFactory(c -> new SimpleStringProperty(marker(c.getValue().state()) + c.getValue().state().label));
        ownerColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().owner()));
        detailColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().detail()));
        SortedList<PortResult> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.getSortOrder().add(portColumn);
        table.setPlaceholder(new Label("No results yet. Pick an interface and a range, then press Scan."));

        hideFreeToggle.selectedProperty().addListener((obs, old, now) -> applyFilter());
        applyFilter();

        progressBar.progressProperty().bind(Bindings.createDoubleBinding(() -> service.totalProperty().get() == 0 ? 0
                : (double) service.progressProperty().get() / service.totalProperty().get(),
                service.progressProperty(), service.totalProperty()));
        summaryLabel.textProperty().bind(Bindings.createStringBinding(this::summary,
                service.progressProperty(), service.totalProperty(), service.results()));

        service.statusProperty().addListener((obs, old, now) -> updateScanButton(now));
        updateScanButton(service.statusProperty().get());
        scanButton.setOnAction(e -> toggleScan());
        rangeField.setOnAction(e -> toggleScan());
        refreshButton.setOnAction(e -> loadAddresses());
        loadAddresses();
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

    private String summary() {
        if (service.totalProperty().get() == 0) {
            return "";
        }
        int listening = 0;
        int inUse = 0;
        int reserved = 0;
        for (PortResult r : service.results()) {
            switch (r.state()) {
                case LISTENING -> listening++;
                case IN_USE -> inUse++;
                case RESERVED -> reserved++;
                default -> { }
            }
        }
        return listening + " listening, " + inUse + " in use, " + reserved + " reserved · "
                + service.progressProperty().get() + "/" + service.totalProperty().get() + " scanned";
    }

    private void applyFilter() {
        filtered.setPredicate(hideFreeToggle.isSelected() ? r -> r.state() != PortState.FREE : null);
    }

    /** Interface enumeration can block briefly, so it runs off the FX thread. */
    private void loadAddresses() {
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            List<LocalAddress> list = PortScanService.localAddresses();
            FxThread.run(() -> {
                String keep = pendingAddress != null ? pendingAddress
                        : addressCombo.getValue() != null ? addressCombo.getValue().ip() : null;
                pendingAddress = null;
                addressCombo.getItems().setAll(list);
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
        try {
            LocalAddress a = addressCombo.getValue();
            service.setAddress(a == null ? "127.0.0.1" : a.ip());
            service.setRanges(Fields.requireText(rangeField, "Port range"));
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
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
        m.put("hideFree", Boolean.toString(hideFreeToggle.isSelected()));
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
        if (fields.containsKey("hideFree")) {
            hideFreeToggle.setSelected(Boolean.parseBoolean(fields.get("hideFree")));
        }
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
