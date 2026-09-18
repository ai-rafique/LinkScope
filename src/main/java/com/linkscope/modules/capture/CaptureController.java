package com.linkscope.modules.capture;

import com.linkscope.core.FxThread;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.PayloadCodec;
import com.linkscope.core.ui.DecoderBridge;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.capture.CaptureService.Device;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/** Capture tab: interface, BPF capture filter, live packet table with display filter, detail pane, pcap files. */
public class CaptureController implements ModuleController {
    public static final String ID = "capture";
    private static final Map<ModuleStatus, String> LABELS = Map.of(
            ModuleStatus.DISCONNECTED, "Idle",
            ModuleStatus.CONNECTING, "Starting…",
            ModuleStatus.CONNECTED, "Capturing",
            ModuleStatus.ERROR, "Error");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @FXML private ComboBox<Device> deviceCombo;
    @FXML private Button refreshButton;
    @FXML private TextField bpfField;
    @FXML private CheckBox promiscuousCheck;
    @FXML private Button startButton;
    @FXML private StatusBadge statusBadge;
    @FXML private TextField displayFilterField;
    @FXML private Label filterStatusLabel;
    @FXML private ToggleButton followToggle;
    @FXML private Label statsLabel;
    @FXML private Button openButton;
    @FXML private Button saveButton;
    @FXML private Button clearButton;
    @FXML private TableView<PacketRow> table;
    @FXML private TableColumn<PacketRow, String> numberColumn;
    @FXML private TableColumn<PacketRow, String> timeColumn;
    @FXML private TableColumn<PacketRow, String> srcColumn;
    @FXML private TableColumn<PacketRow, String> dstColumn;
    @FXML private TableColumn<PacketRow, String> srcPortColumn;
    @FXML private TableColumn<PacketRow, String> dstPortColumn;
    @FXML private TableColumn<PacketRow, String> protocolColumn;
    @FXML private TableColumn<PacketRow, String> lengthColumn;
    @FXML private TableColumn<PacketRow, String> infoColumn;
    @FXML private TextArea detailArea;
    @FXML private Label deviceHint;

    private final CaptureService service = new CaptureService();
    private final FilteredList<PacketRow> filtered = new FilteredList<>(service.rows());
    private String pendingDevice;

    @FXML
    private void initialize() {
        statusBadge.setLabels(LABELS);
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding capturing = service.statusProperty().isEqualTo(ModuleStatus.CONNECTED)
                .or(service.statusProperty().isEqualTo(ModuleStatus.CONNECTING));
        deviceCombo.disableProperty().bind(capturing);
        refreshButton.disableProperty().bind(capturing);
        bpfField.disableProperty().bind(capturing);
        promiscuousCheck.disableProperty().bind(capturing);
        openButton.disableProperty().bind(capturing);

        deviceCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Device d) {
                return d == null ? "" : d.label();
            }

