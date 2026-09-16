package com.linkscope.modules.tunnels;

import com.jcraft.jsch.JSchException;
import com.linkscope.core.FxThread;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.ssh.SshConnectionController;
import com.linkscope.modules.ssh.SshService;
import com.linkscope.modules.ssh.SshService.Forward;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Tunnels tab: a table of SSH port forwards (local -L and remote -R) started and stopped
 * individually on this tab's own SSH login or on the SSH tab's shared connection.
 * Forwards marked "wanted" are re-established automatically after a reconnect.
 */
public class TunnelsController implements ModuleController {
    public static final String ID = "tunnels";
    private static final String LOCAL = "Local (-L)";
    private static final String REMOTE = "Remote (-R)";

    /** Table row: the forward plus its live state. */
    public static final class Row {
        final Forward forward;
        final SimpleBooleanProperty active = new SimpleBooleanProperty(false);
        final SimpleStringProperty status = new SimpleStringProperty("stopped");
        boolean wanted;

        Row(Forward forward) {
            this.forward = forward;
        }
    }

    @FXML private SshConnectionController connectionController;
    @FXML private ComboBox<String> typeCombo;
    @FXML private TextField bindAddressField;
    @FXML private TextField bindPortField;
    @FXML private TextField targetHostField;
    @FXML private TextField targetPortField;
    @FXML private Button addButton;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private Button removeButton;
    @FXML private Button startAllButton;
    @FXML private Button stopAllButton;
    @FXML private Label summaryLabel;
    @FXML private TableView<Row> table;
    @FXML private TableColumn<Row, String> activeColumn;
    @FXML private TableColumn<Row, String> typeColumn;
    @FXML private TableColumn<Row, String> bindColumn;
    @FXML private TableColumn<Row, String> targetColumn;
    @FXML private TableColumn<Row, String> statusColumn;

    private final SshService own = new SshService();
    private SshService attached;
    private final ChangeListener<ModuleStatus> connectionWatcher = (obs, old, now) -> onConnectionChanged(now);

