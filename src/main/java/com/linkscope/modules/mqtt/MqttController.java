package com.linkscope.modules.mqtt;

import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.modules.pubsub.PubSubPanelConfig;
import com.linkscope.modules.pubsub.PubSubPanelController;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MQTT tab: the shared pub/sub panel bound to an {@link MqttService}, plus the MQTT-only
 * options from CLAUDE.md §2.4 (client id, subscribe/publish QoS, retain) in the panel's
 * options row.
 */
public class MqttController implements ModuleController {
    public static final String ID = "mqtt";

    static final PubSubPanelConfig CONFIG = new PubSubPanelConfig(
            "localhost:1883",
            "topic",
            "Wildcards: + matches one level (home/+/temp), # matches everything below (home/#)",
            "Quick test: docker compose up -d starts Mosquitto on localhost:1883. Connect, subscribe to "
                    + "linkscope/#, publish to linkscope/test — your own message comes back in the table and as RX "
                    + "in the log. Retained messages arrive tagged [RETAINED].");

    @FXML private PubSubPanelController panelController;

    private final MqttService service = new MqttService();
    private final TextField clientIdField = new TextField();
    private final ComboBox<Integer> subscribeQosCombo = new ComboBox<>();
    private final ComboBox<Integer> publishQosCombo = new ComboBox<>();
    private final CheckBox retainCheck = new CheckBox("Retain");

    @FXML
    private void initialize() {
        panelController.bind(service, CONFIG);
        panelController.applyFields(Map.of("publishTopic", "linkscope/test"));

        clientIdField.setPromptText("auto");
        clientIdField.setPrefWidth(160);
        clientIdField.setTooltip(new Tooltip("MQTT client id. Blank = generated linkscope-xxxxxxxx. "
                + "Two clients with the same id kick each other off the broker."));
        clientIdField.textProperty().addListener((obs, old, now) -> service.setClientId(now));

        subscribeQosCombo.getItems().setAll(0, 1, 2);
        subscribeQosCombo.setValue(0);
        subscribeQosCombo.setTooltip(new Tooltip("Max QoS for new subscriptions: 0 at most once, 1 at least once, 2 exactly once"));
        subscribeQosCombo.valueProperty().addListener((obs, old, now) -> service.setSubscribeQos(now));

        publishQosCombo.getItems().setAll(0, 1, 2);
        publishQosCombo.setValue(0);
        publishQosCombo.setTooltip(new Tooltip("QoS for published messages: 0 at most once, 1 at least once, 2 exactly once"));
        publishQosCombo.valueProperty().addListener((obs, old, now) -> service.setPublishQos(now));

        retainCheck.setTooltip(new Tooltip("Broker keeps the last retained message per topic and hands it to new subscribers. "
                + "Publish an empty retained payload to clear it."));
        retainCheck.selectedProperty().addListener((obs, old, now) -> service.setRetain(now));

        panelController.addOption("Client id", clientIdField, false);
        panelController.addOption("Sub QoS", subscribeQosCombo, true);
        panelController.addOption("Pub QoS", publishQosCombo, true);
        panelController.addOption("", retainCheck, true);
    }

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
        Map<String, String> m = new LinkedHashMap<>(panelController.captureFields());
        m.put("clientId", Fields.text(clientIdField));
        m.put("subscribeQos", String.valueOf(subscribeQosCombo.getValue()));
        m.put("publishQos", String.valueOf(publishQosCombo.getValue()));
        m.put("retain", Boolean.toString(retainCheck.isSelected()));
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        panelController.applyFields(fields);
        Fields.apply(fields, "clientId", clientIdField);
        applyQos(fields, "subscribeQos", subscribeQosCombo);
        applyQos(fields, "publishQos", publishQosCombo);
        Fields.apply(fields, "retain", retainCheck);
    }

    private static void applyQos(Map<String, String> fields, String key, ComboBox<Integer> combo) {
        String v = fields.get(key);
        if (v == null) {
            return;
        }
        try {
            int qos = Integer.parseInt(v.trim());
            if (qos >= 0 && qos <= 2) {
                combo.setValue(qos);
            }
        } catch (NumberFormatException ignored) {
            // keep current
        }
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
