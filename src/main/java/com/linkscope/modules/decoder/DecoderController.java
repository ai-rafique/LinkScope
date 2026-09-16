package com.linkscope.modules.decoder;

import com.linkscope.core.ByteLayout;
import com.linkscope.core.ByteLayout.Field;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.PayloadCodec;
import com.linkscope.core.PayloadDecoder;
import com.linkscope.core.PayloadDecoder.Decoded;
import com.linkscope.core.ui.Fields;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decoder tool: paste bytes, describe the layout as field widths (with optional type hints),
 * and read every field as integers, floats, text and more. Stateless; presets save the
 * input and layout so a device's format can be recalled by name.
 */
public class DecoderController implements ModuleController {
    public static final String ID = "decoder";
    private static final Map<ModuleStatus, String> LABELS = Map.of(
            ModuleStatus.DISCONNECTED, "Idle",
            ModuleStatus.CONNECTED, "Decoded",
            ModuleStatus.ERROR, "Check input",
            ModuleStatus.CONNECTING, "Working");

    @FXML private TextArea inputArea;
    @FXML private ToggleButton hexInputToggle;
    @FXML private ToggleButton asciiInputToggle;
    @FXML private ToggleButton base64InputToggle;
    @FXML private Label inputStatusLabel;
    @FXML private TextField layoutField;
    @FXML private ToggleButton littleEndianToggle;
    @FXML private ToggleButton bigEndianToggle;
    @FXML private Label layoutStatusLabel;
    @FXML private TextArea groupedDumpArea;
    @FXML private TableView<Field> fieldsTable;
    @FXML private TableColumn<Field, String> indexColumn;
    @FXML private TableColumn<Field, String> offsetColumn;
    @FXML private TableColumn<Field, String> lengthColumn;
    @FXML private TableColumn<Field, String> typeColumn;
    @FXML private TableColumn<Field, String> hexColumn;
    @FXML private TableColumn<Field, String> valueColumn;
    @FXML private TableView<Decoded> detailTable;
    @FXML private TableColumn<Decoded, String> detailNameColumn;
    @FXML private TableColumn<Decoded, String> detailValueColumn;
    @FXML private Button clearButton;

    private final ReadOnlyObjectWrapper<ModuleStatus> status = new ReadOnlyObjectWrapper<>(ModuleStatus.DISCONNECTED);
    private byte[] data = new byte[0];

