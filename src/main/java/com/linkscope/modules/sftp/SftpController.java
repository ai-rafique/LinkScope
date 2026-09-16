package com.linkscope.modules.sftp;

import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.sftp.SftpClient.Entry;
import com.linkscope.modules.sftp.SftpClient.Progress;
import com.linkscope.modules.ssh.SshConnectionController;
import com.linkscope.modules.ssh.SshService;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SFTP tab: remote file browser with transfers, on the shared SSH connection or its own. */
public class SftpController implements ModuleController {
    public static final String ID = "sftp";

    @FXML private SshConnectionController connectionController;
    @FXML private TextField pathField;
    @FXML private Button goButton;
    @FXML private Button upButton;
    @FXML private Button homeButton;
    @FXML private Button refreshButton;
    @FXML private TableView<Entry> table;
    @FXML private TableColumn<Entry, String> nameColumn;
    @FXML private TableColumn<Entry, String> sizeColumn;
    @FXML private TableColumn<Entry, String> modifiedColumn;
    @FXML private TableColumn<Entry, String> permissionsColumn;
    @FXML private Button downloadButton;
    @FXML private Button uploadButton;
    @FXML private TextField nameField;
    @FXML private Button mkdirButton;
    @FXML private Button renameButton;
    @FXML private Button deleteButton;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private Button cancelButton;
    @FXML private Label statusLabel;

    private final SshService own = new SshService();
    private final SftpClient client = new SftpClient(() -> connectionController.activeService().get());
    private final ReadOnlyObjectWrapper<ModuleStatus> status = new ReadOnlyObjectWrapper<>(ModuleStatus.DISCONNECTED);
    private final ChangeListener<ModuleStatus> connectionWatcher = (obs, old, now) -> onConnectionChanged(now);
    private SshService attached;
    private String currentPath = "";
    private boolean deleteArmed;

