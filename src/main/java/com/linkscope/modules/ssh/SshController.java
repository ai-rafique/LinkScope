package com.linkscope.modules.ssh;

import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.HistoryListController;
import com.linkscope.core.ui.HistoryListController.Item;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.ssh.SshService.ExecResult;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SSH tab: the shared connection panel, a plain console for the shell, one-shot exec, history. */
public class SshController implements ModuleController {
    public static final String ID = "ssh";
    private static final int MAX_CONSOLE_CHARS = 400_000;

    @FXML private SshConnectionController connectionController;
    @FXML private TextArea consoleArea;
    @FXML private ToggleButton shellModeToggle;
    @FXML private ToggleButton execModeToggle;
    @FXML private TextField commandField;
    @FXML private Button sendButton;
    @FXML private Button ctrlCButton;
    @FXML private Button clearConsoleButton;
    @FXML private HistoryListController historyController;

    private final SshService service = new SshService();
    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex;

    @FXML
    private void initialize() {
        SshSessions.setPrimary(service);
        connectionController.bind(service, false);

        BooleanBinding notConnected = service.statusProperty().isNotEqualTo(ModuleStatus.CONNECTED);
        sendButton.disableProperty().bind(notConnected);
        ctrlCButton.disableProperty().bind(notConnected);

        ToggleGroup mode = new ToggleGroup();
        shellModeToggle.setToggleGroup(mode);
        execModeToggle.setToggleGroup(mode);
        shellModeToggle.setSelected(true);
        mode.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) {
                old.setSelected(true);
            }
            commandField.setPromptText(execModeToggle.isSelected()
                    ? "Command to run on its own channel (output and exit code below)"
                    : "Type a line for the interactive shell, Enter to send, Up/Down for history");
        });

        service.setOnOutput(this::appendConsole);
        service.statusProperty().addListener((obs, old, now) -> {
            if (now == ModuleStatus.CONNECTING) {
                appendConsole("\n[connecting to " + service.target() + "]\n");
            } else if (now == ModuleStatus.DISCONNECTED && old != null && old != ModuleStatus.DISCONNECTED) {
                appendConsole("\n[disconnected]\n");
            }
        });

        commandField.setOnAction(e -> submit());
        commandField.addEventFilter(KeyEvent.KEY_PRESSED, this::onCommandKey);
        sendButton.setOnAction(e -> submit());
        ctrlCButton.setOnAction(e -> service.send(new byte[] {0x03}));
        clearConsoleButton.setOnAction(e -> consoleArea.clear());

        historyController.setTitle("Commands");
        historyController.setOnSelect(this::restore);
    }

    // --- console ----------------------------------------------------------------------

    private void appendConsole(String text) {
        consoleArea.appendText(text);
        if (consoleArea.getLength() > MAX_CONSOLE_CHARS) {
            consoleArea.deleteText(0, consoleArea.getLength() - MAX_CONSOLE_CHARS);
        }
        consoleArea.positionCaret(consoleArea.getLength());
    }

    private void submit() {
        String command = commandField.getText() == null ? "" : commandField.getText();
        if (service.statusProperty().get() != ModuleStatus.CONNECTED) {
            Toasts.warning("Connect first");
            return;
        }
        remember(command);
        if (execModeToggle.isSelected()) {
            if (command.isBlank()) {
                Toasts.warning("Type a command to run");
                return;
            }
            appendConsole("\n$ " + command + "\n");
            service.exec(command, this::showExecResult);
        } else {
            service.sendLine(command);
        }
        historyController.add(Item.now((execModeToggle.isSelected() ? "exec " : "shell ") + command, "", command));
        commandField.clear();
    }

    private void showExecResult(ExecResult r) {
        appendConsole(r.output() + (r.output().endsWith("\n") || r.output().isEmpty() ? "" : "\n")
                + "[exit " + r.exitStatus() + " · " + r.durationMs() + " ms]\n");
        if (r.exitStatus() != 0) {
            LogSink.get().info(SshService.TAG, "\"" + r.command() + "\" exited with status " + r.exitStatus());
        }
    }

    private void remember(String command) {
        if (command.isBlank()) {
            return;
        }
        commandHistory.remove(command);
        commandHistory.add(command);
        while (commandHistory.size() > 100) {
            commandHistory.remove(0);
        }
        historyIndex = commandHistory.size();
    }

    private void onCommandKey(KeyEvent e) {
        if (commandHistory.isEmpty()) {
            return;
        }
        if (e.getCode() == KeyCode.UP) {
            historyIndex = Math.max(0, historyIndex - 1);
            commandField.setText(commandHistory.get(historyIndex));
            commandField.end();
            e.consume();
        } else if (e.getCode() == KeyCode.DOWN) {
            historyIndex = Math.min(commandHistory.size(), historyIndex + 1);
            commandField.setText(historyIndex == commandHistory.size() ? "" : commandHistory.get(historyIndex));
            commandField.end();
            e.consume();
        }
    }

    private void restore(Item item) {
        if (item.data() instanceof String command) {
            commandField.setText(command);
            commandField.requestFocus();
            commandField.end();
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
    public Map<String, String> captureFields() {
        Map<String, String> m = new LinkedHashMap<>(connectionController.captureFields());
        m.put("mode", execModeToggle.isSelected() ? "exec" : "shell");
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        connectionController.applyFields(fields);
        if (fields.containsKey("mode")) {
            ("exec".equals(fields.get("mode")) ? execModeToggle : shellModeToggle).setSelected(true);
        }
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