    @FXML
    private void initialize() {
        ToggleGroup inputMode = new ToggleGroup();
        hexInputToggle.setToggleGroup(inputMode);
        asciiInputToggle.setToggleGroup(inputMode);
        base64InputToggle.setToggleGroup(inputMode);
        hexInputToggle.setSelected(true);
        keepOneSelected(inputMode);
        ToggleGroup endian = new ToggleGroup();
        littleEndianToggle.setToggleGroup(endian);
        bigEndianToggle.setToggleGroup(endian);
        littleEndianToggle.setSelected(true);
        keepOneSelected(endian);

        indexColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().unassigned() ? "tail" : "#" + c.getValue().index()));
        offsetColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().offset() + " (0x" + Integer.toHexString(c.getValue().offset()) + ")"));
        lengthColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().length() + (c.getValue().truncated() ? " (short)" : "")));
        typeColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().unassigned() ? "unassigned"
                : c.getValue().spec().isRest() ? "rest" : c.getValue().spec().type().label));
        hexColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().hex()));
        valueColumn.setCellValueFactory(c -> new SimpleStringProperty(ByteLayout.summarize(c.getValue(), littleEndianToggle.isSelected())));
        fieldsTable.setPlaceholder(new Label("Paste bytes above and describe the layout, e.g. 2 3 5 6 4"));
        fieldsTable.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> showDetail(now));
        fieldsTable.setContextMenu(fieldMenu());

        detailNameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        detailValueColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().value()));
        detailTable.setPlaceholder(new Label("Select a field to see every interpretation"));
        detailTable.setContextMenu(detailMenu());

        inputArea.textProperty().addListener((obs, old, now) -> decode());
        inputMode.selectedToggleProperty().addListener((obs, old, now) -> decode());
        layoutField.textProperty().addListener((obs, old, now) -> decode());
        endian.selectedToggleProperty().addListener((obs, old, now) -> {
            fieldsTable.refresh();
            showDetail(fieldsTable.getSelectionModel().getSelectedItem());
        });
        clearButton.setOnAction(e -> {
            inputArea.clear();
            layoutField.clear();
        });
        decode();
    }

    private static void keepOneSelected(ToggleGroup group) {
        group.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) {
                old.setSelected(true);
            }
        });
    }

    /** Entry point for "Send to Decoder" from the log. */
    public void load(byte[] bytes) {
        hexInputToggle.setSelected(true);
        inputArea.setText(PayloadCodec.toHex(bytes));
        if (Fields.text(layoutField).isEmpty()) {
            layoutField.setText("*");
        }
        inputArea.requestFocus();
    }

    private byte[] parseInput() {
        String text = inputArea.getText() == null ? "" : inputArea.getText().trim();
        if (text.isEmpty()) {
            return new byte[0];
        }
        if (asciiInputToggle.isSelected()) {
            return PayloadCodec.parseAscii(text);
        }
        if (base64InputToggle.isSelected()) {
            return Base64.getMimeDecoder().decode(text);
        }
        return PayloadCodec.parseHex(text);
    }

    private void decode() {
        try {
            data = parseInput();
            inputStatusLabel.setText(data.length + " byte(s)");
            inputStatusLabel.getStyleClass().remove("ls-error-text");
        } catch (IllegalArgumentException ex) {
            data = new byte[0];
            inputStatusLabel.setText("Input: " + ex.getMessage());
            markError(inputStatusLabel);
            fieldsTable.getItems().clear();
            groupedDumpArea.setText("");
            status.set(ModuleStatus.ERROR);
            return;
        }
        String layoutText = Fields.text(layoutField);
        if (data.length == 0 || layoutText.isEmpty()) {
            layoutStatusLabel.setText(layoutText.isEmpty() ? "Layout: widths in bytes, e.g. 2 3 5 6 4  ·  hints: 4f 2i 2u 5s 3x *  ·  groups: [2 4]*3"
                    : "Waiting for bytes");
            layoutStatusLabel.getStyleClass().remove("ls-error-text");
            fieldsTable.getItems().clear();
            groupedDumpArea.setText(data.length == 0 ? "" : PayloadDecoder.hexDump(data));
            status.set(ModuleStatus.DISCONNECTED);
            return;
        }
        List<Field> fields;
        try {
            fields = ByteLayout.parse(layoutText).apply(data);
        } catch (IllegalArgumentException ex) {
            layoutStatusLabel.setText("Layout: " + ex.getMessage());
            markError(layoutStatusLabel);
            fieldsTable.getItems().clear();
            groupedDumpArea.setText(PayloadDecoder.hexDump(data));
            status.set(ModuleStatus.ERROR);
            return;
        }
        int covered = 0;
        int truncated = 0;
        boolean tail = false;
        for (Field f : fields) {
            if (f.unassigned()) {
                tail = true;
            } else {
                covered += f.length();
            }
            if (f.truncated()) {
                truncated++;
            }
        }
        StringBuilder note = new StringBuilder(fields.size() - (tail ? 1 : 0) + " field(s), " + covered + " of " + data.length + " bytes covered");
        if (tail) {
            note.append(" · ").append(data.length - covered).append(" byte(s) left over");
        }
        if (truncated > 0) {
            note.append(" · ").append(truncated).append(" field(s) short of data");
        }
        layoutStatusLabel.setText(note.toString());
        layoutStatusLabel.getStyleClass().remove("ls-error-text");
        Field selected = fieldsTable.getSelectionModel().getSelectedItem();
        int keep = selected == null ? -1 : fieldsTable.getSelectionModel().getSelectedIndex();
        fieldsTable.getItems().setAll(fields);
        if (keep >= 0 && keep < fields.size()) {
            fieldsTable.getSelectionModel().select(keep);
        }
        groupedDumpArea.setText(groupedDump(fields));
        status.set(ModuleStatus.CONNECTED);
    }

    /** Bytes grouped per field: {@code #1[01 02]  #2[03 04 05]  tail[..]}. */
    private static String groupedDump(List<Field> fields) {
        StringBuilder sb = new StringBuilder();
        int lineLen = 0;
        for (Field f : fields) {
            String label = f.unassigned() ? "tail" : "#" + f.index();
            String chunk = label + "[" + f.hex() + (f.truncated() ? " …" : "") + "]";
            if (lineLen + chunk.length() + 2 > 96 && lineLen > 0) {
                sb.append('\n');
                lineLen = 0;
            } else if (lineLen > 0) {
                sb.append("  ");
                lineLen += 2;
            }
            sb.append(chunk);
            lineLen += chunk.length();
        }
        return sb.toString();
    }

    private void showDetail(Field f) {
        if (f == null) {
            detailTable.getItems().clear();
            return;
        }
        List<Decoded> rows = new ArrayList<>(ByteLayout.decode(f, littleEndianToggle.isSelected()));
        if (f.length() >= 4) {
            String json = PayloadDecoder.prettyJson(f.bytes());
            if (json != null) {
                rows.add(new Decoded("JSON", json));
            }
        }
        detailTable.getItems().setAll(rows);
    }

    private static void markError(Label label) {
        if (!label.getStyleClass().contains("ls-error-text")) {
            label.getStyleClass().add("ls-error-text");
        }
    }

    private ContextMenu fieldMenu() {
        MenuItem copyHex = new MenuItem("Copy field hex");
        copyHex.setOnAction(e -> {
            Field f = fieldsTable.getSelectionModel().getSelectedItem();
            if (f != null) {
                copy(f.hex());
            }
        });
        MenuItem copyValue = new MenuItem("Copy field value");
        copyValue.setOnAction(e -> {
            Field f = fieldsTable.getSelectionModel().getSelectedItem();
            if (f != null) {
                copy(ByteLayout.summarize(f, littleEndianToggle.isSelected()));
            }
        });
        MenuItem copyAll = new MenuItem("Copy table as text");
        copyAll.setOnAction(e -> {
            StringBuilder sb = new StringBuilder();
            for (Field f : fieldsTable.getItems()) {
                sb.append(f.unassigned() ? "tail" : "#" + f.index()).append('\t').append(f.offset()).append('\t')
                        .append(f.length()).append('\t').append(f.hex()).append('\t')
                        .append(ByteLayout.summarize(f, littleEndianToggle.isSelected())).append(System.lineSeparator());
            }
            copy(sb.toString());
        });
        return new ContextMenu(copyHex, copyValue, copyAll);
    }

    private ContextMenu detailMenu() {
        MenuItem copy = new MenuItem("Copy value");
        copy.setOnAction(e -> {
            Decoded d = detailTable.getSelectionModel().getSelectedItem();
            if (d != null) {
                copy(d.value());
            }
        });
        return new ContextMenu(copy);
    }

    private static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
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
    public Map<ModuleStatus, String> statusLabels() {
        return LABELS;
    }

    @Override
    public Map<String, String> captureFields() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("input", inputArea.getText() == null ? "" : inputArea.getText());
        m.put("inputMode", asciiInputToggle.isSelected() ? "ascii" : base64InputToggle.isSelected() ? "base64" : "hex");
        m.put("layout", Fields.text(layoutField));
        m.put("endian", bigEndianToggle.isSelected() ? "big" : "little");
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        String mode = fields.get("inputMode");
        if (mode != null) {
            switch (mode) {
                case "ascii" -> asciiInputToggle.setSelected(true);
                case "base64" -> base64InputToggle.setSelected(true);
                default -> hexInputToggle.setSelected(true);
            }
        }
        if (fields.containsKey("input")) {
            inputArea.setText(fields.get("input"));
        }
        Fields.apply(fields, "layout", layoutField);
        if (fields.containsKey("endian")) {
            ("big".equals(fields.get("endian")) ? bigEndianToggle : littleEndianToggle).setSelected(true);
        }
    }

    @Override
    public void shutdown() {
        // nothing to release
    }
}
