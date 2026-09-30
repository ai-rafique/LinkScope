package com.linkscope.modules.netscan;

import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.NetInterfaces;
import com.linkscope.core.NetInterfaces.Gateway;
import com.linkscope.core.NetInterfaces.Ipv4Network;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.netscan.NetScanService.HostResult;
import com.linkscope.modules.netscan.NetScanService.HostState;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/** Net Scan tab: pick a network interface, sweep its /24, see who is there on a grid and in a table. */
public class NetScanController implements ModuleController {
    public static final String ID = "netscan";
    private static final Map<ModuleStatus, String> LABELS = Map.of(
            ModuleStatus.DISCONNECTED, "Idle",
            ModuleStatus.CONNECTING, "Scanning…",
            ModuleStatus.CONNECTED, "Done",
            ModuleStatus.ERROR, "Error");

    /** Combo entry: one interface address, or the "custom" sentinel that reveals the base field. */
    record NetworkChoice(Ipv4Network net) {
        static final NetworkChoice CUSTOM = new NetworkChoice(null);

        String label() {
            return net == null ? "Custom network…" : net.label();
        }

        String key() {
            return net == null ? "custom" : net.address();
        }
    }

    @FXML private ComboBox<NetworkChoice> networkCombo;
    @FXML private Button refreshButton;
    @FXML private TextField baseField;
    @FXML private TextField fromField;
    @FXML private TextField toField;
    @FXML private TextField timeoutField;
    @FXML private CheckBox probePortsCheck;
    @FXML private CheckBox resolveNamesCheck;
    @FXML private CheckBox arpCheck;
    @FXML private Button scanButton;
    @FXML private StatusBadge statusBadge;
    @FXML private ProgressBar progressBar;
    @FXML private Label networkLabel;
    @FXML private Label upTile;
    @FXML private Label downTile;
    @FXML private Label scannedTile;
    @FXML private FlowPane legendBox;
    @FXML private StackPane gridHost;
    @FXML private ToggleButton onlyUpToggle;
    @FXML private Button copyButton;
    @FXML private TableView<HostResult> table;
    @FXML private TableColumn<HostResult, String> ipColumn;
    @FXML private TableColumn<HostResult, String> roleColumn;
    @FXML private TableColumn<HostResult, String> stateColumn;
    @FXML private TableColumn<HostResult, String> macColumn;
    @FXML private TableColumn<HostResult, String> hostnameColumn;
    @FXML private TableColumn<HostResult, String> rttColumn;
    @FXML private TableColumn<HostResult, String> viaColumn;
    @FXML private TableColumn<HostResult, String> portsColumn;

    private final NetScanService service = new NetScanService();
    private final FilteredList<HostResult> filtered = new FilteredList<>(service.results());
    private final HostGrid grid = new HostGrid();
    private String pendingNetwork;

