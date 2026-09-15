package com.linkscope.modules.udp;

import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.SendBarController;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.kordamp.ikonli.javafx.FontIcon;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

public class UdpController implements ModuleController {
    public static final String ID = "udp";

    @FXML private TextField bindAddressField;
    @FXML private TextField bindPortField;
    @FXML private CheckBox broadcastCheck;
    @FXML private Button listenButton;
    @FXML private StatusBadge statusBadge;
    @FXML private Label lastSenderLabel;
    @FXML private TextField targetHostField;
    @FXML private TextField targetPortField;
    @FXML private CheckBox replyToLastCheck;
    @FXML private SendBarController sendBarController;

    private final UdpService service = new UdpService();

    @FXML
    private void initialize() {
        statusBadge.statusProperty().bind(service.statusProperty());

        BooleanBinding active = Bindings.createBooleanBinding(
                () -> service.statusProperty().get().isActive(), service.statusProperty());
        bindAddressField.disableProperty().bind(active);
        bindPortField.disableProperty().bind(active);
        broadcastCheck.disableProperty().bind(active);
        replyToLastCheck.disableProperty().bind(service.lastSenderProperty().isNull());

        service.statusProperty().addListener((obs, old, now) -> updateListenButton(now));
        updateListenButton(service.statusProperty().get());
        service.lastSenderProperty().addListener((obs, old, now) -> showLastSender(now));

        listenButton.setOnAction(e -> toggleListen());
        sendBarController.setOnSend(this::send);
    }

    private void toggleListen() {
        if (service.statusProperty().get().isActive()) {
            service.stop();
            return;
        }
        try {
            service.setBindAddress(Fields.text(bindAddressField));
            service.setBindPort(Fields.port(bindPortField, "Bind port"));
            service.setBroadcast(broadcastCheck.isSelected());
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
        service.start();
    }

    private void send(byte[] data) {
        if (replyToLastCheck.isSelected() && !replyToLastCheck.isDisabled()) {
            service.replyToLastSender(data);
            return;
        }
        try {
            service.setTargetHost(Fields.requireText(targetHostField, "Target host"));
            service.setTargetPort(Fields.port(targetPortField, "Target port"));
            service.setBroadcast(broadcastCheck.isSelected());
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
        service.send(data);
    }

    private void updateListenButton(ModuleStatus s) {
        boolean active = s != null && s.isActive();
        listenButton.setText(active ? "Stop listening" : "Start listening");
        listenButton.setGraphic(new FontIcon(active ? "fth-square" : "fth-play"));
    }

    private void showLastSender(InetSocketAddress from) {
        lastSenderLabel.setText(from == null ? "No datagram received yet"
                : "Last sender: " + UdpService.describe(from));
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
        m.put("bindAddress", Fields.text(bindAddressField));
        m.put("bindPort", Fields.text(bindPortField));
        m.put("broadcast", Boolean.toString(broadcastCheck.isSelected()));
        m.put("targetHost", Fields.text(targetHostField));
        m.put("targetPort", Fields.text(targetPortField));
        m.putAll(sendBarController.captureFields());
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        Fields.apply(fields, "bindAddress", bindAddressField);
        Fields.apply(fields, "bindPort", bindPortField);
        Fields.apply(fields, "broadcast", broadcastCheck);
        Fields.apply(fields, "targetHost", targetHostField);
        Fields.apply(fields, "targetPort", targetPortField);
        sendBarController.applyFields(fields);
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
