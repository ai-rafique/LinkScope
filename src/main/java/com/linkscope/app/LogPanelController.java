package com.linkscope.app;

import com.linkscope.core.LogEntry;
import com.linkscope.core.LogSink;
import com.linkscope.core.PayloadCodec;
import com.linkscope.core.ui.Toasts;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * The shared log/console panel every module writes into. Hex/ASCII, timestamp and
 * follow toggles apply to all lines; filter matches module tag or rendered text.
 */
public class LogPanelController {
    private static final String LOG_TAG = "LOG";

    @FXML private Label countLabel;
    @FXML private ToggleButton hexToggle;
    @FXML private ToggleButton timestampToggle;
    @FXML private ToggleButton autoscrollToggle;
    @FXML private TextField filterField;
    @FXML private Button clearButton;
    @FXML private Button saveButton;
    @FXML private ListView<LogEntry> list;

    private final ObservableList<LogEntry> all = LogSink.get().entries();
    private final FilteredList<LogEntry> filtered = new FilteredList<>(all);

    @FXML
    private void initialize() {
        list.setItems(filtered);
        list.setCellFactory(v -> new LogCell());
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setPlaceholder(placeholder());
        list.setContextMenu(contextMenu());

        hexToggle.selectedProperty().addListener((obs, old, now) -> {
            list.refresh();
            applyFilter();
        });
        timestampToggle.selectedProperty().addListener((obs, old, now) -> list.refresh());
        filterField.textProperty().addListener((obs, old, now) -> applyFilter());

        countLabel.textProperty().bind(Bindings.createStringBinding(
                () -> filtered.size() == all.size()
                        ? all.size() + " lines"
                        : filtered.size() + " of " + all.size() + " lines",
                filtered, all));

        filtered.addListener((ListChangeListener<LogEntry>) change -> {
            if (autoscrollToggle.isSelected() && !filtered.isEmpty()) {
                list.scrollTo(filtered.size() - 1);
            }
        });

        clearButton.setOnAction(e -> clear());
        saveButton.setOnAction(e -> save());
    }

    public void clear() {
        LogSink.get().clear();
    }

    public boolean isHex() {
        return hexToggle.isSelected();
    }

    public void setHex(boolean hex) {
        hexToggle.setSelected(hex);
    }

    public void toggleHex() {
        hexToggle.setSelected(!hexToggle.isSelected());
    }

    private Label placeholder() {
        Label l = new Label("Nothing logged yet. Start a module above — every TX/RX line from every tab lands here.\n"
                + "Ctrl+L clears, Ctrl+H toggles hex.");
        l.getStyleClass().add("ls-hint");
        l.setWrapText(true);
        return l;
    }

    private void applyFilter() {
        String q = filterField.getText() == null ? "" : filterField.getText().trim().toLowerCase();
        if (q.isEmpty()) {
            filtered.setPredicate(null);
            return;
        }
        boolean hex = isHex();
        filtered.setPredicate(e -> e.module().toLowerCase().contains(q)
                || e.kind().name().toLowerCase().equals(q)
                || e.format(hex, false).toLowerCase().contains(q));
    }

    private ContextMenu contextMenu() {
        MenuItem copyLines = new MenuItem("Copy line(s)");
        copyLines.setOnAction(e -> copy(selectedLines()));
        MenuItem copyHex = new MenuItem("Copy payload as hex");
        copyHex.setOnAction(e -> copy(selectedPayloads(true)));
        MenuItem copyAscii = new MenuItem("Copy payload as ASCII");
        copyAscii.setOnAction(e -> copy(selectedPayloads(false)));
        return new ContextMenu(copyLines, copyHex, copyAscii);
    }

    private String selectedLines() {
        StringBuilder sb = new StringBuilder();
        for (LogEntry e : list.getSelectionModel().getSelectedItems()) {
            sb.append(e.format(isHex(), timestampToggle.isSelected())).append(System.lineSeparator());
        }
        return sb.toString();
    }

    private String selectedPayloads(boolean hex) {
        StringBuilder sb = new StringBuilder();
        for (LogEntry e : list.getSelectionModel().getSelectedItems()) {
            if (e.hasPayload()) {
                sb.append(PayloadCodec.format(e.payload(), hex)).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    private void copy(String text) {
        if (text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void save() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save log");
        chooser.setInitialFileName("linkscope-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".log");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Log files", "*.log", "*.txt"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File target = chooser.showSaveDialog(list.getScene().getWindow());
        if (target == null) {
            return;
        }
        boolean hex = isHex();
        boolean ts = timestampToggle.isSelected();
        List<String> lines = new ArrayList<>(filtered.size());
        for (LogEntry e : filtered) {
            lines.add(e.format(hex, ts));
        }
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            try {
                Files.write(target.toPath(), lines, StandardCharsets.UTF_8);
                LogSink.get().info(LOG_TAG, "Saved " + lines.size() + " lines to " + target);
                Toasts.success("Log saved: " + target.getName());
            } catch (IOException ex) {
                LogSink.get().error(LOG_TAG, "Could not save log to " + target, ex);
            }
        });
    }

    private final class LogCell extends ListCell<LogEntry> {
        @Override
        protected void updateItem(LogEntry item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("log-tx", "log-rx", "log-info", "log-error");
            if (empty || item == null) {
                setText(null);
                return;
            }
            setText(item.format(isHex(), timestampToggle.isSelected()));
            getStyleClass().add(switch (item.kind()) {
                case TX -> "log-tx";
                case RX -> "log-rx";
                case ERROR -> "log-error";
                case INFO -> "log-info";
            });
        }
    }
}
