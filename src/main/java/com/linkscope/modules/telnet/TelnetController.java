package com.linkscope.modules.telnet;

import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.HistoryListController;
import com.linkscope.core.ui.HistoryListController.Item;
import com.linkscope.core.ui.SendBarController;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Telnet tab: host and port, a plain console, the shared send bar (CRLF by default), interrupt, history. */
public class TelnetController implements ModuleController {
    public static final String ID = "telnet";
    private static final int MAX_CONSOLE_CHARS = 400_000;

    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private Button connectButton;
    @FXML private StatusBadge statusBadge;
    @FXML private CheckBox localEchoCheck;
    @FXML private Button interruptButton;
    @FXML private Button clearConsoleButton;
    @FXML private TextArea consoleArea;
    @FXML private SendBarController sendBarController;
    @FXML private HistoryListController historyController;

    private final TelnetService service = new TelnetService();

    @FXML
    private void initialize() {
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding active = Bindings.createBooleanBinding(
                () -> service.statusProperty().get().isActive(), service.statusProperty());
        hostField.disableProperty().bind(active);
        portField.disableProperty().bind(active);
        BooleanBinding notConnected = service.statusProperty().isNotEqualTo(ModuleStatus.CONNECTED);
        sendBarController.sendDisabledProperty().bind(notConnected);
        interruptButton.disableProperty().bind(notConnected);

        service.setOnOutput(this::appendConsole);
        service.statusProperty().addListener((obs, old, now) -> updateConnectButton(now));
        updateConnectButton(service.statusProperty().get());
        connectButton.setOnAction(e -> toggleConnect());
        hostField.setOnAction(e -> toggleConnect());
        portField.setOnAction(e -> toggleConnect());
        interruptButton.setOnAction(e -> service.interrupt());
        clearConsoleButton.setOnAction(e -> consoleArea.clear());

        sendBarController.applyFields(Map.of("lineEnding", "CRLF"));
        sendBarController.setOnSend(this::send);
        historyController.setTitle("Sent");
        historyController.setOnSelect(item -> {
            if (item.data() instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, String> fields = (Map<String, String>) m;
                sendBarController.applyFields(fields);
                sendBarController.requestFocus();
            }
        });
    }

    private void toggleConnect() {
        if (service.statusProperty().get().isActive()) {
            service.stop();
            return;
        }
        try {
            service.setTarget(Fields.requireText(hostField, "Host"), Fields.port(portField, "Port"));
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
        appendConsole("\n[connecting to " + Fields.text(hostField) + ":" + Fields.text(portField) + "]\n");
        service.start();
    }

    private void updateConnectButton(ModuleStatus s) {
        boolean on = s != null && s.isActive();
        connectButton.setText(on ? "Disconnect" : "Connect");
        connectButton.setGraphic(new FontIcon(on ? "fth-square" : "fth-play"));
    }

    private void send(byte[] data) {
        service.send(data);
        if (localEchoCheck.isSelected()) {
            appendConsole(new String(data, StandardCharsets.UTF_8));
        }
        Map<String, String> fields = sendBarController.captureFields();
        String label = fields.get("payload");
        historyController.add(Item.now(label.length() > 60 ? label.substring(0, 60) + "…" : label, "", fields));
    }

    private void appendConsole(String text) {
        consoleArea.appendText(text);
        if (consoleArea.getLength() > MAX_CONSOLE_CHARS) {
            consoleArea.deleteText(0, consoleArea.getLength() - MAX_CONSOLE_CHARS);
        }
        consoleArea.positionCaret(consoleArea.getLength());
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
        m.put("host", Fields.text(hostField));
        m.put("port", Fields.text(portField));
        m.put("localEcho", Boolean.toString(localEchoCheck.isSelected()));
        m.putAll(sendBarController.captureFields());
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        Fields.apply(fields, "host", hostField);
        Fields.apply(fields, "port", portField);
        Fields.apply(fields, "localEcho", localEchoCheck);
        sendBarController.applyFields(fields);
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
