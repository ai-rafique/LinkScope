package com.linkscope.modules.pubsub;

import com.linkscope.core.TransportModule;
import javafx.collections.ObservableList;

import java.time.Duration;

/**
 * Shared broker pub/sub contract for NATS, Kafka and MQTT: connect to broker(s),
 * subscribe to named topics/subjects, publish. The shared {@code pubsub-panel.fxml}
 * binds to this interface, so only the service implementation differs per broker.
 */
public interface PubSubModule extends TransportModule {

    /** Broker list in {@code host:port,host:port} form (scheme optional). Connects asynchronously. */
    void connectToBroker(String brokerList);

    /** NATS subject / Kafka topic / MQTT topic. Remembered and re-applied after reconnects. */
    void subscribe(String topic);

    void unsubscribe(String topic);

    void publish(String topic, byte[] payload);

    /** Received messages (and request replies), newest last, mutated on the FX thread. */
    ObservableList<PubSubMessage> messages();

    /** Topics currently subscribed (or pending until connected), mutated on the FX thread. */
    ObservableList<String> subscriptions();

    /** Whether {@link #request} is meaningful for this broker (NATS yes, Kafka/MQTT no). */
    default boolean supportsRequestReply() {
        return false;
    }

    /** Publishes and waits (off the FX thread) for one reply, logging it as RX. */
    default void request(String topic, byte[] payload, Duration timeout) {
        throw new UnsupportedOperationException(moduleName() + " has no request/reply");
    }
}
