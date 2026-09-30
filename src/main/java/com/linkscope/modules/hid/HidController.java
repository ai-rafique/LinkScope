package com.linkscope.modules.hid;

import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.PayloadCodec;
import com.linkscope.core.ui.DecoderBridge;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/** HID tab: pick an input device, watch its buttons and axes live, and drive rumble or output reports back. */
public class HidController implements ModuleController {
    public static final String ID = "hid";
    private static final Map<ModuleStatus, String> LABELS = Map.of(
            ModuleStatus.DISCONNECTED, "Idle",
            ModuleStatus.CONNECTING, "Opening…",
            ModuleStatus.CONNECTED, "Reading",
            ModuleStatus.ERROR, "Error");

    @FXML private ComboBox<HidDevice> deviceCombo;
    @FXML private Button refreshButton;
    @FXML private Button openButton;
    @FXML private StatusBadge statusBadge;
    @FXML private Label deviceHint;
    @FXML private Label infoLabel;
    @FXML private VBox viewHost;
    @FXML private Label rawLabel;
    @FXML private Label rateLabel;
    @FXML private Button decodeButton;
    @FXML private CheckBox logCheck;
    @FXML private Slider leftSlider;
    @FXML private Slider rightSlider;
    @FXML private Button pulseButton;
    @FXML private Button rumbleStopButton;
    @FXML private TextField outputField;
    @FXML private Button sendOutputButton;
    @FXML private Label summaryLabel;

    private final HidService service = new HidService();
    private ControllerView view;
    private String pendingDevice;
    private final PauseTransition pulseEnd = new PauseTransition(Duration.millis(600));
    private final PauseTransition rumbleDebounce = new PauseTransition(Duration.millis(60));

