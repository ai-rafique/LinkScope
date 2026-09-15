package com.linkscope.modules.websocket;

import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.HistoryListController;
import com.linkscope.core.ui.HistoryListController.Item;
import com.linkscope.core.ui.SendBarController;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.http.HttpRequestSpec;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** WebSocket tester tab: connection with headers, text/binary frames, ping, shared history. */
public class WebSocketController implements ModuleController {
    public static final String ID = "websocket";

    @FXML private TextField urlField;
    @FXML private Button connectButton;
    @FXML private StatusBadge statusBadge;
    @FXML private TextArea headersArea;
    @FXML private ToggleButton textFrameToggle;
    @FXML private ToggleButton binaryFrameToggle;
    @FXML private Button pingButton;
    @FXML private Label pingLabel;
    @FXML private SendBarController sendBarController;
    @FXML private HistoryListController historyController;

    private final WebSocketService service = new WebSocketService();

    @FXML
    private void initialize() {
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding active = Bindings.createBooleanBinding(
                () -> service.statusProperty().get().isActive(), service.statusProperty());
        urlField.disableProperty().bind(active);
        headersArea.disableProperty().bind(active);
        BooleanBinding notConnected = service.statusProperty().isNotEqualTo(ModuleStatus.CONNECTED);
        sendBarController.sendDisabledProperty().bind(notConnected);
        pingButton.disableProperty().bind(notConnected);

        ToggleGroup frames = new ToggleGroup();
        textFrameToggle.setToggleGroup(frames);
        binaryFrameToggle.setToggleGroup(frames);
        textFrameToggle.setSelected(true);
        frames.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) {
                old.setSelected(true);
            }
        });

        urlField.setOnAction(e -> toggleConnect());
        connectButton.setOnAction(e -> toggleConnect());
        pingButton.setOnAction(e -> service.ping());
        service.lastPingMsProperty().addListener((obs, old, now) ->
                pingLabel.setText(now.longValue() < 0 ? "" : "RTT " + now + " ms"));
        service.statusProperty().addListener((obs, old, now) -> updateConnectButton(now));
        updateConnectButton(service.statusProperty().get());
        sendBarController.setOnSend(this::sendFrame);

        historyController.setTitle("History");
        historyController.setOnSelect(this::restore);
    }

    private void toggleConnect() {
        if (service.statusProperty().get().isActive()) {
            service.stop();
            return;
        }
        String url;
        try {
            url = Fields.requireText(urlField, "URL");
            service.setUrl(url);
            service.setHeaders(HttpRequestSpec.parseHeaders(headersArea.getText()));
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
        historyController.add(Item.now("CONNECT " + url, "", Map.of("url", url)));
        service.start();
    }

    private void sendFrame(byte[] data) {
        boolean binary = binaryFrameToggle.isSelected();
        if (binary) {
            service.sendBinary(data);
        } else {
            service.sendText(data);
        }
        String preview = binary ? com.linkscope.core.PayloadCodec.toHex(data)
                : new String(data, StandardCharsets.UTF_8);
        if (preview.length() > 80) {
            preview = preview.substring(0, 80) + "…";
        }
        historyController.add(Item.now((binary ? "BINARY " : "TEXT ") + preview, data.length + " B",
                Map.of("payload", sendBarController.captureFields().get("payload"),
                        "payloadMode", sendBarController.captureFields().get("payloadMode"),
                        "frame", binary ? "binary" : "text")));
    }

    private void restore(Item item) {
        if (!(item.data() instanceof Map<?, ?> data)) {
            return;
        }
        Object url = data.get("url");
        if (url != null) {
            urlField.setText(url.toString());
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) data;
        sendBarController.applyFields(fields);
        ("binary".equals(fields.get("frame")) ? binaryFrameToggle : textFrameToggle).setSelected(true);
    }

    private void updateConnectButton(ModuleStatus s) {
        boolean active = s != null && s.isActive();
        connectButton.setText(active ? "Disconnect" : "Connect");
        connectButton.setGraphic(new FontIcon(active ? "fth-square" : "fth-play"));
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
        m.put("url", Fields.text(urlField));
        m.put("headers", headersArea.getText() == null ? "" : headersArea.getText());
        m.put("frame", binaryFrameToggle.isSelected() ? "binary" : "text");
        m.putAll(sendBarController.captureFields());
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        Fields.apply(fields, "url", urlField);
        if (fields.containsKey("headers")) {
            headersArea.setText(fields.get("headers"));
        }
        if (fields.containsKey("frame")) {
            ("binary".equals(fields.get("frame")) ? binaryFrameToggle : textFrameToggle).setSelected(true);
        }
        sendBarController.applyFields(fields);
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
