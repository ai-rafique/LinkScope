package com.linkscope.modules.kafka;

import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.modules.pubsub.PubSubPanelConfig;
import com.linkscope.modules.pubsub.PubSubPanelController;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kafka tab: the shared pub/sub panel bound to a {@link KafkaService}, plus the three
 * Kafka-only options (consumer group id, partition selector,
 * start-from-beginning) added to the panel's options row.
 */
public class KafkaController implements ModuleController {
    public static final String ID = "kafka";

    static final PubSubPanelConfig CONFIG = new PubSubPanelConfig(
            "localhost:9092",
            "topic",
            "",
            "Quick test: docker compose up -d starts Redpanda on localhost:9092. Connect, subscribe to "
                    + "linkscope.test (the topic is created if missing), publish — the message comes back "
                    + "with its partition and offset.");

    @FXML private PubSubPanelController panelController;

    private final KafkaService service = new KafkaService();
    private final TextField groupIdField = new TextField();
    private final TextField partitionField = new TextField();
    private final CheckBox fromBeginningCheck = new CheckBox("From beginning");

    @FXML
    private void initialize() {
        panelController.bind(service, CONFIG);
        panelController.applyFields(Map.of("publishTopic", "linkscope.test"));

        groupIdField.setPromptText("auto (per session)");
        groupIdField.setPrefWidth(180);
        groupIdField.setTooltip(new Tooltip("Consumer group id. Blank = a random id for this session, so repeated "
                + "test runs never fight over committed offsets."));
        groupIdField.textProperty().addListener((obs, old, now) -> service.setGroupId(now));

        partitionField.setPromptText("auto");
        partitionField.setPrefWidth(70);
        partitionField.setTooltip(new Tooltip("Partition to publish to. Blank lets the client choose."));
        partitionField.textProperty().addListener((obs, old, now) -> service.setPartition(parsePartition(now)));

        fromBeginningCheck.setTooltip(new Tooltip("Start new subscriptions at the earliest retained offset "
                + "(auto.offset.reset=earliest) instead of only new messages. Applies on next connect."));
        fromBeginningCheck.selectedProperty().addListener((obs, old, now) -> service.setFromBeginning(now));

        panelController.addOption("Group id", groupIdField, false);
        panelController.addOption("Partition", partitionField, true);
        panelController.addOption("", fromBeginningCheck, false);
    }

    private static Integer parsePartition(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            int p = Integer.parseInt(text.trim());
            return p < 0 ? null : p;
        } catch (NumberFormatException e) {
            return null;
        }
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
        m.put("groupId", Fields.text(groupIdField));
        m.put("partition", Fields.text(partitionField));
        m.put("fromBeginning", Boolean.toString(fromBeginningCheck.isSelected()));
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        panelController.applyFields(fields);
        Fields.apply(fields, "groupId", groupIdField);
        Fields.apply(fields, "partition", partitionField);
        Fields.apply(fields, "fromBeginning", fromBeginningCheck);
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
