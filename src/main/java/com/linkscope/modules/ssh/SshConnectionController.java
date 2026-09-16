package com.linkscope.modules.ssh;

import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller for {@code ssh-connection.fxml}: host, port, user, password or key auth,
 * strict host key, Connect. Shared by the SSH, Tunnels and SFTP tabs. A tab may allow
 * "Use the SSH tab's connection", in which case {@link #activeService()} follows the
 * primary session from {@link SshSessions} instead of this tab's own service.
 */
public class SshConnectionController {

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
    @FXML private CheckBox sharedCheck;
    @FXML private Button connectButton;
    @FXML private StatusBadge statusBadge;

    private SshService own;
    private final ObjectProperty<SshService> active = new SimpleObjectProperty<>();
    private final ChangeListener<ModuleStatus> buttonUpdater = (obs, old, now) -> updateConnectButton(now);

    @FXML
    private void initialize() {
        ToggleGroup auth = new ToggleGroup();
        passwordAuthToggle.setToggleGroup(auth);
        keyAuthToggle.setToggleGroup(auth);
        passwordAuthToggle.setSelected(true);
        auth.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) {
                old.setSelected(true);
            }
            showAuth();
        });
        showAuth();
        browseKeyButton.setOnAction(e -> browseKey());
        connectButton.setOnAction(e -> toggleConnect());
        sharedCheck.setVisible(false);
        sharedCheck.setManaged(false);
    }

    /**
     * Wires the panel to this tab's own service. When {@code allowShared} is true the
     * "Use the SSH tab's connection" switch appears and, when ticked, the active service
     * becomes the primary one.
     */
    public void bind(SshService ownService, boolean allowShared) {
        this.own = ownService;
        sharedCheck.setVisible(allowShared);
        sharedCheck.setManaged(allowShared);
        sharedCheck.selectedProperty().addListener((obs, old, now) -> rebindActive());
        SshSessions.primaryProperty().addListener((obs, old, now) -> rebindActive());
        rebindActive();

        editable = Bindings.createBooleanBinding(() -> {
            SshService s = active.get();
            return !sharedCheck.isSelected() && (s == null || !s.statusProperty().get().isActive());
        }, sharedCheck.selectedProperty(), active);
        List<Node> fields = List.of(hostField, portField, userField, passwordAuthToggle, keyAuthToggle, passwordField,
                keyPathField, browseKeyButton, passphraseField, strictHostKeyCheck);
        for (Node n : fields) {
            n.disableProperty().bind(editable.not());
        }
        // Status changes of whichever service is active must re-evaluate the binding above.
        active.addListener((obs, old, now) -> {
            if (old != null) {
                old.statusProperty().removeListener(invalidator);
            }
            if (now != null) {
                now.statusProperty().addListener(invalidator);
            }
            editable.invalidate();
        });
        if (active.get() != null) {
            active.get().statusProperty().addListener(invalidator);
        }
        connectButton.disableProperty().bind(sharedCheck.selectedProperty());
    }

    private BooleanBinding editable;
    private final ChangeListener<ModuleStatus> invalidator = (obs, old, now) -> {
        if (editable != null) {
            editable.invalidate();
        }
    };

    private void rebindActive() {
        SshService next = sharedCheck.isSelected() && SshSessions.primary() != null ? SshSessions.primary() : own;
        SshService prev = active.get();
        if (prev == next) {
            return;
        }
        if (prev != null) {
            prev.statusProperty().removeListener(buttonUpdater);
        }
        active.set(next);
        statusBadge.statusProperty().unbind();
        if (next != null) {
            statusBadge.statusProperty().bind(next.statusProperty());
            next.statusProperty().addListener(buttonUpdater);
            updateConnectButton(next.statusProperty().get());
        }
    }

    /** The service this tab should act on: its own, or the SSH tab's when shared. */
    public ReadOnlyObjectProperty<SshService> activeService() {
        return active;
    }

    public boolean isShared() {
        return sharedCheck.isSelected();
    }

    /** Pushes the form into the own service; throws IllegalArgumentException with a user-readable message. */
    public void configure(SshService service) {
        service.setTarget(Fields.requireText(hostField, "Host"), Fields.port(portField, "Port"), Fields.requireText(userField, "User"));
        if (keyAuthToggle.isSelected()) {
            service.setPrivateKey(Fields.requireText(keyPathField, "Private key path"), passphraseField.getText());
        } else {
            service.setPassword(passwordField.getText());
        }
        service.setStrictHostKey(strictHostKeyCheck.isSelected());
    }

    private void toggleConnect() {
        SshService s = active.get();
        if (s == null) {
            return;
        }
        if (s.statusProperty().get().isActive()) {
            s.stop();
            return;
        }
        try {
            configure(s);
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
        s.start();
    }

    private void updateConnectButton(ModuleStatus st) {
        boolean on = st != null && st.isActive();
        connectButton.setText(on ? "Disconnect" : "Connect");
        connectButton.setGraphic(new FontIcon(on ? "fth-square" : "fth-play"));
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

    // --- presets --------------------------------------------------------------------

    public Map<String, String> captureFields() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("host", Fields.text(hostField));
        m.put("port", Fields.text(portField));
        m.put("user", Fields.text(userField));
        m.put("auth", keyAuthToggle.isSelected() ? "key" : "password");
        m.put("keyPath", Fields.text(keyPathField));
        m.put("strictHostKey", Boolean.toString(strictHostKeyCheck.isSelected()));
        m.put("shared", Boolean.toString(sharedCheck.isSelected()));
        String pw = passwordField.getText() == null ? "" : passwordField.getText();
        String pp = passphraseField.getText() == null ? "" : passphraseField.getText();
        m.put("password", pw.trim().startsWith("${") ? pw : "");
        m.put("passphrase", pp.trim().startsWith("${") ? pp : "");
        if ((!pw.isEmpty() && !pw.trim().startsWith("${")) || (!pp.isEmpty() && !pp.trim().startsWith("${"))) {
            Toasts.warning("Password / passphrase not saved in the preset — use a ${VAR} placeholder from .env to store one");
        }
        return m;
    }

    public void applyFields(Map<String, String> fields) {
        Fields.apply(fields, "host", hostField);
        Fields.apply(fields, "port", portField);
        Fields.apply(fields, "user", userField);
        if (fields.containsKey("auth")) {
            ("key".equals(fields.get("auth")) ? keyAuthToggle : passwordAuthToggle).setSelected(true);
        }
        Fields.apply(fields, "keyPath", keyPathField);
        Fields.apply(fields, "strictHostKey", strictHostKeyCheck);
        if (sharedCheck.isVisible()) {
            Fields.apply(fields, "shared", sharedCheck);
        }
        if (fields.get("password") != null && !fields.get("password").isEmpty()) {
            passwordField.setText(fields.get("password"));
        }
        if (fields.get("passphrase") != null && !fields.get("passphrase").isEmpty()) {
            passphraseField.setText(fields.get("passphrase"));
        }
    }
}
