package com.linkscope.modules.nats;

import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.modules.pubsub.PubSubPanelConfig;
import com.linkscope.modules.pubsub.PubSubPanelController;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;

import java.util.Map;

/** NATS tab: the shared pub/sub panel bound to a {@link NatsService}. */
public class NatsController implements ModuleController {
    public static final String ID = "nats";

    static final PubSubPanelConfig CONFIG = new PubSubPanelConfig(
            "localhost:4222",
            "subject",
            "Wildcards: * matches one token (orders.*), > matches the rest (orders.>)",
            "Quick test: Connect (docker compose up -d starts a local server), subscribe to linkscope.>, "
                    + "publish to linkscope.test — your own message comes back in the table and as RX in the log.");

    @FXML private PubSubPanelController panelController;

    private final NatsService service = new NatsService();

    @FXML
    private void initialize() {
        panelController.bind(service, CONFIG);
        panelController.applyFields(Map.of("publishTopic", "linkscope.test"));
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
        return panelController.captureFields();
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        panelController.applyFields(fields);
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
