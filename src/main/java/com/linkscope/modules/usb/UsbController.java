package com.linkscope.modules.usb;

import com.linkscope.core.FxThread;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.PayloadCodec;
import com.linkscope.core.ui.DecoderBridge;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.capture.DisplayFilter;
import com.linkscope.modules.capture.PacketRow;
import com.linkscope.modules.usb.UsbCaptureService.Hub;
import com.linkscope.modules.usb.UsbCaptureService.UsbDevice;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/** USB tab: root hub, device tick list, live USBPcap capture table with display filter, detail pane, pcap files. */
public class UsbController implements ModuleController {
    public static final String ID = "usb";
    private static final Map<ModuleStatus, String> LABELS = Map.of(
            ModuleStatus.DISCONNECTED, "Idle",
            ModuleStatus.CONNECTING, "Starting…",
            ModuleStatus.CONNECTED, "Capturing",
            ModuleStatus.ERROR, "Error");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @FXML private ComboBox<Hub> hubCombo;
    @FXML private Button refreshButton;
    @FXML private ListView<UsbDevice> deviceList;
    @FXML private CheckBox allDevicesCheck;
    @FXML private CheckBox descriptorsCheck;
    @FXML private Button usb3Button;
    @FXML private Button startButton;
    @FXML private StatusBadge statusBadge;
    @FXML private Label hubHint;
    @FXML private Label keyboardWarning;
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
    @FXML private TableColumn<PacketRow, String> typeColumn;
    @FXML private TableColumn<PacketRow, String> lengthColumn;
    @FXML private TableColumn<PacketRow, String> infoColumn;
    @FXML private TextArea detailArea;

    private final UsbCaptureService service = new UsbCaptureService();
    private final FilteredList<PacketRow> filtered = new FilteredList<>(service.rows());
    private final Map<String, BooleanProperty> ticks = new LinkedHashMap<>();
    private final Set<String> pendingDevices = new LinkedHashSet<>();
    private String pendingHub;

    @FXML
    private void initialize() {
        statusBadge.setLabels(LABELS);
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding capturing = service.statusProperty().isEqualTo(ModuleStatus.CONNECTED)
                .or(service.statusProperty().isEqualTo(ModuleStatus.CONNECTING));
        hubCombo.disableProperty().bind(capturing);
        refreshButton.disableProperty().bind(capturing);
        deviceList.disableProperty().bind(capturing.or(allDevicesCheck.selectedProperty()));
        allDevicesCheck.disableProperty().bind(capturing);
        descriptorsCheck.disableProperty().bind(capturing);
        usb3Button.disableProperty().bind(capturing);
        openButton.disableProperty().bind(capturing);

        hubCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Hub h) {
                return h == null ? "" : h.toString();
            }