            @Override
            public Device fromString(String s) {
                return null;
            }
        });
        refreshButton.setOnAction(e -> loadDevices());
        loadDevices();

        numberColumn.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().number())));
        timeColumn.setCellValueFactory(c -> new SimpleStringProperty(String.format("%.6f", c.getValue().relativeSeconds())));
        srcColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().src()));
        dstColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().dst()));
        srcPortColumn.setCellValueFactory(c -> new SimpleStringProperty(port(c.getValue().srcPort())));
        dstPortColumn.setCellValueFactory(c -> new SimpleStringProperty(port(c.getValue().dstPort())));
        protocolColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().protocolColumn()));
        lengthColumn.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().length())));
        infoColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().info()));
        table.setItems(filtered);
        table.setPlaceholder(new Label("Pick an interface and press Start, or open a .pcap file."));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> showDetail(now));
        table.setContextMenu(contextMenu());
        filtered.addListener((ListChangeListener<PacketRow>) change -> {
            if (followToggle.isSelected() && !filtered.isEmpty() && table.getSelectionModel().getSelectedItem() == null) {
                table.scrollTo(filtered.size() - 1);
            }
        });

        displayFilterField.textProperty().addListener((obs, old, now) -> applyDisplayFilter());
        applyDisplayFilter();

        statsLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            long shown = filtered.size();
            long all = service.rows().size();
            return service.capturedProperty().get() + " captured · " + (shown == all ? all + " shown" : shown + " of " + all + " shown")
                    + " · " + humanBytes(service.bytesProperty().get())
                    + (service.droppedProperty().get() > 0 ? " · " + service.droppedProperty().get() + " dropped by driver" : "");
        }, filtered, service.rows(), service.capturedProperty(), service.bytesProperty(), service.droppedProperty()));

        service.statusProperty().addListener((obs, old, now) -> updateStartButton(now));
        updateStartButton(service.statusProperty().get());
        startButton.setOnAction(e -> toggleCapture());
        bpfField.setOnAction(e -> toggleCapture());
        openButton.setOnAction(e -> openPcap());
        saveButton.setOnAction(e -> savePcap());
        clearButton.setOnAction(e -> {
            service.clear();
            detailArea.clear();
        });
    }

    private static String port(Integer p) {
        return p == null ? "" : String.valueOf(p);
    }

    private static String humanBytes(long b) {
        if (b < 1024) {
            return b + " B";
        }
        if (b < 1024 * 1024) {
            return String.format("%.1f KB", b / 1024.0);
        }
        return String.format("%.1f MB", b / (1024.0 * 1024));
    }

    // --- devices ------------------------------------------------------------------------

    private void loadDevices() {
        deviceHint.setText("Listing interfaces…");
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            try {
                List<Device> devices = CaptureService.devices();
                FxThread.run(() -> {
                    String keep = pendingDevice != null ? pendingDevice
                            : deviceCombo.getValue() != null ? deviceCombo.getValue().name() : null;
                    pendingDevice = null;
                    deviceCombo.getItems().setAll(devices);
                    selectDevice(keep);
                    deviceHint.setText(devices.isEmpty() ? "No capture interfaces found." : devices.size() + " interface(s)");
                });
            } catch (IllegalStateException ex) {
                FxThread.run(() -> {
                    deviceHint.setText(ex.getMessage());
                    LogSink.get().error(CaptureService.TAG, ex.getMessage());
                });
            }
        });
    }

    private void selectDevice(String name) {
        for (Device d : deviceCombo.getItems()) {
            if (d.name().equals(name)) {
                deviceCombo.setValue(d);
                return;
            }
        }
        // the list is ranked physical-first; take the first adapter with a routable IPv4
        for (Device d : deviceCombo.getItems()) {
            if (!d.loopback() && d.hasRoutableIpv4()) {
                deviceCombo.setValue(d);
                return;
            }
        }
        if (!deviceCombo.getItems().isEmpty()) {
            deviceCombo.getSelectionModel().selectFirst();
        }
    }

    // --- capture ------------------------------------------------------------------------

    private void toggleCapture() {
        if (service.isRunning()) {
            service.stop();
            return;
        }
        Device d = deviceCombo.getValue();
        if (d == null) {
            Toasts.error("Pick an interface first");
            return;
        }
        service.setDevice(d.name());
        service.setCaptureFilter(Fields.text(bpfField));
        service.setPromiscuous(promiscuousCheck.isSelected());
        service.start();
    }

    private void updateStartButton(ModuleStatus s) {
        boolean on = s == ModuleStatus.CONNECTED || s == ModuleStatus.CONNECTING;
        startButton.setText(on ? "Stop" : "Start");
        startButton.setGraphic(new FontIcon(on ? "fth-square" : "fth-play"));
    }

    private void applyDisplayFilter() {
        String text = Fields.text(displayFilterField);
        try {
            Predicate<PacketRow> p = DisplayFilter.compile(text);
            filtered.setPredicate(text.isEmpty() ? null : p);
            filterStatusLabel.setText(text.isEmpty() ? "" : "filter active");
            filterStatusLabel.getStyleClass().remove("ls-error-text");
        } catch (IllegalArgumentException ex) {
            filterStatusLabel.setText(ex.getMessage());
            if (!filterStatusLabel.getStyleClass().contains("ls-error-text")) {
                filterStatusLabel.getStyleClass().add("ls-error-text");
            }
        }
    }

    private void showDetail(PacketRow row) {
        if (row == null) {
            detailArea.clear();
            return;
        }
        String header = "#" + row.number() + "  " + TIME.format(row.time().atZone(ZoneId.systemDefault()))
                + "  (+" + String.format("%.6f", row.relativeSeconds()) + " s)  " + row.length() + " bytes\n";
        detailArea.setText(header + row.details());
        detailArea.positionCaret(0);
    }

    private ContextMenu contextMenu() {
        MenuItem conversation = new MenuItem("Filter this conversation");
        conversation.setOnAction(e -> {
            PacketRow r = table.getSelectionModel().getSelectedItem();
            if (r == null) {
                return;
            }
            String f = "ip.addr == " + r.src() + " and ip.addr == " + r.dst();
            if (r.srcPort() != null && r.dstPort() != null) {
                f += " and port == " + r.srcPort() + " and port == " + r.dstPort();
            }
            displayFilterField.setText(f);
        });
        MenuItem host = new MenuItem("Filter this host");
        host.setOnAction(e -> {
            PacketRow r = table.getSelectionModel().getSelectedItem();
            if (r != null) {
                displayFilterField.setText("ip.addr == " + r.src());
            }
        });
        MenuItem clearFilter = new MenuItem("Clear display filter");
        clearFilter.setOnAction(e -> displayFilterField.clear());
        MenuItem decode = new MenuItem("Send payload to Decoder");
        decode.setOnAction(e -> {
            PacketRow r = table.getSelectionModel().getSelectedItem();
            if (r != null && r.payload() != null && r.payload().length > 0) {
                DecoderBridge.open(r.payload());
            } else {
                Toasts.info("This packet has no payload above the transport layer");
            }
        });
        MenuItem copyPayload = new MenuItem("Copy payload as hex");
        copyPayload.setOnAction(e -> {
            PacketRow r = table.getSelectionModel().getSelectedItem();
            if (r != null && r.payload() != null) {
                copy(PayloadCodec.toHex(r.payload()));
            }
        });
        MenuItem copyFrame = new MenuItem("Copy whole frame as hex");
        copyFrame.setOnAction(e -> {
            PacketRow r = table.getSelectionModel().getSelectedItem();
            if (r != null) {
                copy(PayloadCodec.toHex(r.raw()));
            }
        });
        return new ContextMenu(conversation, host, clearFilter, decode, copyPayload, copyFrame);
    }

    private static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    // --- files --------------------------------------------------------------------------

    private void openPcap() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open capture");
        chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Capture files (*.pcap, *.cap)", "*.pcap", "*.cap"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File f = chooser.showOpenDialog(table.getScene().getWindow());
        if (f != null) {
            service.openFile(f.toPath());
        }
    }

    private void savePcap() {
        if (filtered.isEmpty()) {
            Toasts.warning("Nothing to save");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save capture" + (filtered.size() == service.rows().size() ? "" : " (filtered view)"));
        chooser.setInitialFileName("linkscope-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now()) + ".pcap");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("pcap (*.pcap)", "*.pcap"));
        File f = chooser.showSaveDialog(table.getScene().getWindow());
        if (f != null) {
            service.saveFile(f.toPath(), filtered, () -> Toasts.success("Saved " + f.getName()));
        }
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
        m.put("device", deviceCombo.getValue() == null ? "" : deviceCombo.getValue().name());
        m.put("captureFilter", Fields.text(bpfField));
        m.put("promiscuous", Boolean.toString(promiscuousCheck.isSelected()));
        m.put("displayFilter", Fields.text(displayFilterField));
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        String device = fields.get("device");
        if (device != null && !device.isBlank()) {
            pendingDevice = device;
            selectDevice(device);
        }
        Fields.apply(fields, "captureFilter", bpfField);
        Fields.apply(fields, "promiscuous", promiscuousCheck);
        Fields.apply(fields, "displayFilter", displayFilterField);
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
