package com.linkscope.modules.modbus;

import com.fazecast.jSerialComm.SerialPort;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.PayloadCodec;
import com.linkscope.core.protocol.Crc16;
import com.linkscope.core.protocol.ModbusEncoder;
import com.linkscope.core.protocol.ModbusEncoder.Function;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.modbus.ModbusService.Exchange;
import com.linkscope.modules.modbus.ModbusService.Mode;
import com.linkscope.modules.serial.SerialService;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Modbus tab: link settings, configurable CRC, request builder with polling, decoded exchange table. */
public class ModbusController implements ModuleController {
    public static final String ID = "modbus";
    private static final String CUSTOM_PARAMS = "Custom parameters";
    private static final String CUSTOM_TABLE = "Custom table (pasted)";
    private static final byte[] REFERENCE = {0x01, 0x03, 0x00, 0x00, 0x00, 0x0A};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    record PortChoice(String name, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    // link
    @FXML private ComboBox<Mode> modeCombo;
    @FXML private HBox serialBox;
    @FXML private ComboBox<PortChoice> portCombo;
    @FXML private Button refreshButton;
    @FXML private ComboBox<String> baudCombo;
    @FXML private ComboBox<Integer> dataBitsCombo;
    @FXML private ComboBox<SerialService.Parity> parityCombo;
    @FXML private ComboBox<SerialService.StopBits> stopBitsCombo;
    @FXML private HBox tcpBox;
    @FXML private TextField hostField;
    @FXML private TextField tcpPortField;
    @FXML private Button openButton;
    @FXML private StatusBadge statusBadge;
    // crc
    @FXML private ComboBox<String> crcPresetCombo;
    @FXML private TextField polyField;
    @FXML private TextField initField;
    @FXML private TextField xorOutField;
    @FXML private CheckBox reflectCheck;
    @FXML private ComboBox<Crc16.ByteOrder> byteOrderCombo;
    @FXML private TextArea tableArea;
    @FXML private Label crcCheckLabel;
    // request
    @FXML private TextField unitField;
    @FXML private ComboBox<Function> functionCombo;
    @FXML private TextField addressField;
    @FXML private HBox quantityBox;
    @FXML private TextField quantityField;
    @FXML private HBox valuesBox;
    @FXML private TextField valuesField;
    @FXML private HBox rawBox;
    @FXML private TextField rawPduField;
    @FXML private Button sendButton;
    @FXML private ToggleButton pollToggle;
    @FXML private TextField pollIntervalField;
    // table
    @FXML private TableView<Exchange> table;
    @FXML private TableColumn<Exchange, String> timeColumn;
    @FXML private TableColumn<Exchange, String> dirColumn;
    @FXML private TableColumn<Exchange, String> unitColumn;
    @FXML private TableColumn<Exchange, String> functionColumn;
    @FXML private TableColumn<Exchange, String> detailColumn;
    @FXML private TableColumn<Exchange, String> crcColumn;
    @FXML private TableColumn<Exchange, String> rawColumn;
    @FXML private Button clearButton;

    private final ModbusService service = new ModbusService();
    private String pendingPortName;
    private boolean updatingCrcFields;

    @FXML
    private void initialize() {
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding active = Bindings.createBooleanBinding(
                () -> service.statusProperty().get().isActive(), service.statusProperty());
        for (Node n : List.of(modeCombo, portCombo, refreshButton, baudCombo, dataBitsCombo, parityCombo, stopBitsCombo,
                hostField, tcpPortField)) {
            n.disableProperty().bind(active);
        }
        BooleanBinding notConnected = service.statusProperty().isNotEqualTo(ModuleStatus.CONNECTED);
        sendButton.disableProperty().bind(notConnected);
        pollToggle.disableProperty().bind(notConnected);

        // link
        modeCombo.getItems().setAll(Mode.values());
        modeCombo.setValue(Mode.RTU);
        modeCombo.valueProperty().addListener((obs, old, now) -> showMode(now));
        showMode(Mode.RTU);
        for (int b : SerialService.COMMON_BAUD_RATES) {
            baudCombo.getItems().add(Integer.toString(b));
        }
        baudCombo.setValue("9600");
        dataBitsCombo.getItems().setAll(8, 7);
        dataBitsCombo.setValue(8);
        parityCombo.getItems().setAll(SerialService.Parity.values());
        parityCombo.setValue(SerialService.Parity.NONE);
        stopBitsCombo.getItems().setAll(SerialService.StopBits.values());
        stopBitsCombo.setValue(SerialService.StopBits.ONE);
        portCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(PortChoice c) {
                return c == null ? "" : c.label();
            }

            @Override
            public PortChoice fromString(String s) {
                return null;
            }
        });
        refreshButton.setOnAction(e -> loadPorts());
        loadPorts();
        service.statusProperty().addListener((obs, old, now) -> updateOpenButton(now));
        updateOpenButton(service.statusProperty().get());
        openButton.setOnAction(e -> toggleOpen());