            @Override
            public Hub fromString(String s) {
                return null;
            }
        });
        hubCombo.valueProperty().addListener((obs, old, now) -> loadDevices(now));
        refreshButton.setOnAction(e -> loadHubs());
        deviceList.setCellFactory(CheckBoxListCell.forListView(this::tickOf, new StringConverter<>() {
            @Override
            public String toString(UsbDevice d) {
                return "[" + d.address() + "]  " + d.summary();
            }

            @Override
            public UsbDevice fromString(String s) {
                return null;
            }
        }));
        deviceList.setPlaceholder(new Label("No devices reported on this hub."));
        allDevicesCheck.selectedProperty().addListener((obs, old, now) -> updateKeyboardWarning());
        usb3Button.setOnAction(e -> {
            service.initUsb3Capture();
            Toasts.info("Asking Windows to run USBPcapCMD -I as administrator");
        });
        loadHubs();

        numberColumn.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().number())));
        timeColumn.setCellValueFactory(c -> new SimpleStringProperty(String.format("%.6f", c.getValue().relativeSeconds())));
        srcColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().src()));
        dstColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().dst()));
        typeColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().protocolColumn().replace("USB ", "")));
        lengthColumn.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().payload().length)));
        infoColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().info()));
        table.setItems(filtered);
        table.setPlaceholder(new Label("Pick a root hub, tick the devices to watch and press Start, or open a USBPcap file."));
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
            return service.capturedProperty().get() + " records · " + (shown == all ? all + " shown" : shown + " of " + all + " shown")
                    + " · " + humanBytes(service.bytesProperty().get()) + " payload";
        }, filtered, service.rows(), service.capturedProperty(), service.bytesProperty()));

        service.statusProperty().addListener((obs, old, now) -> updateStartButton(now));
        updateStartButton(service.statusProperty().get());
        startButton.setOnAction(e -> toggleCapture());
        openButton.setOnAction(e -> openPcap());
        saveButton.setOnAction(e -> savePcap());
        clearButton.setOnAction(e -> {
            service.clear();
            detailArea.clear();
        });
    }

    private BooleanProperty tickOf(UsbDevice d) {
        return ticks.computeIfAbsent(d.address(), k -> {
            BooleanProperty p = new SimpleBooleanProperty(pendingDevices.contains(k));
            p.addListener((obs, old, now) -> updateKeyboardWarning());
            return p;
        });
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

    // --- hubs and devices -----------------------------------------------------------------

    private void loadHubs() {
        if (UsbCaptureService.helper() == null) {
            hubHint.setText(UsbCaptureService.helperMissingMessage());
            return;
        }
        hubHint.setText("Listing root hubs…");
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            try {
                List<Hub> hubs = UsbCaptureService.hubs();
                FxThread.run(() -> {
                    String keep = pendingHub != null ? pendingHub : hubCombo.getValue() != null ? hubCombo.getValue().name() : null;
                    pendingHub = null;
                    Hub current = hubCombo.getValue();
                    hubCombo.getItems().setAll(hubs);
                    Hub pick = hubs.stream().filter(h -> h.name().equals(keep)).findFirst().orElse(hubs.isEmpty() ? null : hubs.get(0));
                    hubCombo.setValue(pick);
                    if (pick != null && pick.equals(current)) {
                        loadDevices(pick);
                    }
                    hubHint.setText(hubs.isEmpty() ? "No USBPcap root hubs found (is the driver installed and Windows restarted?)"
                            : hubs.size() + " root hub(s)");
                });
            } catch (IOException ex) {
                FxThread.run(() -> {
                    hubHint.setText(ex.getMessage());
                    LogSink.get().error(UsbCaptureService.TAG, ex.getMessage());
                });
            }
        });
    }

    private void loadDevices(Hub hub) {
        if (hub == null) {
            deviceList.getItems().clear();
            return;
        }
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            try {
                List<UsbDevice> devices = UsbCaptureService.devices(hub.name());
                FxThread.run(() -> {
                    // keep ticks for addresses that still exist; forget the rest
                    Set<String> present = new LinkedHashSet<>();
                    devices.forEach(d -> present.add(d.address()));
                    ticks.keySet().retainAll(present);
                    deviceList.getItems().setAll(devices);
                    updateKeyboardWarning();
                });
            } catch (IOException ex) {
                FxThread.run(() -> LogSink.get().error(UsbCaptureService.TAG, "Could not list devices on " + hub.name() + ": " + ex.getMessage()));
            }
        });
    }

    private List<String> tickedAddresses() {
        List<String> out = new ArrayList<>();
        for (UsbDevice d : deviceList.getItems()) {
            if (tickOf(d).get()) {
                out.add(d.address());
            }
        }
        return out;
    }

    private void updateKeyboardWarning() {
        boolean keyboard = false;
        for (UsbDevice d : deviceList.getItems()) {
            if ((allDevicesCheck.isSelected() || tickOf(d).get()) && d.looksLikeKeyboard()) {
                keyboard = true;
                break;
            }
        }
        keyboardWarning.setVisible(keyboard);
        keyboardWarning.setManaged(keyboard);
    }

    // --- capture --------------------------------------------------------------------------

    private void toggleCapture() {
        if (service.isRunning()) {
            service.stop();
            return;
        }
        Hub hub = hubCombo.getValue();
        if (hub == null) {
            Toasts.error("Pick a root hub first");
            return;
        }
        List<String> devices = tickedAddresses();
        if (!allDevicesCheck.isSelected() && devices.isEmpty()) {
            Toasts.error("Tick at least one device, or choose all devices");
            return;
        }
        service.setHub(hub.name());
        service.setDevices(devices);
        service.setAllDevices(allDevicesCheck.isSelected());
        service.setInjectDescriptors(descriptorsCheck.isSelected());
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
        MenuItem device = new MenuItem("Filter this device");
        device.setOnAction(e -> {
            PacketRow r = table.getSelectionModel().getSelectedItem();
            if (r != null && r.field("usb.addr") != null) {
                displayFilterField.setText("usb.addr == " + r.field("usb.addr"));
            }
        });
        MenuItem endpoint = new MenuItem("Filter this endpoint");
        endpoint.setOnAction(e -> {
            PacketRow r = table.getSelectionModel().getSelectedItem();
            if (r != null && r.field("usb.addr") != null) {
                displayFilterField.setText("usb.addr == " + r.field("usb.addr") + " and usb.endpoint == " + r.field("usb.endpoint"));
            }
        });
        MenuItem dataOnly = new MenuItem("Only bulk and interrupt data");
        dataOnly.setOnAction(e -> displayFilterField.setText("(bulk or interrupt) and usb.status == success"));
        MenuItem clearFilter = new MenuItem("Clear display filter");
        clearFilter.setOnAction(e -> displayFilterField.clear());
        MenuItem decode = new MenuItem("Send payload to Decoder");
        decode.setOnAction(e -> {
            PacketRow r = table.getSelectionModel().getSelectedItem();
            if (r != null && r.payload().length > 0) {
                DecoderBridge.open(r.payload());
            } else {
                Toasts.info("This record carries no data");
            }
        });
        MenuItem copyPayload = new MenuItem("Copy payload as hex");
        copyPayload.setOnAction(e -> {
            PacketRow r = table.getSelectionModel().getSelectedItem();
            if (r != null) {
                copy(PayloadCodec.toHex(r.payload()));
            }
        });
        MenuItem copyRecord = new MenuItem("Copy whole record as hex");
        copyRecord.setOnAction(e -> {
            PacketRow r = table.getSelectionModel().getSelectedItem();
            if (r != null) {
                copy(PayloadCodec.toHex(r.raw()));
            }
        });
        return new ContextMenu(device, endpoint, dataOnly, clearFilter, decode, copyPayload, copyRecord);
    }

    private static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    // --- files ----------------------------------------------------------------------------

    private void openPcap() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open USB capture");
        chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("USBPcap files (*.pcap)", "*.pcap"),
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
        if (keyboardWarning.isVisible()) {
            Toasts.warning("This capture may contain keystrokes");
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save USB capture" + (filtered.size() == service.rows().size() ? "" : " (filtered view)"));
        chooser.setInitialFileName("linkscope-usb-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now()) + ".pcap");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("pcap (*.pcap)", "*.pcap"));
        File f = chooser.showSaveDialog(table.getScene().getWindow());
        if (f != null) {
            service.saveFile(f.toPath(), filtered, () -> Toasts.success("Saved " + f.getName()));
        }
    }

    // --- ModuleController -----------------------------------------------------------------

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
        m.put("hub", hubCombo.getValue() == null ? "" : hubCombo.getValue().name());
        m.put("devices", String.join(",", tickedAddresses()));
        m.put("allDevices", Boolean.toString(allDevicesCheck.isSelected()));
        m.put("descriptors", Boolean.toString(descriptorsCheck.isSelected()));
        m.put("displayFilter", Fields.text(displayFilterField));
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        String devices = fields.get("devices");
        if (devices != null) {
            pendingDevices.clear();
            Arrays.stream(devices.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(pendingDevices::add);
            ticks.forEach((address, tick) -> tick.set(pendingDevices.contains(address)));
        }
        String hub = fields.get("hub");
        if (hub != null && !hub.isBlank()) {
            pendingHub = hub;
            hubCombo.getItems().stream().filter(h -> h.name().equals(hub)).findFirst().ifPresent(h -> {
                pendingHub = null;
                hubCombo.setValue(h);
            });
        }
        Fields.apply(fields, "allDevices", allDevicesCheck);
        Fields.apply(fields, "descriptors", descriptorsCheck);
        Fields.apply(fields, "displayFilter", displayFilterField);
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
