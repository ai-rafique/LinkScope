package com.linkscope.core.ui;

import com.linkscope.core.PayloadCodec;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Controller for {@code send-bar.fxml}: payload field with ASCII/HEX input mode, line
 * ending selector, Enter-to-send, and Up/Down history. Included by every module tab via
 * {@code <fx:include fx:id="sendBar" source="send-bar.fxml"/>}.
 */
public class SendBarController {
    public static final List<String> LINE_ENDINGS = List.of("None", "LF", "CR", "CRLF");
    private static final int HISTORY_LIMIT = 50;

    @FXML private TextField payloadField;
    @FXML private ToggleButton asciiToggle;
    @FXML private ToggleButton hexToggle;
    @FXML private ComboBox<String> lineEnding;
    @FXML private Button sendButton;

    private final BooleanProperty sendDisabled = new SimpleBooleanProperty(false);
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private Consumer<byte[]> onSend = data -> { };

    @FXML
    private void initialize() {
        ToggleGroup group = new ToggleGroup();
        asciiToggle.setToggleGroup(group);
        hexToggle.setToggleGroup(group);
        asciiToggle.setSelected(true);
        group.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) {
                old.setSelected(true);
            }
        });
        asciiToggle.setTooltip(new Tooltip("Type text. Escapes: \\n \\r \\t \\0 \\\\ \\xNN"));
        hexToggle.setTooltip(new Tooltip("Type hex bytes: \"48 65 6c\", \"48656c\" or \"0x48,0x65\""));

        lineEnding.getItems().setAll(LINE_ENDINGS);
        lineEnding.getSelectionModel().selectFirst();
        lineEnding.setTooltip(new Tooltip("Appended after the payload: LF (\\n), CR (\\r) or CRLF (\\r\\n)"));

        sendButton.disableProperty().bind(sendDisabled);
        sendButton.setOnAction(e -> fire());
        payloadField.setOnAction(e -> fire());
        payloadField.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
    }

    public void setOnSend(Consumer<byte[]> handler) {
        this.onSend = handler == null ? data -> { } : handler;
    }

    /** Bind this to a module's "not connected" state to grey out the Send button. */
    public BooleanProperty sendDisabledProperty() {
        return sendDisabled;
    }

    public boolean isHex() {
        return hexToggle.isSelected();
    }

    public void requestFocus() {
        payloadField.requestFocus();
    }

    /** Parses the current field contents plus the selected line ending. */
    public byte[] currentPayload() {
        byte[] body = PayloadCodec.parse(payloadField.getText() == null ? "" : payloadField.getText(), isHex());
        byte[] tail = lineEndingBytes();
        if (tail.length == 0) {
            return body;
        }
        byte[] out = new byte[body.length + tail.length];
        System.arraycopy(body, 0, out, 0, body.length);
        System.arraycopy(tail, 0, out, body.length, tail.length);
        return out;
    }

    public Map<String, String> captureFields() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("payload", payloadField.getText() == null ? "" : payloadField.getText());
        m.put("payloadMode", isHex() ? "hex" : "ascii");
        m.put("lineEnding", lineEnding.getValue() == null ? "None" : lineEnding.getValue());
        return m;
    }

    public void applyFields(Map<String, String> fields) {
        if (fields.containsKey("payload")) {
            payloadField.setText(fields.get("payload"));
        }
        if (fields.containsKey("payloadMode")) {
            boolean hex = "hex".equalsIgnoreCase(fields.get("payloadMode"));
            (hex ? hexToggle : asciiToggle).setSelected(true);
        }
        String le = fields.get("lineEnding");
        if (le != null && LINE_ENDINGS.contains(le)) {
            lineEnding.setValue(le);
        }
    }

    private void fire() {
        if (sendDisabled.get()) {
            return;
        }
        byte[] data;
        try {
            data = currentPayload();
        } catch (IllegalArgumentException ex) {
            Toasts.error("Payload: " + ex.getMessage());
            return;
        }
        remember(payloadField.getText());
        onSend.accept(data);
        payloadField.selectAll();
    }

    private byte[] lineEndingBytes() {
        String le = lineEnding.getValue();
        if (le == null) {
            return new byte[0];
        }
        return switch (le) {
            case "LF" -> new byte[] {'\n'};
            case "CR" -> new byte[] {'\r'};
            case "CRLF" -> new byte[] {'\r', '\n'};
            default -> new byte[0];
        };
    }

    private void remember(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        history.remove(text);
        history.add(text);
        while (history.size() > HISTORY_LIMIT) {
            history.remove(0);
        }
        historyIndex = history.size();
    }

    private void onKeyPressed(KeyEvent e) {
        if (history.isEmpty()) {
            return;
        }
        if (e.getCode() == KeyCode.UP) {
            historyIndex = Math.max(0, historyIndex - 1);
            payloadField.setText(history.get(historyIndex));
            payloadField.end();
            e.consume();
        } else if (e.getCode() == KeyCode.DOWN) {
            historyIndex = Math.min(history.size(), historyIndex + 1);
            payloadField.setText(historyIndex == history.size() ? "" : history.get(historyIndex));
            payloadField.end();
            e.consume();
        }
    }
}
