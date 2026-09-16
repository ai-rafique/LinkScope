package com.linkscope.modules.ssh;

import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.HistoryListController;
import com.linkscope.core.ui.HistoryListController.Item;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.ssh.SshService.ExecResult;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SSH tab: connection with password or key auth, a plain console for the shell, one-shot exec, history. */
public class SshController implements ModuleController {
    public static final String ID = "ssh";
    private static final int MAX_CONSOLE_CHARS = 400_000;

    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private TextField userField;
    @FXML private ToggleButton passwordAuthToggle;
    @FXML private ToggleButton keyAuthToggle;
    @FXML private HBox passwordBox;
    @FXML private PasswordField passwordField;
    @FXML private HBox keyBox;
    @FXML private TextField keyPathField;
    @FXML private Button browseKeyButton;
    @FXML private PasswordField passphraseField;
    @FXML private CheckBox strictHostKeyCheck;
    @FXML private Button connectButton;
    @FXML private StatusBadge statusBadge;
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
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding active = Bindings.createBooleanBinding(
                () -> service.statusProperty().get().isActive(), service.statusProperty());
        for (var n : List.of(hostField, portField, userField, passwordAuthToggle, keyAuthToggle, passwordField,
                keyPathField, browseKeyButton, passphraseField, strictHostKeyCheck)) {
            n.disableProperty().bind(active);
        }
        BooleanBinding notConnected = service.statusProperty().isNotEqualTo(ModuleStatus.CONNECTED);
        sendButton.disableProperty().bind(notConnected);
        ctrlCButton.disableProperty().bind(notConnected);

        ToggleGroup auth = new ToggleGroup();
        passwordAuthToggle.setToggleGroup(auth);
        keyAuthToggle.setToggleGroup(auth);
        passwordAuthToggle.setSelected(true);
        keepOne(auth);
        auth.selectedToggleProperty().addListener((obs, old, now) -> showAuth());
        showAuth();

        ToggleGroup mode = new ToggleGroup();
        shellModeToggle.setToggleGroup(mode);
        execModeToggle.setToggleGroup(mode);
        shellModeToggle.setSelected(true);
        keepOne(mode);
        mode.selectedToggleProperty().addListener((obs, old, now) -> commandField.setPromptText(
                execModeToggle.isSelected() ? "Command to run on its own channel (output and exit code below)"
                        : "Type a line for the interactive shell, Enter to send, Up/Down for history"));

        browseKeyButton.setOnAction(e -> browseKey());
        connectButton.setOnAction(e -> toggleConnect());
        service.statusProperty().addListener((obs, old, now) -> updateConnectButton(now));
        updateConnectButton(service.statusProperty().get());
        service.setOnOutput(this::appendConsole);

        commandField.setOnAction(e -> submit());
        commandField.addEventFilter(KeyEvent.KEY_PRESSED, this::onCommandKey);
        sendButton.setOnAction(e -> submit());
        ctrlCButton.setOnAction(e -> service.send(new byte[] {0x03}));
        clearConsoleButton.setOnAction(e -> consoleArea.clear());
        consoleArea.setText("");

        historyController.setTitle("Commands");
        historyController.setOnSelect(this::restore);
    }

    private static void keepOne(ToggleGroup group) {
        group.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) {
                old.setSelected(true);
            }
        });
    }

    private void showAuth() {
        boolean key = keyAuthToggle.isSelected();
        passwordBox.setVisible(!key);
        passwordBox.setManaged(!key);
        keyBox.setVisible(key);
        keyBox.setManaged(key);
    }

    private void browseKey() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Private key");
        File sshDir = new File(System.getProperty("user.home"), ".ssh");
        if (sshDir.isDirectory()) {
            chooser.setInitialDirectory(sshDir);
        }
        File f = chooser.showOpenDialog(hostField.getScene().getWindow());
        if (f != null) {
            keyPathField.setText(f.getAbsolutePath());
        }
    }

    private void toggleConnect() {
        if (service.statusProperty().get().isActive()) {
            service.stop();
            return;
        }
        try {
            service.setTarget(Fields.requireText(hostField, "Host"), Fields.port(portField, "Port"), Fields.requireText(userField, "User"));
            if (keyAuthToggle.isSelected()) {
                service.setPrivateKey(Fields.requireText(keyPathField, "Private key path"), passphraseField.getText());
            } else {
                service.setPassword(passwordField.getText());
            }
            service.setStrictHostKey(strictHostKeyCheck.isSelected());
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
        appendConsole("\n[connecting to " + service.target() + "]\n");
        service.start();
    }

    private void updateConnectButton(ModuleStatus s) {
        boolean active = s != null && s.isActive();
        connectButton.setText(active ? "Disconnect" : "Connect");
        connectButton.setGraphic(new FontIcon(active ? "fth-square" : "fth-play"));
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
        Map<String, String> m = new LinkedHashMap<>();
        m.put("host", Fields.text(hostField));
        m.put("port", Fields.text(portField));
        m.put("user", Fields.text(userField));
        m.put("auth", keyAuthToggle.isSelected() ? "key" : "password");
        m.put("keyPath", Fields.text(keyPathField));
        m.put("strictHostKey", Boolean.toString(strictHostKeyCheck.isSelected()));
        m.put("mode", execModeToggle.isSelected() ? "exec" : "shell");
        // Secrets: saved only when they are ${VAR} placeholders resolved from .env at load time.
        String pw = passwordField.getText() == null ? "" : passwordField.getText();
        String pp = passphraseField.getText() == null ? "" : passphraseField.getText();
        m.put("password", pw.trim().startsWith("${") ? pw : "");
        m.put("passphrase", pp.trim().startsWith("${") ? pp : "");
        if ((!pw.isEmpty() && !pw.trim().startsWith("${")) || (!pp.isEmpty() && !pp.trim().startsWith("${"))) {
            Toasts.warning("Password / passphrase not saved in the preset — use a ${VAR} placeholder from .env to store one");
        }
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        Fields.apply(fields, "host", hostField);
        Fields.apply(fields, "port", portField);
        Fields.apply(fields, "user", userField);
        if (fields.containsKey("auth")) {
            ("key".equals(fields.get("auth")) ? keyAuthToggle : passwordAuthToggle).setSelected(true);
        }
        Fields.apply(fields, "keyPath", keyPathField);
        Fields.apply(fields, "strictHostKey", strictHostKeyCheck);
        if (fields.containsKey("mode")) {
            ("exec".equals(fields.get("mode")) ? execModeToggle : shellModeToggle).setSelected(true);
        }
        if (fields.get("password") != null && !fields.get("password").isEmpty()) {
            passwordField.setText(fields.get("password"));
        }
        if (fields.get("passphrase") != null && !fields.get("passphrase").isEmpty()) {
            passphraseField.setText(fields.get("passphrase"));
        }
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