    @FXML
    private void initialize() {
        own.setOpenShell(false);
        connectionController.bind(own, true);
        connectionController.activeService().addListener((obs, old, now) -> attach(now));
        attach(connectionController.activeService().get());

        nameColumn.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().directory() ? "▸ " : "   ") + c.getValue().name() + (c.getValue().link() ? " →" : "")));
        sizeColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sizeText()));
        modifiedColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().modifiedText()));
        permissionsColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().permissions()));
        table.setPlaceholder(new Label("Connect, then browse. Double-click a folder to open it."));
        table.setOnMouseClicked(e -> {
            Entry sel = table.getSelectionModel().getSelectedItem();
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && sel != null && sel.directory()) {
                list(SftpClient.join(currentPath, sel.name()));
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (now != null) {
                nameField.setText(now.name());
            }
            disarmDelete();
        });

        BooleanBinding notConnected = status.isNotEqualTo(ModuleStatus.CONNECTED);
        for (var n : List.of(pathField, goButton, upButton, homeButton, refreshButton, uploadButton, mkdirButton)) {
            n.disableProperty().bind(notConnected);
        }
        BooleanBinding noSelection = table.getSelectionModel().selectedItemProperty().isNull();
        downloadButton.disableProperty().bind(notConnected.or(noSelection));
        renameButton.disableProperty().bind(notConnected.or(noSelection));
        deleteButton.disableProperty().bind(notConnected.or(noSelection));

        pathField.setOnAction(e -> list(Fields.text(pathField)));
        goButton.setOnAction(e -> list(Fields.text(pathField)));
        upButton.setOnAction(e -> list(SftpClient.parent(currentPath)));
        homeButton.setOnAction(e -> list("."));
        refreshButton.setOnAction(e -> list(currentPath));
        downloadButton.setOnAction(e -> download());
        uploadButton.setOnAction(e -> upload());
        mkdirButton.setOnAction(e -> mkdir());
        renameButton.setOnAction(e -> rename());
        deleteButton.setOnAction(e -> delete());
        cancelButton.setOnAction(e -> client.cancelTransfer());
        showProgress(null);
    }

    // --- connection ---------------------------------------------------------------------

    private void attach(SshService service) {
        if (attached == service) {
            return;
        }
        if (attached != null) {
            attached.statusProperty().removeListener(connectionWatcher);
        }
        attached = service;
        status.unbind();
        if (service != null) {
            status.bind(service.statusProperty());
            service.statusProperty().addListener(connectionWatcher);
            onConnectionChanged(service.statusProperty().get());
        } else {
            status.set(ModuleStatus.DISCONNECTED);
        }
    }

    private void onConnectionChanged(ModuleStatus s) {
        if (s == ModuleStatus.CONNECTED) {
            list(currentPath.isEmpty() ? "." : currentPath);
        } else if (s != ModuleStatus.CONNECTING) {
            client.closeChannel();
            table.getItems().clear();
            statusLabel.setText("");
        }
    }

    // --- browsing -----------------------------------------------------------------------

    private void list(String path) {
        statusLabel.setText("Listing " + (path.isEmpty() || ".".equals(path) ? "home" : path) + "…");
        client.list(path, resolved -> {
            currentPath = resolved;
            pathField.setText(resolved);
        }, entries -> {
            table.getItems().setAll(entries);
            long dirs = entries.stream().filter(Entry::directory).count();
            statusLabel.setText(dirs + " folder(s), " + (entries.size() - dirs) + " file(s)");
        }, this::fail);
    }

    private void fail(String message) {
        statusLabel.setText(message);
        Toasts.error(message);
        showProgress(null);
    }

    private void download() {
        Entry sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        if (sel.directory()) {
            Toasts.warning("Open the folder and download files from it; folder download is not supported");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save " + sel.name());
        chooser.setInitialFileName(sel.name());
        File target = chooser.showSaveDialog(table.getScene().getWindow());
        if (target == null) {
            return;
        }
        client.download(SftpClient.join(currentPath, sel.name()), target, this::showProgress, () -> {
            showProgress(null);
            Toasts.success("Downloaded " + sel.name());
        }, this::fail);
    }

    private void upload() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Upload to " + currentPath);
        List<File> files = chooser.showOpenMultipleDialog(table.getScene().getWindow());
        if (files == null || files.isEmpty()) {
            return;
        }
        uploadNext(files, 0);
    }

    private void uploadNext(List<File> files, int index) {
        if (index >= files.size()) {
            showProgress(null);
            Toasts.success("Uploaded " + files.size() + " file(s)");
            list(currentPath);
            return;
        }
        File f = files.get(index);
        client.upload(f, currentPath, this::showProgress, () -> uploadNext(files, index + 1), this::fail);
    }

    private void mkdir() {
        String name = Fields.text(nameField);
        if (name.isEmpty() || name.contains("/")) {
            Toasts.warning("Type a folder name in the Name box (no slashes)");
            return;
        }
        client.mkdir(SftpClient.join(currentPath, name), () -> list(currentPath), this::fail);
    }

    private void rename() {
        Entry sel = table.getSelectionModel().getSelectedItem();
        String name = Fields.text(nameField);
        if (sel == null || name.isEmpty() || name.equals(sel.name())) {
            Toasts.warning("Select an entry and type the new name in the Name box");
            return;
        }
        client.rename(SftpClient.join(currentPath, sel.name()), SftpClient.join(currentPath, name), () -> list(currentPath), this::fail);
    }

    /** Two-step delete without a modal: first click arms, second click within 4 s deletes. */
    private void delete() {
        Entry sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        if (!deleteArmed) {
            deleteArmed = true;
            deleteButton.setText("Confirm delete");
            deleteButton.setGraphic(new FontIcon("fth-alert-triangle"));
            PauseTransition reset = new PauseTransition(Duration.seconds(4));
            reset.setOnFinished(e -> disarmDelete());
            reset.play();
            return;
        }
        disarmDelete();
        client.delete(SftpClient.join(currentPath, sel.name()), sel.directory(), () -> list(currentPath), this::fail);
    }

    private void disarmDelete() {
        deleteArmed = false;
        deleteButton.setText("Delete");
        deleteButton.setGraphic(new FontIcon("fth-trash-2"));
    }

    private void showProgress(Progress p) {
        boolean busy = p != null && !p.finished();
        progressBar.setVisible(busy);
        progressBar.setManaged(busy);
        cancelButton.setVisible(busy);
        cancelButton.setManaged(busy);
        if (p == null) {
            progressLabel.setText("");
            return;
        }
        if (p.total() > 0) {
            progressBar.setProgress((double) p.done() / p.total());
            progressLabel.setText(p.file() + ": " + SftpClient.humanSize(p.done()) + " of " + SftpClient.humanSize(p.total()));
        } else {
            progressBar.setProgress(-1);
            progressLabel.setText(p.file() + ": " + SftpClient.humanSize(p.done()));
        }
        if (p.finished()) {
            progressLabel.setText(p.file() + ": done");
        }
    }

    // --- ModuleController -----------------------------------------------------------

    @Override
    public String moduleId() {
        return ID;
    }

    @Override
    public ReadOnlyObjectProperty<ModuleStatus> statusProperty() {
        return status.getReadOnlyProperty();
    }

    @Override
    public Map<String, String> captureFields() {
        Map<String, String> m = new LinkedHashMap<>(connectionController.captureFields());
        m.put("path", currentPath);
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        connectionController.applyFields(fields);
        String path = fields.get("path");
        if (path != null && !path.isBlank()) {
            currentPath = path;
            pathField.setText(path);
            if (status.get() == ModuleStatus.CONNECTED) {
                list(path);
            }
        }
    }

    @Override
    public void shutdown() {
        client.closeChannel();
        own.stop();
    }
}