        // crc
        for (Crc16.Params p : Crc16.PRESETS) {
            crcPresetCombo.getItems().add(p.name());
        }
        crcPresetCombo.getItems().addAll(CUSTOM_PARAMS, CUSTOM_TABLE);
        crcPresetCombo.setValue(Crc16.MODBUS.name());
        byteOrderCombo.getItems().setAll(Crc16.ByteOrder.values());
        byteOrderCombo.setValue(Crc16.ByteOrder.LOW_FIRST);
        crcPresetCombo.valueProperty().addListener((obs, old, now) -> onPresetChosen(now));
        for (TextField f : List.of(polyField, initField, xorOutField)) {
            f.textProperty().addListener((obs, old, now) -> onCrcFieldEdited());
        }
        reflectCheck.selectedProperty().addListener((obs, old, now) -> onCrcFieldEdited());
        byteOrderCombo.valueProperty().addListener((obs, old, now) -> applyCrc());
        tableArea.textProperty().addListener((obs, old, now) -> {
            if (!updatingCrcFields && !now.isBlank()) {
                crcPresetCombo.setValue(CUSTOM_TABLE);
            }
            applyCrc();
        });
        onPresetChosen(Crc16.MODBUS.name());

        // request
        functionCombo.getItems().setAll(Function.values());
        functionCombo.setValue(Function.READ_HOLDING_REGISTERS);
        functionCombo.valueProperty().addListener((obs, old, now) -> showFunctionFields(now));
        showFunctionFields(Function.READ_HOLDING_REGISTERS);
        sendButton.setOnAction(e -> sendOnce());
        for (TextField f : List.of(addressField, quantityField, valuesField, rawPduField, unitField)) {
            f.setOnAction(e -> sendOnce());
        }
        pollToggle.selectedProperty().addListener((obs, old, now) -> togglePoll(now));
        service.statusProperty().addListener((obs, old, now) -> {
            if (now != ModuleStatus.CONNECTED && pollToggle.isSelected()) {
                pollToggle.setSelected(false);
            }
        });

