package com.linkscope.core.ui;

import com.linkscope.core.LogEntry;
import com.linkscope.core.PayloadDecoder;
import com.linkscope.core.PayloadDecoder.Decoded;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.time.format.DateTimeFormatter;

/**
 * Payload inspector for one log line: hex dump plus live decoders for the selected bytes.
 * Select bytes in the dump (hex or ASCII column) or type an offset and length.
 */
public class InspectorController {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @FXML private Label headerLabel;
    @FXML private TextArea dumpArea;
    @FXML private TextField offsetField;
    @FXML private TextField lengthField;
    @FXML private Label selectionLabel;
    @FXML private TableView<Decoded> decodedTable;
    @FXML private TableColumn<Decoded, String> nameColumn;
    @FXML private TableColumn<Decoded, String> valueColumn;

    private byte[] data = new byte[0];
    private boolean updating;

    @FXML
    private void initialize() {
        nameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        valueColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().value()));
        decodedTable.setPlaceholder(new Label("Select a TX or RX line in the log to inspect its bytes."));
        MenuItem copy = new MenuItem("Copy value");
        copy.setOnAction(e -> {
            Decoded d = decodedTable.getSelectionModel().getSelectedItem();
            if (d != null) {
                ClipboardContent content = new ClipboardContent();
                content.putString(d.value());
                Clipboard.getSystemClipboard().setContent(content);
            }
        });
        decodedTable.setContextMenu(new ContextMenu(copy));

        dumpArea.selectionProperty().addListener((obs, old, now) -> onDumpSelection(now));
        offsetField.setOnAction(e -> decodeFromFields());
        lengthField.setOnAction(e -> decodeFromFields());
        offsetField.textProperty().addListener((obs, old, now) -> {
            if (!updating) {
                decodeFromFields();
            }
        });
        lengthField.textProperty().addListener((obs, old, now) -> {
            if (!updating) {
                decodeFromFields();
            }
        });
        clear();
    }

    /** Shows an entry; non-payload lines clear the inspector. */
    public void show(LogEntry entry) {
        if (entry == null || !entry.hasPayload()) {
            clear();
            return;
        }
        data = entry.payload();
        headerLabel.setText(TIME.format(entry.time()) + "  [" + entry.module() + "] [" + entry.kind() + "]  "
                + (entry.note() == null ? "" : entry.note()) + "  ·  " + data.length + " B");
        dumpArea.setText(PayloadDecoder.hexDump(data));
        setFields(0, Math.min(4, data.length));
        decode(0, Math.min(4, data.length));
    }

    public void clear() {
        data = new byte[0];
        headerLabel.setText("Nothing selected");
        dumpArea.setText("");
        setFields(0, 0);
        decodedTable.getItems().clear();
        selectionLabel.setText("Select bytes in the dump, or type an offset");
    }

    private void onDumpSelection(IndexRange range) {
        if (data.length == 0 || range == null) {
            return;
        }
        int[] r = PayloadDecoder.selectionToRange(range.getStart(), range.getEnd(), data.length);
        if (r == null) {
            int single = PayloadDecoder.offsetAt(range.getStart(), data.length);
            if (single < 0) {
                return;
            }
            r = new int[] {single, 1};
        }
        setFields(r[0], r[1]);
        decode(r[0], r[1]);
    }

    private void decodeFromFields() {
        try {
            int offset = Integer.parseInt(offsetField.getText().trim());
            int length = Integer.parseInt(lengthField.getText().trim());
            decode(offset, length);
        } catch (NumberFormatException ignored) {
            // wait for a valid number
        }
    }

    private void setFields(int offset, int length) {
        updating = true;
        offsetField.setText(String.valueOf(offset));
        lengthField.setText(String.valueOf(length));
        updating = false;
    }

    private void decode(int offset, int length) {
        decodedTable.getItems().setAll(PayloadDecoder.decode(data, offset, length));
        if (data.length == 0) {
            selectionLabel.setText("");
        } else if (offset < 0 || offset >= data.length) {
            selectionLabel.setText("Offset out of range (0–" + (data.length - 1) + ")");
        } else {
            int end = Math.min(data.length, offset + Math.max(1, length)) - 1;
            selectionLabel.setText("bytes " + offset + "–" + end + " of " + data.length);
        }
    }
}