    @FXML
    private void initialize() {
        statusBadge.setLabels(LABELS);
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding scanning = service.statusProperty().isEqualTo(ModuleStatus.CONNECTING);
        for (var n : List.of(networkCombo, refreshButton, baseField, fromField, toField, timeoutField, probePortsCheck, resolveNamesCheck, arpCheck)) {
            n.disableProperty().bind(scanning);
        }

        networkCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(NetworkChoice c) {
                return c == null ? "" : c.label();
            }

            @Override
            public NetworkChoice fromString(String s) {
                return null;
            }
        });
        networkCombo.valueProperty().addListener((obs, old, now) -> onNetworkChosen(now));
        networkCombo.setOnShowing(e -> loadNetworks());
        refreshButton.setOnAction(e -> loadNetworks());
        loadNetworks();

        gridHost.getChildren().add(grid);
        grid.setOnPick(this::jumpTo);
        buildLegend();

        ipColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().ip()));
        ipColumn.setComparator(Comparator.comparingInt(ip -> Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1))));
        roleColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().role()));
        stateColumn.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().state() == HostState.UP ? "● " : "○ ") + c.getValue().state().label));
        macColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().mac()));
        hostnameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().hostname()));
        rttColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().rttMs() < 0 ? "" : c.getValue().rttMs() + " ms"));
        viaColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().via()));
        portsColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().openPorts().stream()
                .map(p -> p + labelFor(p)).collect(Collectors.joining(", "))));
        SortedList<HostResult> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.getSortOrder().add(ipColumn);
        table.setPlaceholder(new Label("Pick an interface and press Scan. Hosts that answer appear here and light up on the grid."));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> grid.select(now == null ? -1 : now.lastOctet()));

        service.results().addListener((ListChangeListener<HostResult>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    change.getAddedSubList().forEach(grid::update);
                }
            }
        });

        onlyUpToggle.selectedProperty().addListener((obs, old, now) -> applyFilter());
        applyFilter();
        copyButton.setOnAction(e -> copyUpAddresses());

        progressBar.progressProperty().bind(Bindings.createDoubleBinding(() -> service.totalProperty().get() == 0 ? 0
                : (double) service.progressProperty().get() / service.totalProperty().get(),
                service.progressProperty(), service.totalProperty()));
        upTile.textProperty().bind(Bindings.createStringBinding(() -> String.valueOf(service.upCountProperty().get()), service.upCountProperty()));
        downTile.textProperty().bind(Bindings.createStringBinding(
                () -> String.valueOf(Math.max(0, service.progressProperty().get() - service.upCountProperty().get())),
                service.progressProperty(), service.upCountProperty()));
        scannedTile.textProperty().bind(Bindings.createStringBinding(() -> service.totalProperty().get() == 0 ? "0"
                : service.progressProperty().get() + " / " + service.totalProperty().get(),
                service.progressProperty(), service.totalProperty()));

        service.statusProperty().addListener((obs, old, now) -> updateScanButton(now));
        updateScanButton(service.statusProperty().get());
        scanButton.setOnAction(e -> toggleScan());
        baseField.setOnAction(e -> toggleScan());
    }

    private static String labelFor(int port) {
        String name = com.linkscope.core.KnownPorts.nameOf(port);
        return name.isEmpty() ? "" : " " + name;
    }

    private void buildLegend() {
        legendBox.getChildren().clear();
        legendBox.getChildren().addAll(
                legend("up", "up"),
                legend("arp", "ARP only"),
                legend("down", "silent"),
                legend("self", "this PC"),
                legend("gateway", "gateway"));
    }

    private static HBox legend(String state, String text) {
        StackPane swatch = new StackPane();
        swatch.getStyleClass().addAll("ls-hostcell", "ls-legend-swatch");
        if (state.equals("self") || state.equals("gateway")) {
            swatch.getStyleClass().addAll("down", state);
        } else {
            swatch.getStyleClass().add(state);
        }
        Label label = new Label(text);
        label.getStyleClass().addAll("text-muted", "small");
        HBox box = new HBox(6, swatch, label);
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return box;
    }

    // --- networks -------------------------------------------------------------------------

    private void loadNetworks() {
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            List<Ipv4Network> nets = NetInterfaces.ipv4Networks();
            FxThread.run(() -> {
                String keep = pendingNetwork != null ? pendingNetwork
                        : networkCombo.getValue() != null ? networkCombo.getValue().key() : null;
                pendingNetwork = null;
                List<NetworkChoice> choices = new ArrayList<>();
                nets.forEach(n -> choices.add(new NetworkChoice(n)));
                choices.add(NetworkChoice.CUSTOM);
                List<String> currentKeys = networkCombo.getItems().stream().map(NetworkChoice::key).toList();
                List<String> newKeys = choices.stream().map(NetworkChoice::key).toList();
                if (!currentKeys.equals(newKeys)) {
                    networkCombo.getItems().setAll(choices);
                }
                NetworkChoice pick = choices.stream().filter(c -> c.key().equals(keep)).findFirst()
                        .orElse(choices.stream().filter(c -> c.net() != null && !c.net().loopback() && !c.net().virtual()
                                && !c.net().address().startsWith("169.254.")).findFirst()
                                .orElse(choices.stream().filter(c -> c.net() != null && !c.net().loopback()).findFirst()
                                        .orElse(choices.get(0))));
                if (networkCombo.getValue() == null || !networkCombo.getValue().key().equals(pick.key())) {
                    networkCombo.setValue(pick);
                } else {
                    onNetworkChosen(pick);
                }
            });
        });
    }

    private void onNetworkChosen(NetworkChoice choice) {
        boolean custom = choice == null || choice.net() == null;
        baseField.setVisible(custom);
        baseField.setManaged(custom);
        if (!custom) {
            Ipv4Network n = choice.net();
            String note = n.prefix() < 24 ? "  (the /24 around " + n.address() + "; the interface's /" + n.prefix() + " is larger)"
                    : n.prefix() > 24 ? "  (the whole /24; the interface's /" + n.prefix() + " is smaller)" : "";
            networkLabel.setText("Sweeps " + n.base24() + ".0 – " + n.base24() + ".255" + note);
        } else {
            networkLabel.setText("Sweeps the /24 typed on the right");
        }
    }

    // --- scan -----------------------------------------------------------------------------

    private void toggleScan() {
        if (service.isRunning()) {
            service.stop();
            return;
        }
        NetworkChoice choice = networkCombo.getValue();
        try {
            if (choice != null && choice.net() != null) {
                service.setBase(choice.net().base24());
                service.setSelfIp(choice.net().address());
            } else {
                service.setBase(Fields.text(baseField));
                service.setSelfIp("");
            }
            service.setRange(Fields.intValue(fromField, "From", 0, 255), Fields.intValue(toField, "To", 0, 255));
            service.setTimeoutMs(Fields.intValue(timeoutField, "Timeout", 50, 60_000));
            service.setProbePorts(probePortsCheck.isSelected());
            service.setResolveNames(resolveNamesCheck.isSelected());
            service.setReadArp(arpCheck.isSelected());
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
        String base = service.base();
        grid.reset(base, Fields.intValue(fromField, "From", 0, 255), Fields.intValue(toField, "To", 0, 255));
        table.getSelectionModel().clearSelection();
        // The gateway lookup shells out; do it off the FX thread, then start.
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            String gateway = "";
            for (Gateway g : NetInterfaces.defaultGateways()) {
                if (g.address().startsWith(base + ".")) {
                    gateway = g.address();
                    break;
                }
            }
            service.setGatewayIp(gateway);
            service.start();
        });
    }

    private void jumpTo(int octet) {
        for (HostResult r : table.getItems()) {
            if (r.lastOctet() == octet) {
                table.getSelectionModel().select(r);
                table.scrollTo(r);
                return;
            }
        }
        // the host is filtered out (down); show it briefly by lifting the filter
        if (onlyUpToggle.isSelected()) {
            for (HostResult r : service.results()) {
                if (r.lastOctet() == octet) {
                    Toasts.info(r.ip() + ": no answer" + (r.role().isEmpty() ? "" : " (" + r.role() + ")"));
                    return;
                }
            }
        }
    }

    private void applyFilter() {
        filtered.setPredicate(onlyUpToggle.isSelected() ? r -> r.state() == HostState.UP : null);
    }

    private void copyUpAddresses() {
        String text = service.results().stream().filter(r -> r.state() == HostState.UP)
                .sorted(Comparator.comparingInt(HostResult::lastOctet))
                .map(r -> r.ip() + (r.mac().isEmpty() ? "" : "\t" + r.mac()) + (r.hostname().isEmpty() ? "" : "\t" + r.hostname()))
                .collect(Collectors.joining("\n"));
        if (text.isEmpty()) {
            Toasts.info("No hosts up to copy");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        Toasts.success("Copied " + text.lines().count() + " address(es)");
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
        m.put("network", networkCombo.getValue() == null ? "" : networkCombo.getValue().key());
        m.put("base", Fields.text(baseField));
        m.put("from", Fields.text(fromField));
        m.put("to", Fields.text(toField));
        m.put("timeoutMs", Fields.text(timeoutField));
        m.put("probePorts", Boolean.toString(probePortsCheck.isSelected()));
        m.put("resolveNames", Boolean.toString(resolveNamesCheck.isSelected()));
        m.put("arp", Boolean.toString(arpCheck.isSelected()));
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        String network = fields.get("network");
        if (network != null && !network.isBlank()) {
            pendingNetwork = network;
            networkCombo.getItems().stream().filter(c -> c.key().equals(network)).findFirst().ifPresent(c -> {
                pendingNetwork = null;
                networkCombo.setValue(c);
            });
        }
        Fields.apply(fields, "base", baseField);
        Fields.apply(fields, "from", fromField);
        Fields.apply(fields, "to", toField);
        Fields.apply(fields, "timeoutMs", timeoutField);
        Fields.apply(fields, "probePorts", probePortsCheck);
        Fields.apply(fields, "resolveNames", resolveNamesCheck);
        Fields.apply(fields, "arp", arpCheck);
    }

    @Override
    public boolean navigate(String target) {
        switch (target) {
            case "focus:network" -> com.linkscope.core.ui.Highlight.flash(networkCombo);
            case "focus:arp" -> com.linkscope.core.ui.Highlight.flash(arpCheck);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