        // table
        timeColumn.setCellValueFactory(c -> new SimpleStringProperty(TIME.format(c.getValue().time())));
        dirColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().direction()));
        unitColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().frame().map(f -> String.valueOf(f.unitId())).orElse("")));
        functionColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().frame()
                .map(f -> String.format("%02d %s", f.functionCode() & 0x7F, f.exception() ? "EXCEPTION" : f.functionName()))
                .orElse("")));
        detailColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().summary()));
        crcColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().crcOk() ? "ok" : "BAD"));
        rawColumn.setCellValueFactory(c -> new SimpleStringProperty(PayloadCodec.toHex(c.getValue().raw())));
        table.setItems(service.exchanges());
        table.setPlaceholder(new Label("No frames yet. Open the link and send a request, or just listen on a bus."));
        service.exchanges().addListener((ListChangeListener<Exchange>) change -> {
            if (!service.exchanges().isEmpty()) {
                table.scrollTo(service.exchanges().size() - 1);
            }
        });
        MenuItem copyRaw = new MenuItem("Copy raw hex");
        copyRaw.setOnAction(e -> {
            Exchange x = table.getSelectionModel().getSelectedItem();
            if (x != null) {
                ClipboardContent content = new ClipboardContent();
                content.putString(PayloadCodec.toHex(x.raw()));
                Clipboard.getSystemClipboard().setContent(content);
            }
        });
        MenuItem useAsRaw = new MenuItem("Use as raw request (unit + PDU)");
        useAsRaw.setOnAction(e -> {
            Exchange x = table.getSelectionModel().getSelectedItem();
            if (x != null) {
                byte[] raw = x.raw();
                int drop = service.mode() == Mode.TCP ? 6 : 0;
                int end = service.mode() == Mode.TCP ? raw.length : Math.max(drop, raw.length - 2);
                rawPduField.setText(PayloadCodec.toHex(java.util.Arrays.copyOfRange(raw, Math.min(drop, raw.length), end)));
                functionCombo.setValue(Function.RAW_PDU);
            }
        });
        table.setContextMenu(new ContextMenu(copyRaw, useAsRaw));
        clearButton.setOnAction(e -> service.exchanges().clear());
    }

    // --- link -----------------------------------------------------------------------

    private void showMode(Mode mode) {
        boolean tcp = mode == Mode.TCP;
        serialBox.setVisible(!tcp);
        serialBox.setManaged(!tcp);
        tcpBox.setVisible(tcp);
        tcpBox.setManaged(tcp);
    }

    private void loadPorts() {
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            List<PortChoice> choices = new ArrayList<>();
            for (SerialPort p : SerialService.availablePorts()) {
                choices.add(new PortChoice(p.getSystemPortName(), p.getSystemPortName() + " — " + p.getDescriptivePortName()));
            }
            FxThread.run(() -> {
                String keep = pendingPortName != null ? pendingPortName
                        : portCombo.getValue() != null ? portCombo.getValue().name() : null;
                pendingPortName = null;
                portCombo.getItems().setAll(choices);
                portCombo.setPromptText(choices.isEmpty() ? "No serial ports found" : "Select a port");
                selectPort(keep);
            });
        });
    }

    private void selectPort(String name) {
        for (PortChoice c : portCombo.getItems()) {
            if (c.name().equals(name)) {
                portCombo.setValue(c);
                return;
            }
        }
        if (portCombo.getValue() == null && !portCombo.getItems().isEmpty()) {
            portCombo.getSelectionModel().selectFirst();
        }
    }

    private void toggleOpen() {
        if (service.statusProperty().get().isActive()) {
            service.stop();
            return;
        }
        try {
            service.setMode(modeCombo.getValue());
            if (modeCombo.getValue() == Mode.TCP) {
                service.setTcp(Fields.requireText(hostField, "Host"), Fields.port(tcpPortField, "Port"));
            } else {
                PortChoice port = portCombo.getValue();
                if (port == null) {
                    throw new IllegalArgumentException("Select a serial port first");
                }
                String baud = baudCombo.getEditor().getText();
                if (baud == null || baud.isBlank()) {
                    baud = baudCombo.getValue();
                }
                service.setSerial(port.name(), Integer.parseInt(baud == null ? "" : baud.trim()), dataBitsCombo.getValue(),
                        parityCombo.getValue(), stopBitsCombo.getValue());
            }
            applyCrc();
        } catch (NumberFormatException ex) {
            Toasts.error("Baud rate must be a whole number");
            return;
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
        service.start();
    }

    private void updateOpenButton(ModuleStatus s) {
        boolean active = s != null && s.isActive();
        openButton.setText(active ? "Close" : "Open");
        openButton.setGraphic(new FontIcon(active ? "fth-square" : "fth-play"));
    }

    // --- crc ------------------------------------------------------------------------

    private void onPresetChosen(String name) {
        if (name == null) {
            return;
        }
        for (Crc16.Params p : Crc16.PRESETS) {
            if (p.name().equals(name)) {
                updatingCrcFields = true;
                polyField.setText(Crc16.hex4(p.poly()));
                initField.setText(Crc16.hex4(p.init()));
                xorOutField.setText(Crc16.hex4(p.xorOut()));
                reflectCheck.setSelected(p.reflected());
                if (p == Crc16.MODBUS || p == Crc16.ARC || p == Crc16.USB) {
                    byteOrderCombo.setValue(Crc16.ByteOrder.LOW_FIRST);
                } else {
                    byteOrderCombo.setValue(Crc16.ByteOrder.HIGH_FIRST);
                }
                updatingCrcFields = false;
                break;
            }
        }
        boolean table = CUSTOM_TABLE.equals(name);
        tableArea.setDisable(!table);
        polyField.setDisable(table);
        applyCrc();
    }

    private void onCrcFieldEdited() {
        if (updatingCrcFields) {
            return;
        }
        String preset = crcPresetCombo.getValue();
        if (preset != null && !preset.startsWith("Custom")) {
            updatingCrcFields = true;
            crcPresetCombo.setValue(CUSTOM_PARAMS);
            updatingCrcFields = false;
        }
        applyCrc();
    }

    private static int parseHex(TextField field, String label) {
        String t = Fields.text(field).toLowerCase();
        if (t.startsWith("0x")) {
            t = t.substring(2);
        }
        try {
            int v = Integer.parseInt(t, 16);
            if (v < 0 || v > 0xFFFF) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a 16-bit hex value, e.g. 8005");
        }
    }

    private Crc16 buildCrc() {
        String preset = crcPresetCombo.getValue();
        Crc16.ByteOrder order = byteOrderCombo.getValue() == null ? Crc16.ByteOrder.LOW_FIRST : byteOrderCombo.getValue();
        if (CUSTOM_TABLE.equals(preset)) {
            if (tableArea.getText() == null || tableArea.getText().isBlank()) {
                throw new IllegalArgumentException("Paste a CRC table (256 entries, or 256 high + 256 low bytes)");
            }
            return Crc16.parseTable(tableArea.getText(), reflectCheck.isSelected(), parseHex(initField, "Init"),
                    parseHex(xorOutField, "Xor-out"), order);
        }
        for (Crc16.Params p : Crc16.PRESETS) {
            if (p.name().equals(preset)) {
                return Crc16.of(p, order);
            }
        }
        return Crc16.ofParameters(parseHex(polyField, "Polynomial"), parseHex(initField, "Init"), reflectCheck.isSelected(),
                parseHex(xorOutField, "Xor-out"), order);
    }

    /** Rebuilds the CRC from the panel, pushes it to the service and shows a reference value. */
    private void applyCrc() {
        try {
            Crc16 crc = buildCrc();
            service.setCrc(crc);
            int value = crc.compute(REFERENCE);
            crcCheckLabel.setText("CRC of 01 03 00 00 00 0A = " + Crc16.hex4(value)
                    + (value == 0xCDC5 ? " (matches standard Modbus)" : " (standard Modbus gives cdc5)"));
            crcCheckLabel.getStyleClass().remove("ls-error-text");
        } catch (IllegalArgumentException ex) {
            crcCheckLabel.setText("Invalid: " + ex.getMessage());
            if (!crcCheckLabel.getStyleClass().contains("ls-error-text")) {
                crcCheckLabel.getStyleClass().add("ls-error-text");
            }
        }
    }

    // --- request ---------------------------------------------------------------------

    private void showFunctionFields(Function f) {
        boolean raw = f == Function.RAW_PDU;
        rawBox.setVisible(raw);
        rawBox.setManaged(raw);
        addressField.getParent().setVisible(!raw);
        addressField.getParent().setManaged(!raw);
        quantityBox.setVisible(f.usesQuantity);
        quantityBox.setManaged(f.usesQuantity);
        valuesBox.setVisible(f.usesValues);
        valuesBox.setManaged(f.usesValues);
        valuesField.setPromptText(f == Function.WRITE_SINGLE_COIL || f == Function.WRITE_MULTIPLE_COILS
                ? "1 0 1  or  on off" : "1, 2, 0x0A");
    }

    /** Unit id followed by the PDU, built from the form. */
    private byte[] buildRequest() {
        int unit = Fields.intValue(unitField, "Unit id", 0, 255);
        Function f = functionCombo.getValue();
        byte[] pdu;
        if (f == Function.RAW_PDU) {
            pdu = PayloadCodec.parseHex(Fields.requireText(rawPduField, "Raw PDU"));
            if (pdu.length == 0) {
                throw new IllegalArgumentException("Raw PDU needs at least a function code");
            }
        } else {
            int address = Fields.intValue(addressField, "Address", 0, 65535);
            pdu = switch (f) {
                case READ_COILS, READ_DISCRETE_INPUTS, READ_HOLDING_REGISTERS, READ_INPUT_REGISTERS ->
                        ModbusEncoder.readRequest(f.code, address, Fields.intValue(quantityField, "Quantity", 1, 2000));
                case WRITE_SINGLE_COIL -> ModbusEncoder.writeSingleCoil(address, ModbusEncoder.parseBits(valuesField.getText())[0]);
                case WRITE_SINGLE_REGISTER -> ModbusEncoder.writeSingleRegister(address, ModbusEncoder.parseValues(valuesField.getText())[0]);
                case WRITE_MULTIPLE_COILS -> ModbusEncoder.writeMultipleCoils(address, ModbusEncoder.parseBits(valuesField.getText()));
                case WRITE_MULTIPLE_REGISTERS -> ModbusEncoder.writeMultipleRegisters(address, ModbusEncoder.parseValues(valuesField.getText()));
                default -> throw new IllegalArgumentException("Unsupported function");
            };
        }
        byte[] out = new byte[pdu.length + 1];
        out[0] = (byte) unit;
        System.arraycopy(pdu, 0, out, 1, pdu.length);
        return out;
    }

    private void sendOnce() {
        if (service.statusProperty().get() != ModuleStatus.CONNECTED) {
            Toasts.warning("Open the link first");
            return;
        }
        try {
            service.sendRaw(buildRequest());
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
        }
    }

    private void togglePoll(boolean on) {
        if (!on) {
            service.stopPolling();
            return;
        }
        try {
            byte[] request = buildRequest();
            int interval = Fields.intValue(pollIntervalField, "Poll interval", 20, 3_600_000);
            service.startPolling(() -> request, interval);
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            pollToggle.setSelected(false);
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
        m.put("mode", modeCombo.getValue().name());
        m.put("port", portCombo.getValue() == null ? "" : portCombo.getValue().name());
        String baud = baudCombo.getEditor().getText();
        m.put("baud", baud == null || baud.isBlank() ? String.valueOf(baudCombo.getValue()) : baud.trim());
        m.put("dataBits", String.valueOf(dataBitsCombo.getValue()));
        m.put("parity", parityCombo.getValue().name());
        m.put("stopBits", stopBitsCombo.getValue().name());
        m.put("host", Fields.text(hostField));
        m.put("tcpPort", Fields.text(tcpPortField));
        m.put("crcPreset", crcPresetCombo.getValue());
        m.put("crcPoly", Fields.text(polyField));
        m.put("crcInit", Fields.text(initField));
        m.put("crcXorOut", Fields.text(xorOutField));
        m.put("crcReflect", Boolean.toString(reflectCheck.isSelected()));
        m.put("crcByteOrder", byteOrderCombo.getValue().name());
        m.put("crcTable", tableArea.getText() == null ? "" : tableArea.getText());
        m.put("unit", Fields.text(unitField));
        m.put("function", functionCombo.getValue().name());
        m.put("address", Fields.text(addressField));
        m.put("quantity", Fields.text(quantityField));
        m.put("values", Fields.text(valuesField));
        m.put("rawPdu", Fields.text(rawPduField));
        m.put("pollIntervalMs", Fields.text(pollIntervalField));
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        applyEnum(fields, "mode", Mode.class, modeCombo);
        String port = fields.get("port");
        if (port != null && !port.isBlank()) {
            pendingPortName = port;
            selectPort(port);
        }
        if (fields.containsKey("baud")) {
            baudCombo.setValue(fields.get("baud"));
        }
        if (fields.containsKey("dataBits")) {
            try {
                dataBitsCombo.setValue(Integer.parseInt(fields.get("dataBits")));
            } catch (NumberFormatException ignored) {
                // keep current
            }
        }
        applyEnum(fields, "parity", SerialService.Parity.class, parityCombo);
        applyEnum(fields, "stopBits", SerialService.StopBits.class, stopBitsCombo);
        Fields.apply(fields, "host", hostField);
        Fields.apply(fields, "tcpPort", tcpPortField);
        updatingCrcFields = true;
        Fields.apply(fields, "crcPoly", polyField);
        Fields.apply(fields, "crcInit", initField);
        Fields.apply(fields, "crcXorOut", xorOutField);
        Fields.apply(fields, "crcReflect", reflectCheck);
        applyEnum(fields, "crcByteOrder", Crc16.ByteOrder.class, byteOrderCombo);
        if (fields.containsKey("crcTable")) {
            tableArea.setText(fields.get("crcTable"));
        }
        updatingCrcFields = false;
        String preset = fields.get("crcPreset");
        if (preset != null && crcPresetCombo.getItems().contains(preset)) {
            if (preset.startsWith("Custom")) {
                updatingCrcFields = true;
                crcPresetCombo.setValue(preset);
                updatingCrcFields = false;
                tableArea.setDisable(!CUSTOM_TABLE.equals(preset));
                polyField.setDisable(CUSTOM_TABLE.equals(preset));
            } else {
                crcPresetCombo.setValue(preset);
            }
        }
        applyCrc();
        Fields.apply(fields, "unit", unitField);
        applyEnum(fields, "function", Function.class, functionCombo);
        Fields.apply(fields, "address", addressField);
        Fields.apply(fields, "quantity", quantityField);
        Fields.apply(fields, "values", valuesField);
        Fields.apply(fields, "rawPdu", rawPduField);
        Fields.apply(fields, "pollIntervalMs", pollIntervalField);
    }

    private static <E extends Enum<E>> void applyEnum(Map<String, String> fields, String key, Class<E> type, ComboBox<E> combo) {
        String v = fields.get(key);
        if (v == null) {
            return;
        }
        try {
            combo.setValue(Enum.valueOf(type, v));
        } catch (IllegalArgumentException ignored) {
            // keep current
        }
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
