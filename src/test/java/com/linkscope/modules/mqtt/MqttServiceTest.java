package com.linkscope.modules.mqtt;

import com.linkscope.core.LogEntry;
import com.linkscope.core.LogSink;
import com.linkscope.modules.pubsub.PubSubModule;
import com.linkscope.modules.pubsub.PubSubModuleContractTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.linkscope.TestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the shared PubSubModule contract against MQTT (Mosquitto locally), plus MQTT-only cases. */
class MqttServiceTest extends PubSubModuleContractTest {

    static String broker() {
        String env = System.getenv("MQTT_BROKER");
        return env == null || env.isBlank() ? "localhost:1883" : env;
    }

    @Override
    protected PubSubModule createModule() {
        return new MqttService();
    }

    @Override
    protected String brokerList() {
        return broker();
    }

    @Override
    protected String testTopic() {
        return "linkscope/test/" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void wildcardSubscriptionReceivesChildTopics() {
        String base = testTopic();
        module.subscribe(base + "/#");
        awaitTrue("subscribed", () -> loggedInfo("Subscribed to " + base + "/#"));
        byte[] payload = "deep".getBytes(StandardCharsets.UTF_8);
        module.publish(base + "/a/b", payload);
        awaitTrue("wildcard delivery", DELIVERY_TIMEOUT, () -> module.messages().stream()
                .anyMatch(m -> m.topic().equals(base + "/a/b")));
    }

    @Test
    void retainedMessageIsTaggedOnReceipt() {
        MqttService mqtt = (MqttService) module;
        String topic = testTopic();
        byte[] payload = "sticky".getBytes(StandardCharsets.UTF_8);
        mqtt.setRetain(true);
        mqtt.setPublishQos(1);
        module.publish(topic, payload);
        awaitTrue("retained publish acked", DELIVERY_TIMEOUT, () -> LogSink.get().entries().stream()
                .anyMatch(e -> e.kind() == LogEntry.Kind.TX && e.note().contains("retain")));

        module.subscribe(topic);
        awaitTrue("retained message delivered to late subscriber", DELIVERY_TIMEOUT, () -> !module.messages().isEmpty());
        assertEquals(topic, module.messages().get(0).topic());
        assertTrue(module.messages().get(0).metadata().contains("RETAINED"), module.messages().get(0).metadata());
        assertTrue(LogSink.get().entries().stream().anyMatch(e -> e.kind() == LogEntry.Kind.RX
                && e.note().startsWith("[RETAINED] " + topic)));

        // clear the retained message so the broker does not keep test residue
        module.publish(topic, new byte[0]);
        awaitTrue("clear acked", DELIVERY_TIMEOUT, () -> LogSink.get().entries().stream()
                .filter(e -> e.kind() == LogEntry.Kind.TX).count() >= 2);
    }

    @Test
    void serverUriParsing() {
        assertEquals("tcp://localhost:1883", MqttService.toServerUri(" localhost:1883 , other:1883"));
        assertEquals("ssl://secure:8883", MqttService.toServerUri("ssl://secure:8883"));
        assertEquals(2, MqttService.entryCount("a:1, b:2"));
    }

    private static boolean loggedInfo(String text) {
        return LogSink.get().entries().stream()
                .anyMatch(e -> e.kind() == LogEntry.Kind.INFO && e.note() != null && e.note().contains(text));
    }
}
