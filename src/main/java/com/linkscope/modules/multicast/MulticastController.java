package com.linkscope.modules.multicast;

import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.SendBarController;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class MulticastController implements ModuleController {
    public static final String ID = "multicast";

    /** Combo entry: a concrete interface, or "Auto" when {@code nif} is null. */
    record IfaceChoice(NetworkInterface nif, String label) {
        static final IfaceChoice AUTO = new IfaceChoice(null, "Auto (first multicast-capable interface)");

        String name() {
            return nif == null ? "" : nif.getName();
        }
    }

    @FXML private TextField groupField;
    @FXML private TextField portField;
    @FXML private ComboBox<IfaceChoice> interfaceCombo;
    @FXML private TextField ttlField;
    @FXML private CheckBox loopbackCheck;
    @FXML private Button joinButton;
    @FXML private StatusBadge statusBadge;
    @FXML private SendBarController sendBarController;

    private final MulticastService service = new MulticastService();

    @FXML
    private void initialize() {
        statusBadge.statusProperty().bind(service.statusProperty());
        BooleanBinding active = Bindings.createBooleanBinding(
                () -> service.statusProperty().get().isActive(), service.statusProperty());
        groupField.disableProperty().bind(active);
        portField.disableProperty().bind(active);
        interfaceCombo.disableProperty().bind(active);
        ttlField.disableProperty().bind(active);
        loopbackCheck.disableProperty().bind(active);

        interfaceCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(IfaceChoice c) {
                return c == null ? "" : c.label();
            }

            @Override
            public IfaceChoice fromString(String s) {
                return null;
            }
        });
        interfaceCombo.getItems().setAll(IfaceChoice.AUTO);
        interfaceCombo.getSelectionModel().selectFirst();
        loadInterfaces();

        service.statusProperty().addListener((obs, old, now) -> updateJoinButton(now));
        updateJoinButton(service.statusProperty().get());
        joinButton.setOnAction(e -> toggleJoin());
        sendBarController.setOnSend(this::send);
    }

    /** Interface enumeration can block briefly, so it runs off the FX thread. */
    private void loadInterfaces() {
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            List<IfaceChoice> choices = new ArrayList<>();
            choices.add(IfaceChoice.AUTO);
            for (NetworkInterface nif : MulticastService.candidateInterfaces()) {
                choices.add(new IfaceChoice(nif, MulticastService.describe(nif)));
            }
            FxThread.run(() -> {
                IfaceChoice selected = interfaceCombo.getValue();
                interfaceCombo.getItems().setAll(choices);
                selectInterface(selected == null ? "" : selected.name());
            });
        });
    }

    private void selectInterface(String name) {
        for (IfaceChoice c : interfaceCombo.getItems()) {
            if (c.name().equals(name)) {
                interfaceCombo.setValue(c);
                return;
            }
        }
        interfaceCombo.getSelectionModel().selectFirst();
    }

    private boolean applyConfig() {
        try {
            service.setGroup(Fields.requireText(groupField, "Group address"));
            service.setPort(Fields.port(portField, "Port"));
            IfaceChoice c = interfaceCombo.getValue();
            service.setInterfaceName(c == null ? null : c.name());
            service.setTtl(Fields.intValue(ttlField, "TTL", 0, 255));
            service.setLoopback(loopbackCheck.isSelected());
            return true;
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return false;
        }
    }

    private void toggleJoin() {
        if (service.statusProperty().get().isActive()) {
            service.stop();
            return;
        }
        if (applyConfig()) {
            service.start();
        }
    }

    private void send(byte[] data) {
        if (service.statusProperty().get().isActive() || applyConfig()) {
            service.send(data);
        }
    }

    private void updateJoinButton(ModuleStatus s) {
        boolean active = s != null && s.isActive();
        joinButton.setText(active ? "Leave group" : "Join group");
        joinButton.setGraphic(new FontIcon(active ? "fth-square" : "fth-play"));
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
        m.put("group", Fields.text(groupField));
        m.put("port", Fields.text(portField));
        IfaceChoice c = interfaceCombo.getValue();
        m.put("interface", c == null ? "" : c.name());
        m.put("ttl", Fields.text(ttlField));
        m.put("loopback", Boolean.toString(loopbackCheck.isSelected()));
        m.putAll(sendBarController.captureFields());
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        Fields.apply(fields, "group", groupField);
        Fields.apply(fields, "port", portField);
        if (fields.containsKey("interface")) {
            selectInterface(fields.get("interface"));
        }
        Fields.apply(fields, "ttl", ttlField);
        Fields.apply(fields, "loopback", loopbackCheck);
        sendBarController.applyFields(fields);
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
