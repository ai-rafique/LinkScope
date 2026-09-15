package com.linkscope.modules.pubsub;

import com.linkscope.TestSupport;
import com.linkscope.core.LogEntry;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static com.linkscope.TestSupport.awaitTrue;
import static com.linkscope.TestSupport.logged;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One contract suite for every {@link PubSubModule} (NATS now, Kafka/MQTT later):
 * connect, subscribe, publish, receive, unsubscribe. Integration-only: skipped when
 * SKIP_INTEGRATION is set or the broker from the Docker Compose stack is not reachable.
 */
public abstract class PubSubModuleContractTest {
    /** Kafka group joins can take several seconds; NATS is instant. */
    protected static final Duration SUBSCRIBE_TIMEOUT = Duration.ofSeconds(20);
    protected static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);

    protected PubSubModule module;

    protected abstract PubSubModule createModule();

    protected abstract String brokerList();

    /** A topic unique to the run so parallel test runs do not cross-talk. */
    protected abstract String testTopic();

    @BeforeEach
    void connect() {
        Assumptions.assumeFalse(TestSupport.integrationSkipped(), "SKIP_INTEGRATION set");
        LogSink.get().clear();
        module = createModule();
        module.connectToBroker(brokerList());
        awaitTrue("connected or failed", () -> module.status() == ModuleStatus.CONNECTED
                || module.status() == ModuleStatus.ERROR);
        Assumptions.assumeTrue(module.status() == ModuleStatus.CONNECTED,
                "broker " + brokerList() + " not reachable:\n" + TestSupport.dumpLog());
    }

    @AfterEach
    void disconnect() {
        if (module != null) {
            module.stop();
            if (module.status() != ModuleStatus.ERROR) {
                awaitTrue("disconnected", () -> module.status() == ModuleStatus.DISCONNECTED);
            }
        }
    }

    @Test
    void subscribePublishReceive() {
        String topic = testTopic();
        module.subscribe(topic);
        awaitTrue("subscription listed", () -> module.subscriptions().contains(topic));
        awaitTrue("subscribe acknowledged", SUBSCRIBE_TIMEOUT, () -> loggedInfoContaining("Subscribed to " + topic));

        byte[] payload = ("hello " + topic).getBytes(StandardCharsets.UTF_8);
        module.publish(topic, payload);
        awaitTrue("TX logged", DELIVERY_TIMEOUT, () -> loggedTxContaining(payload));
        awaitTrue("message received", DELIVERY_TIMEOUT, () -> module.messages().stream()
                .anyMatch(m -> m.topic().equals(topic) && Arrays.equals(m.payload(), payload)));
        awaitTrue("RX logged", () -> logged(module.moduleName(), LogEntry.Kind.RX, payload));
        assertEquals(1, module.messages().size());
    }

    @Test
    void unsubscribeStopsDelivery() throws InterruptedException {
        String topic = testTopic();
        module.subscribe(topic);
        awaitTrue("subscribe acknowledged", SUBSCRIBE_TIMEOUT, () -> loggedInfoContaining("Subscribed to " + topic));
        module.unsubscribe(topic);
        awaitTrue("unsubscribed", () -> !module.subscriptions().contains(topic)
                && loggedInfoContaining("Unsubscribed from " + topic));

        byte[] payload = "should not arrive".getBytes(StandardCharsets.UTF_8);
        module.publish(topic, payload);
        awaitTrue("TX logged", DELIVERY_TIMEOUT, () -> loggedTxContaining(payload));
        Thread.sleep(1000);
        assertTrue(module.messages().isEmpty(), "no message after unsubscribe");
    }

    /** TX notes may carry broker metadata (Kafka partition/offset), so match on payload only. */
    private boolean loggedTxContaining(byte[] payload) {
        return logged(module.moduleName(), LogEntry.Kind.TX, payload);
    }

    private static boolean loggedInfoContaining(String text) {
        for (LogEntry e : LogSink.get().entries().toArray(new LogEntry[0])) {
            if (e.kind() == LogEntry.Kind.INFO && e.note() != null && e.note().contains(text)) {
                return true;
            }
        }
        return false;
    }
}
