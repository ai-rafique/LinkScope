package com.linkscope.modules.mqtt;

import com.linkscope.core.AbstractService;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleStatus;
import com.linkscope.modules.pubsub.PubSubMessage;
import com.linkscope.modules.pubsub.PubSubModule;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MQTT v5 via the Eclipse Paho client, against the shared {@link PubSubModule} contract.
 * Topic filters support {@code +} and {@code #}; QoS applies to subscribe and publish;
 * retained publishes are flagged and retained receipts are tagged {@code [RETAINED]} in
 * the log. MQTT is single-broker: a comma list uses the first entry with a warning.
 */
public final class MqttService extends AbstractService implements PubSubModule {
    public static final String TAG = "MQTT";
    public static final int MAX_MESSAGES = 5_000;
    private static final int CONNECT_TIMEOUT_S = 5;
    private static final long OP_TIMEOUT_MS = 5_000;

    private final ObservableList<PubSubMessage> messages = FXCollections.observableArrayList();
    private final ObservableList<String> subscriptions = FXCollections.observableArrayList();
    private final List<String> wanted = new ArrayList<>();

    private volatile String brokerList = "localhost:1883";
    private volatile String clientId = "";
    private volatile int subscribeQos;
    private volatile int publishQos;
    private volatile boolean retain;
    private volatile String defaultTopic = "linkscope/test";

    private volatile MqttAsyncClient client;
    private volatile boolean closing;

    @Override
    public String moduleName() {
        return TAG;
    }

    @Override
    public ObservableList<PubSubMessage> messages() {
        return messages;
    }

    @Override
    public ObservableList<String> subscriptions() {
        return subscriptions;
    }

    // --- configuration --------------------------------------------------------------

    /** Client id; blank means a generated {@code linkscope-xxxxxxxx}. */
    public void setClientId(String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public void setSubscribeQos(int qos) {
        this.subscribeQos = requireQos(qos);
    }

    public void setPublishQos(int qos) {
        this.publishQos = requireQos(qos);
    }

    public void setRetain(boolean retain) {
        this.retain = retain;
    }

    public void setDefaultTopic(String topic) {
        this.defaultTopic = topic;
    }

    private static int requireQos(int qos) {
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("QoS must be 0, 1 or 2");
        }
        return qos;
    }

    /** First entry of the list as a {@code tcp://host:port} URI (scheme kept if given). */
    static String toServerUri(String brokerList) {
        for (String raw : brokerList.split(",")) {
            String s = raw.trim();
            if (!s.isEmpty()) {
                return s.contains("://") ? s : "tcp://" + s;
            }
        }
        throw new IllegalArgumentException("Broker is required");
    }

    static int entryCount(String brokerList) {
        int n = 0;
        for (String raw : brokerList.split(",")) {
            if (!raw.isBlank()) {
                n++;
            }
        }
        return n;
    }

    // --- lifecycle ------------------------------------------------------------------

    @Override
    public void connectToBroker(String brokers) {
        this.brokerList = brokers;
        start();
    }

    @Override
    public void start() {
        if (client != null || status() == ModuleStatus.CONNECTING) {
            return;
        }
        closing = false;
        setStatus(ModuleStatus.CONNECTING);
        exec.submit(this::connect);
    }

    private void connect() {
        MqttAsyncClient c = null;
        String uri;
        try {
            uri = toServerUri(brokerList);
        } catch (IllegalArgumentException e) {
            logError(e.getMessage());
            setStatus(ModuleStatus.ERROR);
            return;
        }
        if (entryCount(brokerList) > 1) {
            logError("MQTT is single-broker — using " + uri + " and ignoring the rest of the list");
        }
        String id = clientId.isEmpty() ? "linkscope-" + UUID.randomUUID().toString().substring(0, 8) : clientId;
        try {
            c = new MqttAsyncClient(uri, id, new MemoryPersistence());
            c.setCallback(new Callback());
            MqttConnectionOptions options = new MqttConnectionOptions();
            options.setAutomaticReconnect(true);
            options.setCleanStart(true);
            options.setConnectionTimeout(CONNECT_TIMEOUT_S);
            options.setKeepAliveInterval(30);
            c.connect(options).waitForCompletion(CONNECT_TIMEOUT_S * 1000L + 1000);
            client = c;
            logInfo("Connected to " + uri + " as " + id);
            setStatus(ModuleStatus.CONNECTED);
            resubscribeAll(c);
        } catch (MqttException | IllegalArgumentException e) {
            logError("Connect to " + uri + " failed", e);
            setStatus(ModuleStatus.ERROR);
            if (c != null) {
                closeQuietly(c);
            }
        }
    }

    @Override
    public void stop() {
        MqttAsyncClient c = client;
        client = null;
        if (c == null) {
            if (status() != ModuleStatus.DISCONNECTED) {
                setStatus(ModuleStatus.DISCONNECTED);
            }
            return;
        }
        closing = true;
        exec.submit(() -> {
            try {
                if (c.isConnected()) {
                    c.disconnect().waitForCompletion(OP_TIMEOUT_MS);
                }
            } catch (MqttException e) {
                logError("Disconnect error", e);
            } finally {
                closeQuietly(c);
                logInfo("Disconnected");
                setStatus(ModuleStatus.DISCONNECTED);
            }
        });
    }

    private static void closeQuietly(MqttAsyncClient c) {
        try {
            c.close(true);
        } catch (MqttException ignored) {
            // closing
        }
    }

    // --- pub/sub --------------------------------------------------------------------

    @Override
    public void subscribe(String topic) {
        synchronized (wanted) {
            if (wanted.contains(topic)) {
                return;
            }
            wanted.add(topic);
        }
        FxThread.run(() -> {
            if (!subscriptions.contains(topic)) {
                subscriptions.add(topic);
            }
        });
        MqttAsyncClient c = client;
        if (c != null) {
            exec.submit(() -> doSubscribe(c, topic));
        } else {
            logInfo("Will subscribe to " + topic + " once connected");
        }
    }

    private void doSubscribe(MqttAsyncClient c, String topic) {
        int qos = subscribeQos;
        try {
            c.subscribe(topic, qos).waitForCompletion(OP_TIMEOUT_MS);
            logInfo("Subscribed to " + topic + " (QoS " + qos + ")");
        } catch (MqttException | IllegalArgumentException e) {
            logError("Subscribe to " + topic + " failed", e);
        }
    }

    private void resubscribeAll(MqttAsyncClient c) {
        List<String> topics;
        synchronized (wanted) {
            topics = List.copyOf(wanted);
        }
        for (String t : topics) {
            doSubscribe(c, t);
        }
    }

    @Override
    public void unsubscribe(String topic) {
        synchronized (wanted) {
            wanted.remove(topic);
        }
        FxThread.run(() -> subscriptions.remove(topic));
        MqttAsyncClient c = client;
        if (c != null) {
            exec.submit(() -> {
                try {
                    c.unsubscribe(topic).waitForCompletion(OP_TIMEOUT_MS);
                    logInfo("Unsubscribed from " + topic);
                } catch (MqttException e) {
                    logError("Unsubscribe from " + topic + " failed", e);
                }
            });
        }
    }

    @Override
    public void publish(String topic, byte[] payload) {
        MqttAsyncClient c = client;
        if (c == null) {
            logError("Not connected — connect first");
            return;
        }
        int qos = publishQos;
        boolean retained = retain;
        exec.submit(() -> {
            try {
                MqttMessage m = new MqttMessage(payload);
                m.setQos(qos);
                m.setRetained(retained);
                c.publish(topic, m).waitForCompletion(OP_TIMEOUT_MS);
                log.tx(TAG, payload, topic + " (qos " + qos + (retained ? ", retain)" : ")"));
            } catch (MqttException | IllegalArgumentException e) {
                logError("Publish to " + topic + " failed", e);
            }
        });
    }

    @Override
    public void send(byte[] payload) {
        publish(defaultTopic, payload);
    }

    private void record(String topic, MqttMessage message) {
        byte[] data = message.getPayload() == null ? new byte[0] : message.getPayload();
        StringBuilder meta = new StringBuilder("qos ").append(message.getQos());
        if (message.isRetained()) {
            meta.append(", RETAINED");
        }
        MqttProperties props = message.getProperties();
        if (props != null && props.getResponseTopic() != null) {
            meta.append(", response-topic ").append(props.getResponseTopic());
        }
        PubSubMessage m = PubSubMessage.now(topic, data, meta.toString());
        FxThread.run(() -> {
            messages.add(m);
            if (messages.size() > MAX_MESSAGES) {
                messages.remove(0, messages.size() - MAX_MESSAGES);
            }
        });
        log.rx(TAG, data, (message.isRetained() ? "[RETAINED] " : "") + topic + " (" + meta + ")");
    }

    private final class Callback implements MqttCallback {
        @Override
        public void disconnected(MqttDisconnectResponse response) {
            if (closing || client == null) {
                return;
            }
            String reason = response.getReasonString() != null ? response.getReasonString()
                    : response.getException() != null ? response.getException().getMessage() : "connection lost";
            logError("Disconnected: " + reason + " — reconnecting");
            setStatus(ModuleStatus.CONNECTING);
        }

        @Override
        public void mqttErrorOccurred(MqttException exception) {
            if (!closing) {
                logError("Client error", exception);
            }
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            record(topic, message);
        }

        @Override
        public void deliveryComplete(IMqttToken token) {
            // publish() waits on the token itself
        }

        @Override
        public void connectComplete(boolean reconnect, String serverUri) {
            if (!reconnect) {
                return; // initial connect is reported by connect()
            }
            logInfo("Reconnected to " + serverUri);
            setStatus(ModuleStatus.CONNECTED);
            MqttAsyncClient c = client;
            if (c != null) {
                exec.submit(() -> resubscribeAll(c));
            }
        }

        @Override
        public void authPacketArrived(int reasonCode, MqttProperties properties) {
            // no enhanced auth in v1
        }
    }
}
