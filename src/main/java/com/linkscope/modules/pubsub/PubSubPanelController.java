package com.linkscope.modules.pubsub;

import com.linkscope.core.ModuleStatus;
import com.linkscope.core.PayloadCodec;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.SendBarController;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller for {@code pubsub-panel.fxml}: broker field + connect, subscription list,
 * publish box (with optional request/reply) and a message table. Each broker's
 * controller includes this fragment and calls {@link #bind}.
 */
public class PubSubPanelController {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int PREVIEW_CHARS = 200;

    @FXML private Label brokerLabel;
    @FXML private TextField brokerField;
    @FXML private Button connectButton;
    @FXML private StatusBadge statusBadge;

    @FXML private Label subscriptionsTitle;
    @FXML private TextField subscribeField;
    @FXML private Button subscribeButton;
    @FXML private ListView<String> subscriptionsList;
    @FXML private Button unsubscribeButton;

    @FXML private Label publishTopicLabel;
    @FXML private TextField publishTopicField;
    @FXML private HBox requestBox;
    @FXML private CheckBox requestReplyCheck;
    @FXML private TextField timeoutField;
    @FXML private SendBarController sendBarController;

    @FXML private TableView<PubSubMessage> messagesTable;
    @FXML private TableColumn<PubSubMessage, String> timeColumn;
    @FXML private TableColumn<PubSubMessage, String> topicColumn;
    @FXML private TableColumn<PubSubMessage, String> sizeColumn;
    @FXML private TableColumn<PubSubMessage, String> payloadColumn;
    @FXML private TableColumn<PubSubMessage, String> metadataColumn;
    @FXML private Button clearMessagesButton;
    @FXML private Label hintLabel;

    private PubSubModule service;

    @FXML
    private void initialize() {
        timeColumn.setCellValueFactory(c -> new SimpleStringProperty(TIME.format(c.getValue().receivedAt())));
        topicColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().topic()));
        sizeColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().payload().length + " B"));
        payloadColumn.setCellValueFactory(c -> new SimpleStringProperty(preview(c.getValue().payload())));
        metadataColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().metadata()));
        messagesTable.setPlaceholder(new Label("No messages yet."));
        messagesTable.setContextMenu(messageMenu());

        connectButton.setOnAction(e -> toggleConnect());
        subscribeButton.setOnAction(e -> subscribe());
        subscribeField.setOnAction(e -> subscribe());
        unsubscribeButton.setOnAction(e -> unsubscribe());
        unsubscribeButton.disableProperty().bind(subscriptionsList.getSelectionModel().selectedItemProperty().isNull());
        clearMessagesButton.setOnAction(e -> {
            if (service != null) {
                service.messages().clear();
            }
        });
        sendBarController.setOnSend(this::publish);
    }

    /** Wires the panel to a broker service. Call once from the including controller. */
    public void bind(PubSubModule module, PubSubPanelConfig config) {
        this.service = module;
        brokerField.setPromptText(config.brokerPrompt());
        brokerField.setText(config.brokerPrompt());
        brokerField.setTooltip(new Tooltip("Comma-separated host:port list"));
        subscriptionsTitle.setText("Subscriptions");
        subscribeField.setPromptText(config.topicNoun() + " to subscribe");
        if (!config.wildcardHint().isEmpty()) {
            subscribeField.setTooltip(new Tooltip(config.wildcardHint()));
        }
        publishTopicLabel.setText(config.topicNounCapitalized());
        publishTopicField.setPromptText(config.topicNoun() + " to publish to");
        hintLabel.setText(config.hint());

        statusBadge.statusProperty().bind(module.statusProperty());
        BooleanBinding active = Bindings.createBooleanBinding(
                () -> module.statusProperty().get().isActive(), module.statusProperty());
        brokerField.disableProperty().bind(active);
        BooleanBinding notConnected = module.statusProperty().isNotEqualTo(ModuleStatus.CONNECTED);
        sendBarController.sendDisabledProperty().bind(notConnected);

        boolean rr = module.supportsRequestReply();
        requestBox.setVisible(rr);
        requestBox.setManaged(rr);

        subscriptionsList.setItems(module.subscriptions());
        messagesTable.setItems(module.messages());
        module.messages().addListener((ListChangeListener<PubSubMessage>) change -> {
            if (!module.messages().isEmpty()) {
                messagesTable.scrollTo(module.messages().size() - 1);
            }
        });
        module.statusProperty().addListener((obs, old, now) -> updateConnectButton(now));
        updateConnectButton(module.statusProperty().get());
    }

    private void toggleConnect() {
        if (service.statusProperty().get().isActive()) {
            service.stop();
            return;
        }
        String brokers = Fields.text(brokerField);
        if (brokers.isEmpty()) {
            Toasts.error("Broker list is required");
            return;
        }
        service.connectToBroker(brokers);
    }

    private void subscribe() {
        String topic = Fields.text(subscribeField);
        if (topic.isEmpty()) {
            Toasts.warning("Type a " + publishTopicLabel.getText().toLowerCase() + " first");
            return;
        }
        service.subscribe(topic);
        subscribeField.clear();
    }

    private void unsubscribe() {
        String topic = subscriptionsList.getSelectionModel().getSelectedItem();
        if (topic != null) {
            service.unsubscribe(topic);
        }
    }

    private void publish(byte[] data) {
        String topic = Fields.text(publishTopicField);
        if (topic.isEmpty()) {
            Toasts.error(publishTopicLabel.getText() + " to publish to is required");
            return;
        }
        if (requestReplyCheck.isVisible() && requestReplyCheck.isSelected()) {
            int ms;
            try {
                ms = Fields.intValue(timeoutField, "Timeout", 1, 600_000);
            } catch (IllegalArgumentException ex) {
                Toasts.error(ex.getMessage());
                return;
            }
            service.request(topic, data, Duration.ofMillis(ms));
        } else {
            service.publish(topic, data);
        }
    }

    private void updateConnectButton(ModuleStatus s) {
        boolean active = s != null && s.isActive();
        connectButton.setText(active ? "Disconnect" : "Connect");
        connectButton.setGraphic(new FontIcon(active ? "fth-square" : "fth-play"));
    }

    private static String preview(byte[] payload) {
        String text = PayloadCodec.toAscii(payload);
        return text.length() > PREVIEW_CHARS ? text.substring(0, PREVIEW_CHARS) + "…" : text;
    }

    private ContextMenu messageMenu() {
        MenuItem copyAscii = new MenuItem("Copy payload as ASCII");
        copyAscii.setOnAction(e -> copySelected(false));
        MenuItem copyHex = new MenuItem("Copy payload as hex");
        copyHex.setOnAction(e -> copySelected(true));
        MenuItem useTopic = new MenuItem("Use topic for publish");
        useTopic.setOnAction(e -> {
            PubSubMessage m = messagesTable.getSelectionModel().getSelectedItem();
            if (m != null) {
                publishTopicField.setText(m.topic());
            }
        });
        return new ContextMenu(copyAscii, copyHex, useTopic);
    }

    private void copySelected(boolean hex) {
        PubSubMessage m = messagesTable.getSelectionModel().getSelectedItem();
        if (m == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(PayloadCodec.format(m.payload(), hex));
        Clipboard.getSystemClipboard().setContent(content);
    }

    // --- presets --------------------------------------------------------------------

    public Map<String, String> captureFields() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("brokers", Fields.text(brokerField));
        m.put("subscriptions", String.join(",", service == null ? java.util.List.of() : service.subscriptions()));
        m.put("publishTopic", Fields.text(publishTopicField));
        m.put("requestReply", Boolean.toString(requestReplyCheck.isSelected()));
        m.put("requestTimeoutMs", Fields.text(timeoutField));
        m.putAll(sendBarController.captureFields());
        return m;
    }

    public void applyFields(Map<String, String> fields) {
        Fields.apply(fields, "brokers", brokerField);
        Fields.apply(fields, "publishTopic", publishTopicField);
        Fields.apply(fields, "requestReply", requestReplyCheck);
        Fields.apply(fields, "requestTimeoutMs", timeoutField);
        String subs = fields.get("subscriptions");
        if (subs != null && service != null) {
            for (String existing : java.util.List.copyOf(service.subscriptions())) {
                service.unsubscribe(existing);
            }
            for (String topic : subs.split(",")) {
                if (!topic.isBlank()) {
                    service.subscribe(topic.trim());
                }
            }
        }
        sendBarController.applyFields(fields);
    }
}
