package com.linkscope.app;

import com.linkscope.core.LogEntry;
import com.linkscope.core.LogEntry.Kind;
import com.linkscope.core.LogEntry.TimeMode;
import com.linkscope.core.LogSink;
import com.linkscope.core.PayloadCodec;
import com.linkscope.core.ui.DecoderBridge;
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
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * The shared log/console panel every module writes into. Hex/ASCII and timestamp modes
 * apply to all lines; module and kind chips plus a text filter narrow the view; any
 * TX/RX line can be sent to the Decoder tool.
 */
public class LogPanelController {
    private static final String LOG_TAG = "LOG";

    @FXML private Label countLabel;
    @FXML private ToggleButton hexToggle;
    @FXML private ToggleButton timestampToggle;
    @FXML private ToggleButton deltaToggle;
    @FXML private ToggleButton autoscrollToggle;
    @FXML private TextField filterField;
    @FXML private Button decodeButton;
    @FXML private Button clearButton;
    @FXML private Button saveButton;
    @FXML private FlowPane chipRow;
    @FXML private ToggleButton txChip;
    @FXML private ToggleButton rxChip;
    @FXML private ToggleButton infoChip;
    @FXML private ToggleButton errorChip;
    @FXML private ListView<LogEntry> list;

    private final ObservableList<LogEntry> all = LogSink.get().entries();
    private final FilteredList<LogEntry> filtered = new FilteredList<>(all);
    private final Map<Kind, ToggleButton> kindChips = new EnumMap<>(Kind.class);
    private final Map<String, ToggleButton> moduleChips = new LinkedHashMap<>();
    private final Set<String> hiddenModules = new HashSet<>();

