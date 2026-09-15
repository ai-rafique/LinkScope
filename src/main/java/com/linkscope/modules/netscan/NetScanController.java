package com.linkscope.modules.netscan;

import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.netscan.NetScanService.HostResult;
import com.linkscope.modules.netscan.NetScanService.HostState;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Net Scan tab: sweep a /24 and list which addresses are occupied. */
public class NetScanController implements ModuleController {
    public static final String ID = "netscan";
    private static final Map<ModuleStatus, String> LABELS = Map.of(
            ModuleStatus.DISCONNECTED, "Idle",
            ModuleStatus.CONNECTING, "Scanning…",
            ModuleStatus.CONNECTED, "Done",
            ModuleStatus.ERROR, "Error");

    @FXML private TextField baseField;
    @FXML private TextField fromField;
    @FXML private TextField toField;
    @FXML private TextField timeoutField;
    @FXML private CheckBox probePortsCheck;
    @FXML private CheckBox resolveNamesCheck;
    @FXML private Button scanButton;
    @FXML private StatusBadge statusBadge;
    @FXML private ProgressBar progressBar;
    @FXML private Label summaryLabel;
    @FXML private ToggleButton onlyUpToggle;
    @FXML private TableView<HostResult> table;
    @FXML private TableColumn<HostResult, String> ipColumn;
    @FXML private TableColumn<HostResult, String> stateColumn;
    @FXML private TableColumn<HostResult, String> rttColumn;
    @FXML private TableColumn<HostResult, String> viaColumn;
    @FXML private TableColumn<HostResult, String> portsColumn;
    @FXML private TableColumn<HostResult, String> hostnameColumn;

    private final NetScanService service = new NetScanService();
    private final FilteredList<HostResult> filtered = new FilteredList<>(service.results());

    @FXML
    private void initialize() {
        statusBadge.setLabels(LABELS);
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding scanning = service.statusProperty().isEqualTo(ModuleStatus.CONNECTING);
        for (var n : java.util.List.of(baseField, fromField, toField, timeoutField, probePortsCheck, resolveNamesCheck)) {
            n.disableProperty().bind(scanning);
        }

        ipColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().ip()));
        ipColumn.setComparator(Comparator.comparingInt(ip -> Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1))));
        stateColumn.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().state() == HostState.UP ? "● " : "○ ") + c.getValue().state().label));
        rttColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().rttMs() < 0 ? "" : c.getValue().rttMs() + " ms"));
        viaColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().via()));
        portsColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().openPorts().isEmpty() ? ""
                : c.getValue().openPorts().toString().replaceAll("[\\[\\]]", "")));
        hostnameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().hostname()));
        SortedList<HostResult> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.getSortOrder().add(ipColumn);
        table.setPlaceholder(new Label("No results yet. Enter a base like 192.168.1.x and press Scan."));

        onlyUpToggle.selectedProperty().addListener((obs, old, now) -> applyFilter());
        applyFilter();

        progressBar.progressProperty().bind(Bindings.createDoubleBinding(() -> service.totalProperty().get() == 0 ? 0
                : (double) service.progressProperty().get() / service.totalProperty().get(),
                service.progressProperty(), service.totalProperty()));
        summaryLabel.textProperty().bind(Bindings.createStringBinding(() -> service.totalProperty().get() == 0 ? ""
                : service.upCountProperty().get() + " up, " + (service.progressProperty().get() - service.upCountProperty().get())
                + " down, " + service.progressProperty().get() + "/" + service.totalProperty().get() + " scanned",
                service.progressProperty(), service.totalProperty(), service.upCountProperty()));

        service.statusProperty().addListener((obs, old, now) -> updateScanButton(now));
        updateScanButton(service.statusProperty().get());
        scanButton.setOnAction(e -> toggleScan());
        baseField.setOnAction(e -> toggleScan());
    }

    private void applyFilter() {
        filtered.setPredicate(onlyUpToggle.isSelected() ? r -> r.state() == HostState.UP : null);
    }

    private void toggleScan() {
        if (service.isRunning()) {
            service.stop();
            return;
        }
        try {
            service.setBase(Fields.text(baseField));
            service.setRange(Fields.intValue(fromField, "From", 0, 255), Fields.intValue(toField, "To", 0, 255));
            service.setTimeoutMs(Fields.intValue(timeoutField, "Timeout", 50, 60_000));
            service.setProbePorts(probePortsCheck.isSelected());
            service.setResolveNames(resolveNamesCheck.isSelected());
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
        m.put("base", Fields.text(baseField));
        m.put("from", Fields.text(fromField));
        m.put("to", Fields.text(toField));
        m.put("timeoutMs", Fields.text(timeoutField));
        m.put("probePorts", Boolean.toString(probePortsCheck.isSelected()));
        m.put("resolveNames", Boolean.toString(resolveNamesCheck.isSelected()));
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        Fields.apply(fields, "base", baseField);
        Fields.apply(fields, "from", fromField);
        Fields.apply(fields, "to", toField);
        Fields.apply(fields, "timeoutMs", timeoutField);
        Fields.apply(fields, "probePorts", probePortsCheck);
        Fields.apply(fields, "resolveNames", resolveNamesCheck);
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