    @FXML
    private void initialize() {
        statusBadge.setLabels(LABELS);
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding open = service.statusProperty().isEqualTo(ModuleStatus.CONNECTED)
                .or(service.statusProperty().isEqualTo(ModuleStatus.CONNECTING));
        deviceCombo.disableProperty().bind(open);
        refreshButton.disableProperty().bind(open);

        deviceCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(HidDevice d) {
                return d == null ? "" : d.label();
            }

            @Override
            public HidDevice fromString(String s) {
                return null;
            }
        });
        deviceCombo.valueProperty().addListener((obs, old, now) -> describe(now));
        deviceCombo.setOnShowing(e -> loadDevices());
        refreshButton.setOnAction(e -> loadDevices());
        loadDevices();

        service.layoutProperty().addListener((obs, old, now) -> rebuildView(now));
        service.latestProperty().addListener((obs, old, now) -> showReport(now));
        rateLabel.textProperty().bind(Bindings.createStringBinding(
                () -> service.reportCountProperty().get() + " report(s) · " + service.reportsPerSecondProperty().get() + " per second",
                service.reportCountProperty(), service.reportsPerSecondProperty()));
        service.statusProperty().addListener((obs, old, now) -> {
            updateOpenButton(now);
            updateOutputControls();
            if (now == ModuleStatus.DISCONNECTED || now == ModuleStatus.ERROR) {
                summarize();
            }
        });
        updateOpenButton(service.statusProperty().get());
        updateOutputControls();
        openButton.setOnAction(e -> toggle());
        logCheck.selectedProperty().addListener((obs, old, now) -> service.setLogReports(now));

        decodeButton.setOnAction(e -> {
            HidReport r = service.latestProperty().get();
            if (r == null || r.raw().length == 0) {
                Toasts.info("No report yet");
            } else {
                DecoderBridge.open(r.raw());
            }
        });

        leftSlider.valueProperty().addListener((obs, old, now) -> rumbleDebounce.playFromStart());
        rightSlider.valueProperty().addListener((obs, old, now) -> rumbleDebounce.playFromStart());
        rumbleDebounce.setOnFinished(e -> {
            if (service.canWrite() && service.device() != null && service.device().isXInput()) {
                service.rumble(leftSlider.getValue() / 100, rightSlider.getValue() / 100);
            }
        });
        pulseButton.setOnAction(e -> {
            service.rumble(1, 1);
            pulseEnd.playFromStart();
        });
        pulseEnd.setOnFinished(e -> service.rumble(leftSlider.getValue() / 100, rightSlider.getValue() / 100));
        rumbleStopButton.setOnAction(e -> {
            leftSlider.setValue(0);
            rightSlider.setValue(0);
            service.rumble(0, 0);
        });
        sendOutputButton.setOnAction(e -> sendOutput());
        outputField.setOnAction(e -> sendOutput());
    }

    // --- devices ------------------------------------------------------------------------

    private void loadDevices() {
        if (!HidService.available()) {
            deviceHint.setText(HidService.unavailableReason());
            return;
        }
        deviceHint.setText("Listing devices…");
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            List<HidDevice> devices = HidService.devices();
            FxThread.run(() -> {
                String keep = pendingDevice != null ? pendingDevice : deviceCombo.getValue() != null ? deviceCombo.getValue().path() : null;
                pendingDevice = null;
                List<String> current = deviceCombo.getItems().stream().map(HidDevice::path).toList();
                if (!current.equals(devices.stream().map(HidDevice::path).toList())) {
                    deviceCombo.getItems().setAll(devices);
                }
                HidDevice pick = devices.stream().filter(d -> d.path().equals(keep)).findFirst()
                        .orElse(devices.stream().filter(HidDevice::isGameController).findFirst()
                                .orElse(devices.isEmpty() ? null : devices.get(0)));
                deviceCombo.setValue(pick);
                long controllers = devices.stream().filter(HidDevice::isGameController).count();
                deviceHint.setText(devices.isEmpty() ? "No HID devices found"
                        : devices.size() + " device(s), " + controllers + " game controller(s)");
            });
        });
    }

    private void describe(HidDevice d) {
        if (d == null) {
            infoLabel.setText("");
            return;
        }
        if (d.isXInput()) {
            infoLabel.setText("Read through XInput: buttons, both sticks and both triggers separately, plus rumble. "
                    + "The same controller also appears as a HID gamepad in this list, where the triggers share one axis and rumble is not available.");
        } else {
            String who = d.manufacturer().isBlank() || d.product().toLowerCase().startsWith(d.manufacturer().toLowerCase())
                    ? d.product() : d.manufacturer() + " " + d.product();
            infoLabel.setText(who.trim() + "  ·  " + d.ids() + "  ·  " + d.kind() + "  ·  " + d.buttonCount() + " button group(s), "
                    + d.valueCount() + " value(s)  ·  input " + d.inputLength() + " B, output " + d.outputLength() + " B"
                    + (d.usage() == HidUsages.USAGE_KEYBOARD || d.usage() == HidUsages.USAGE_MOUSE
                    ? "  ·  Windows keeps keyboards and mice exclusive; opening will fail" : ""));
        }
    }

    // --- reading ------------------------------------------------------------------------

    private void toggle() {
        if (service.isRunning()) {
            service.stop();
            return;
        }
        HidDevice d = deviceCombo.getValue();
        if (d == null) {
            Toasts.error("Pick a device first");
            return;
        }
        viewHost.getChildren().clear();
        view = null;
        rawLabel.setText("");
        summaryLabel.setText("");
        service.setDevice(d);
        service.setLogReports(logCheck.isSelected());
        service.start();
    }

    private void rebuildView(HidLayout layout) {
        viewHost.getChildren().clear();
        if (layout == null) {
            return;
        }
        view = new ControllerView(layout);
        viewHost.getChildren().add(view);
    }

    private void showReport(HidReport r) {
        if (r == null) {
            return;
        }
        if (view != null) {
            view.show(r);
        }
        rawLabel.setText("report id " + r.reportId() + " · " + r.raw().length + " B · " + PayloadCodec.toHex(r.raw()));
    }

    private void summarize() {
        if (view == null) {
            return;
        }
        Set<String> never = view.neverPressed();
        if (never.isEmpty()) {
            summaryLabel.setText("Every button registered at least one press this session.");
        } else if (never.size() > 8) {
            summaryLabel.setText(never.size() + " button(s) were never pressed this session.");
        } else {
            summaryLabel.setText("Never pressed this session: " + String.join(", ", never));
        }
    }

    private void updateOpenButton(ModuleStatus s) {
        boolean on = s == ModuleStatus.CONNECTED || s == ModuleStatus.CONNECTING;
        openButton.setText(on ? "Close" : "Open");
        openButton.setGraphic(new FontIcon(on ? "fth-square" : "fth-play"));
    }

    private void updateOutputControls() {
        HidDevice d = service.device();
        boolean xinput = service.isRunning() && d != null && d.isXInput();
        boolean hidOut = service.isRunning() && d != null && !d.isXInput() && d.outputLength() > 0;
        for (var n : List.of(leftSlider, rightSlider, pulseButton, rumbleStopButton)) {
            n.setDisable(!xinput);
        }
        outputField.setDisable(!hidOut);
        sendOutputButton.setDisable(!hidOut);
        if (hidOut) {
            outputField.setPromptText("hex, " + d.outputLength() + " bytes starting with the report id, e.g. 00 01 00 …");
        }
    }

    private void sendOutput() {
        try {
            byte[] data = PayloadCodec.parseHex(Fields.text(outputField));
            if (data.length == 0) {
                Toasts.info("Type the output report as hex, report id first");
                return;
            }
            service.sendOutput(data);
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
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
        m.put("device", deviceCombo.getValue() == null ? "" : deviceCombo.getValue().path());
        m.put("logReports", Boolean.toString(logCheck.isSelected()));
        m.put("output", Fields.text(outputField));
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        String device = fields.get("device");
        if (device != null && !device.isBlank()) {
            pendingDevice = device;
            deviceCombo.getItems().stream().filter(d -> d.path().equals(device)).findFirst().ifPresent(d -> {
                pendingDevice = null;
                deviceCombo.setValue(d);
            });
        }
        Fields.apply(fields, "logReports", logCheck);
        Fields.apply(fields, "output", outputField);
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
