package com.linkscope.modules.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.SendBarController;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.serial.SerialService.FlowControl;
import com.linkscope.modules.serial.SerialService.Parity;
import com.linkscope.modules.serial.SerialService.StopBits;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class SerialController implements ModuleController {
    public static final String ID = "serial";

    /** Combo entry: system name plus description. */
    record PortChoice(String name, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    @FXML private ComboBox<PortChoice> portCombo;
    @FXML private Button refreshButton;
    @FXML private ComboBox<String> baudCombo;
    @FXML private ComboBox<Integer> dataBitsCombo;
    @FXML private ComboBox<Parity> parityCombo;
    @FXML private ComboBox<StopBits> stopBitsCombo;
    @FXML private ComboBox<FlowControl> flowCombo;
    @FXML private CheckBox dtrCheck;
    @FXML private CheckBox rtsCheck;
    @FXML private Button openButton;
    @FXML private StatusBadge statusBadge;
    @FXML private Label portsHint;
    @FXML private SendBarController sendBarController;

    private final SerialService service = new SerialService();
    private String pendingPortName;

    @FXML
    private void initialize() {
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding active = Bindings.createBooleanBinding(
                () -> service.statusProperty().get().isActive(), service.statusProperty());
        for (var node : List.of(portCombo, refreshButton, baudCombo, dataBitsCombo, parityCombo, stopBitsCombo, flowCombo)) {
            node.disableProperty().bind(active);
        }
        sendBarController.sendDisabledProperty().bind(service.statusProperty().isNotEqualTo(ModuleStatus.CONNECTED));

        for (int b : SerialService.COMMON_BAUD_RATES) {
            baudCombo.getItems().add(Integer.toString(b));
        }
        baudCombo.setValue("9600");
        dataBitsCombo.getItems().setAll(8, 7, 6, 5);
        dataBitsCombo.setValue(8);
        parityCombo.getItems().setAll(Parity.values());
        parityCombo.setValue(Parity.NONE);
        stopBitsCombo.getItems().setAll(StopBits.values());
        stopBitsCombo.setValue(StopBits.ONE);
        flowCombo.getItems().setAll(FlowControl.values());
        flowCombo.setValue(FlowControl.NONE);
        portCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(PortChoice c) {
                return c == null ? "" : c.label();
            }

            @Override
            public PortChoice fromString(String s) {
                return null;
            }
        });

        dtrCheck.selectedProperty().addListener((obs, old, now) -> service.setDtr(now));
        rtsCheck.selectedProperty().addListener((obs, old, now) -> service.setRts(now));
        service.statusProperty().addListener((obs, old, now) -> updateOpenButton(now));
        updateOpenButton(service.statusProperty().get());
        openButton.setOnAction(e -> toggleOpen());
        refreshButton.setOnAction(e -> refreshPorts());
        sendBarController.setOnSend(service::send);
        sendBarController.applyFields(Map.of("lineEnding", "CRLF"));
        refreshPorts();
    }

    /** Port enumeration can block briefly, so it runs off the FX thread. */
    private void refreshPorts() {
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            List<PortChoice> choices = new ArrayList<>();
            for (SerialPort p : SerialService.availablePorts()) {
                choices.add(new PortChoice(p.getSystemPortName(),
                        p.getSystemPortName() + " — " + p.getDescriptivePortName()));
            }
            FxThread.run(() -> {
                String keep = pendingPortName != null ? pendingPortName
                        : portCombo.getValue() != null ? portCombo.getValue().name() : null;
                pendingPortName = null;
                portCombo.getItems().setAll(choices);
                if (choices.isEmpty()) {
                    portCombo.setPromptText("No serial ports found");
                    portsHint.setText("No serial ports found. Plug in a device or create a virtual port pair, then Refresh.");
                } else {
                    portCombo.setPromptText("Select a port");
                    portsHint.setText(choices.size() + " port(s) found.");
                    selectPort(keep);
                }
            });
        });
    }

    private void selectPort(String name) {
        for (PortChoice c : portCombo.getItems()) {
            if (c.name().equals(name)) {
                portCombo.setValue(c);
                return;
            }
        }
        if (portCombo.getValue() == null && !portCombo.getItems().isEmpty()) {
            portCombo.getSelectionModel().selectFirst();
        }
    }

    private void toggleOpen() {
        if (service.statusProperty().get().isActive()) {
            service.stop();
            return;
        }
        try {
            PortChoice choice = portCombo.getValue();
            if (choice == null) {
                throw new IllegalArgumentException("Select a serial port first");
            }
            service.setPortName(choice.name());
            String baud = baudCombo.getEditor().getText();
            if (baud == null || baud.isBlank()) {
                baud = baudCombo.getValue();
            }
            service.setBaudRate(Integer.parseInt(baud == null ? "" : baud.trim()));
            service.setDataBits(dataBitsCombo.getValue());
            service.setParity(parityCombo.getValue());
            service.setStopBits(stopBitsCombo.getValue());
            service.setFlowControl(flowCombo.getValue());
            service.setDtr(dtrCheck.isSelected());
            service.setRts(rtsCheck.isSelected());
        } catch (NumberFormatException ex) {
            Toasts.error("Baud rate must be a whole number");
            return;
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
        service.start();
    }

    private void updateOpenButton(ModuleStatus s) {
        boolean active = s != null && s.isActive();
        openButton.setText(active ? "Close port" : "Open port");
        openButton.setGraphic(new FontIcon(active ? "fth-square" : "fth-play"));
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
    public Map<String, String> captureFields() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("port", portCombo.getValue() == null ? "" : portCombo.getValue().name());
        String baud = baudCombo.getEditor().getText();
        m.put("baud", baud == null || baud.isBlank() ? String.valueOf(baudCombo.getValue()) : baud.trim());
        m.put("dataBits", String.valueOf(dataBitsCombo.getValue()));
        m.put("parity", parityCombo.getValue().name());
        m.put("stopBits", stopBitsCombo.getValue().name());
        m.put("flowControl", flowCombo.getValue().name());
        m.put("dtr", Boolean.toString(dtrCheck.isSelected()));
        m.put("rts", Boolean.toString(rtsCheck.isSelected()));
        m.putAll(sendBarController.captureFields());
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        String port = fields.get("port");
        if (port != null && !port.isBlank()) {
            pendingPortName = port;
            selectPort(port);
            if (portCombo.getValue() == null || !portCombo.getValue().name().equals(port)) {
                refreshPorts();
            }
        }
        if (fields.containsKey("baud")) {
            baudCombo.setValue(fields.get("baud"));
        }
        if (fields.containsKey("dataBits")) {
            try {
                dataBitsCombo.setValue(Integer.parseInt(fields.get("dataBits")));
            } catch (NumberFormatException ignored) {
                // keep current
            }
        }
        applyEnum(fields, "parity", Parity.class, parityCombo);
        applyEnum(fields, "stopBits", StopBits.class, stopBitsCombo);
        applyEnum(fields, "flowControl", FlowControl.class, flowCombo);
        Fields.apply(fields, "dtr", dtrCheck);
        Fields.apply(fields, "rts", rtsCheck);
        sendBarController.applyFields(fields);
    }

    private static <E extends Enum<E>> void applyEnum(Map<String, String> fields, String key, Class<E> type, ComboBox<E> combo) {
        String v = fields.get(key);
        if (v == null) {
            return;
        }
        try {
            combo.setValue(Enum.valueOf(type, v));
        } catch (IllegalArgumentException ignored) {
            // keep current
        }
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