    @FXML
    private void initialize() {
        list.setItems(filtered);
        list.setCellFactory(v -> new LogCell());
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setPlaceholder(placeholder());
        list.setContextMenu(contextMenu());

        kindChips.put(Kind.TX, txChip);
        kindChips.put(Kind.RX, rxChip);
        kindChips.put(Kind.INFO, infoChip);
        kindChips.put(Kind.ERROR, errorChip);
        for (ToggleButton chip : kindChips.values()) {
            chip.selectedProperty().addListener((obs, old, now) -> applyFilter());
        }
        for (LogEntry e : all) {
            ensureModuleChip(e.module());
        }
        all.addListener((ListChangeListener<LogEntry>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (LogEntry e : change.getAddedSubList()) {
                        ensureModuleChip(e.module());
                    }
                }
            }
        });

        hexToggle.selectedProperty().addListener((obs, old, now) -> {
            list.refresh();
            applyFilter();
        });
        timestampToggle.selectedProperty().addListener((obs, old, now) -> {
            if (now) {
                deltaToggle.setSelected(false);
            }
            list.refresh();
        });
        deltaToggle.selectedProperty().addListener((obs, old, now) -> {
            if (now) {
                timestampToggle.setSelected(false);
            }
            list.refresh();
        });
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

        decodeButton.disableProperty().bind(Bindings.createBooleanBinding(() -> {
            LogEntry e = list.getSelectionModel().getSelectedItem();
            return e == null || !e.hasPayload();
        }, list.getSelectionModel().selectedItemProperty()));
        decodeButton.setOnAction(e -> sendSelectedToDecoder());
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

    /** Ctrl+I: open the selected TX/RX line in the Decoder tool. */
    public void sendSelectedToDecoder() {
        LogEntry e = list.getSelectionModel().getSelectedItem();
        if (e == null || !e.hasPayload()) {
            Toasts.info("Select a TX or RX line first");
            return;
        }
        DecoderBridge.open(e.payload());
    }

    private TimeMode timeMode() {
        return deltaToggle.isSelected() ? TimeMode.DELTA : timestampToggle.isSelected() ? TimeMode.ABSOLUTE : TimeMode.NONE;
    }

    // --- filtering --------------------------------------------------------------------

    private void ensureModuleChip(String module) {
        if (module == null || moduleChips.containsKey(module)) {
            return;
        }
        ToggleButton chip = new ToggleButton(module);
        chip.setSelected(true);
        chip.getStyleClass().addAll("ls-chip", "small");
        chip.setTooltip(new Tooltip("Show / hide lines from " + module));
        chip.selectedProperty().addListener((obs, old, now) -> {
            if (now) {
                hiddenModules.remove(module);
            } else {
                hiddenModules.add(module);
            }
            applyFilter();
        });
        moduleChips.put(module, chip);
        chipRow.getChildren().add(chip);
    }

    private void applyFilter() {
        String q = filterField.getText() == null ? "" : filterField.getText().trim().toLowerCase();
        boolean hex = isHex();
        Set<Kind> kinds = new HashSet<>();
        kindChips.forEach((kind, chip) -> {
            if (chip.isSelected()) {
                kinds.add(kind);
            }
        });
        Set<String> hidden = new HashSet<>(hiddenModules);
        boolean allKinds = kinds.size() == Kind.values().length;
        if (q.isEmpty() && allKinds && hidden.isEmpty()) {
            filtered.setPredicate(null);
            return;
        }
        filtered.setPredicate(e -> kinds.contains(e.kind())
                && !hidden.contains(e.module())
                && (q.isEmpty() || e.module().toLowerCase().contains(q) || e.format(hex, false).toLowerCase().contains(q)));
    }

    // --- misc UI ----------------------------------------------------------------------

    private Label placeholder() {
        Label l = new Label("Nothing logged yet. Start a module above — every TX/RX line from every module lands here.\n"
                + "Ctrl+L clears, Ctrl+H toggles hex, Ctrl+I opens the selected line in the Decoder.");
        l.getStyleClass().add("ls-hint");
        l.setWrapText(true);
        return l;
    }

    private ContextMenu contextMenu() {
        MenuItem decode = new MenuItem("Send to Decoder");
        decode.setOnAction(e -> sendSelectedToDecoder());
        MenuItem copyLines = new MenuItem("Copy line(s)");
        copyLines.setOnAction(e -> copy(selectedLines()));
        MenuItem copyHex = new MenuItem("Copy payload as hex");
        copyHex.setOnAction(e -> copy(selectedPayloads(true)));
        MenuItem copyAscii = new MenuItem("Copy payload as ASCII");
        copyAscii.setOnAction(e -> copy(selectedPayloads(false)));
        return new ContextMenu(decode, copyLines, copyHex, copyAscii);
    }

    private String selectedLines() {
        StringBuilder sb = new StringBuilder();
        TimeMode mode = timeMode();
        for (LogEntry e : list.getSelectionModel().getSelectedItems()) {
            int idx = filtered.indexOf(e);
            LocalTime prev = idx > 0 ? filtered.get(idx - 1).time() : null;
            sb.append(e.format(isHex(), mode, prev)).append(System.lineSeparator());
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
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        chooser.setInitialFileName("linkscope-" + stamp + ".log");
        FileChooser.ExtensionFilter logFilter = new FileChooser.ExtensionFilter("Log text (*.log, *.txt)", "*.log", "*.txt");
        FileChooser.ExtensionFilter csvFilter = new FileChooser.ExtensionFilter("CSV (*.csv)", "*.csv");
        chooser.getExtensionFilters().addAll(logFilter, csvFilter, new FileChooser.ExtensionFilter("All files", "*.*"));
        File target = chooser.showSaveDialog(list.getScene().getWindow());
        if (target == null) {
            return;
        }
        boolean csv = chooser.getSelectedExtensionFilter() == csvFilter || target.getName().toLowerCase().endsWith(".csv");
        boolean hex = isHex();
        TimeMode mode = timeMode();
        List<String> lines = new ArrayList<>(filtered.size() + 1);
        if (csv) {
            lines.add(LogEntry.CSV_HEADER);
            for (LogEntry e : filtered) {
                lines.add(e.toCsvRow());
            }
        } else {
            LocalTime prev = null;
            for (LogEntry e : filtered) {
                lines.add(e.format(hex, mode, prev));
                prev = e.time();
            }
        }
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            try {
                Files.write(target.toPath(), lines, StandardCharsets.UTF_8);
                LogSink.get().info(LOG_TAG, "Saved " + (csv ? lines.size() - 1 : lines.size()) + " lines to " + target);
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
            LocalTime prev = null;
            if (deltaToggle.isSelected()) {
                int idx = getIndex();
                if (idx > 0 && idx - 1 < filtered.size()) {
                    prev = filtered.get(idx - 1).time();
                }
            }
            setText(item.format(isHex(), timeMode(), prev));
            getStyleClass().add(switch (item.kind()) {
                case TX -> "log-tx";
                case RX -> "log-rx";
                case ERROR -> "log-error";
                case INFO -> "log-info";
            });
        }
    }
}