    @FXML
    private void initialize() {
        own.setOpenShell(false);
        connectionController.bind(own, true);
        connectionController.activeService().addListener((obs, old, now) -> attach(now));
        attach(connectionController.activeService().get());

        typeCombo.getItems().setAll(LOCAL, REMOTE);
        typeCombo.setValue(LOCAL);
        typeCombo.valueProperty().addListener((obs, old, now) -> updatePrompts());
        updatePrompts();

        activeColumn.setCellValueFactory(c -> Bindings.createStringBinding(
                () -> c.getValue().active.get() ? "● on" : "○ off", c.getValue().active));
        typeColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().forward.local() ? "Local" : "Remote"));
        bindColumn.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().forward.local() ? "this PC " : "server ") + c.getValue().forward.bindAddress() + ":" + c.getValue().forward.bindPort()));
        targetColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().forward.targetHost() + ":" + c.getValue().forward.targetPort()
                        + (c.getValue().forward.local() ? " (as seen from the server)" : " (as seen from this PC)")));
        statusColumn.setCellValueFactory(c -> c.getValue().status);
        table.setPlaceholder(new Label("No forwards yet. Add one above: local makes a remote service reachable on this PC; remote does the reverse."));

        BooleanBinding noSelection = table.getSelectionModel().selectedItemProperty().isNull();
        startButton.disableProperty().bind(noSelection);
        stopButton.disableProperty().bind(noSelection);
        removeButton.disableProperty().bind(noSelection);
        addButton.setOnAction(e -> add());
        for (TextField f : List.of(bindPortField, targetHostField, targetPortField, bindAddressField)) {
            f.setOnAction(e -> add());
        }
        startButton.setOnAction(e -> start(table.getSelectionModel().getSelectedItem()));
        stopButton.setOnAction(e -> stop(table.getSelectionModel().getSelectedItem(), true));
        removeButton.setOnAction(e -> {
            Row r = table.getSelectionModel().getSelectedItem();
            if (r != null) {
                stop(r, true);
                table.getItems().remove(r);
                updateSummary();
            }
        });
        startAllButton.setOnAction(e -> table.getItems().forEach(this::start));
        stopAllButton.setOnAction(e -> table.getItems().forEach(r -> stop(r, true)));
        updateSummary();
    }

    private void updatePrompts() {
        boolean local = LOCAL.equals(typeCombo.getValue());
        bindAddressField.setPromptText(local ? "127.0.0.1" : "127.0.0.1 (server side)");
        bindPortField.setPromptText(local ? "port on this PC" : "port on the server");
        targetHostField.setPromptText(local ? "host as seen from the server" : "host as seen from this PC");
        targetPortField.setPromptText("port");
        bindAddressField.setTooltip(new Tooltip(local
                ? "Interface to listen on here. 127.0.0.1 keeps it private; 0.0.0.0 lets other machines use the tunnel."
                : "Address the server binds. Anything but loopback needs GatewayPorts enabled in sshd_config."));
    }

    // --- connection handling ----------------------------------------------------------

    private void attach(SshService service) {
        if (attached == service) {
            return;
        }
        if (attached != null) {
            attached.statusProperty().removeListener(connectionWatcher);
            for (Row r : table.getItems()) {
                markStopped(r, "connection switched");
            }
        }
        attached = service;
        if (service != null) {
            service.statusProperty().addListener(connectionWatcher);
            onConnectionChanged(service.statusProperty().get());
        }
    }

    private void onConnectionChanged(ModuleStatus status) {
        if (status == ModuleStatus.CONNECTED) {
            for (Row r : table.getItems()) {
                if (r.wanted && !r.active.get()) {
                    start(r);
                }
            }
        } else if (status != ModuleStatus.CONNECTING) {
            for (Row r : table.getItems()) {
                if (r.active.get()) {
                    markStopped(r, "connection closed" + (r.wanted ? " · restarts on reconnect" : ""));
                }
            }
        }
        updateSummary();
    }

    // --- forwards ---------------------------------------------------------------------

    private void add() {
        Forward f;
        try {
            boolean local = LOCAL.equals(typeCombo.getValue());
            String bind = Fields.text(bindAddressField);
            f = new Forward(local, bind.isEmpty() ? "127.0.0.1" : bind,
                    Fields.port(bindPortField, local ? "Local port" : "Server port"),
                    Fields.requireText(targetHostField, "Target host"),
                    Fields.port(targetPortField, "Target port"));
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
        for (Row r : table.getItems()) {
            if (r.forward.equals(f)) {
                Toasts.warning("That forward is already in the list");
                return;
            }
        }
        Row row = new Row(f);
        table.getItems().add(row);
        table.getSelectionModel().select(row);
        updateSummary();
        if (attached != null && attached.statusProperty().get() == ModuleStatus.CONNECTED) {
            start(row);
        } else {
            row.wanted = true;
            row.status.set("waiting for connection");
        }
    }

    private void start(Row r) {
        if (r == null) {
            return;
        }
        r.wanted = true;
        SshService s = attached;
        if (s == null || s.statusProperty().get() != ModuleStatus.CONNECTED) {
            r.status.set("waiting for connection");
            Toasts.info("Connect first; the forward starts as soon as the session is up");
            return;
        }
        if (r.active.get()) {
            return;
        }
        r.status.set("starting…");
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            try {
                s.addForward(r.forward);
                FxThread.run(() -> {
                    r.active.set(true);
                    r.status.set(r.forward.local()
                            ? "listening on " + r.forward.bindAddress() + ":" + r.forward.bindPort()
                            : "server listening on " + r.forward.bindAddress() + ":" + r.forward.bindPort());
                    updateSummary();
                });
            } catch (JSchException e) {
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                LogSink.get().error(SshService.TAG, "Forward " + r.forward.describe() + " failed: " + why);
                FxThread.run(() -> {
                    r.active.set(false);
                    r.status.set("failed: " + why);
                    updateSummary();
                });
            }
        });
    }

    private void stop(Row r, boolean clearWanted) {
        if (r == null) {
            return;
        }
        if (clearWanted) {
            r.wanted = false;
        }
        SshService s = attached;
        if (r.active.get() && s != null) {
            Executors.newVirtualThreadPerTaskExecutor().submit(() -> s.removeForward(r.forward));
        }
        markStopped(r, "stopped");
    }

    private void markStopped(Row r, String why) {
        r.active.set(false);
        r.status.set(why);
        updateSummary();
    }

    private void updateSummary() {
        long on = table.getItems().stream().filter(r -> r.active.get()).count();
        summaryLabel.setText(table.getItems().isEmpty() ? "" : on + " of " + table.getItems().size() + " active");
    }

    // --- ModuleController -----------------------------------------------------------

    @Override
    public String moduleId() {
        return ID;
    }

    @Override
    public ReadOnlyObjectProperty<ModuleStatus> statusProperty() {
        return own.statusProperty();
    }

    @Override
    public Map<String, String> captureFields() {
        Map<String, String> m = new LinkedHashMap<>(connectionController.captureFields());
        List<String> specs = new ArrayList<>();
        for (Row r : table.getItems()) {
            specs.add(r.forward.toSpec());
        }
        m.put("forwards", String.join("; ", specs));
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        connectionController.applyFields(fields);
        String specs = fields.get("forwards");
        if (specs != null) {
            table.getItems().forEach(r -> stop(r, true));
            table.getItems().clear();
            for (String spec : specs.split(";")) {
                if (!spec.isBlank()) {
                    try {
                        Row row = new Row(Forward.parse(spec));
                        row.wanted = true;
                        row.status.set("waiting for connection");
                        table.getItems().add(row);
                    } catch (IllegalArgumentException ex) {
                        Toasts.warning("Skipped forward in preset: " + ex.getMessage());
                    }
                }
            }
            updateSummary();
            if (attached != null && attached.statusProperty().get() == ModuleStatus.CONNECTED) {
                table.getItems().forEach(this::start);
            }
        }
    }

    @Override
    public void shutdown() {
        own.stop();
    }
}
