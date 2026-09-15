package com.linkscope.modules.kafka;

import com.linkscope.modules.pubsub.PubSubModule;
import com.linkscope.modules.pubsub.PubSubModuleContractTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.linkscope.TestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the shared PubSubModule contract against Kafka (Redpanda locally), plus Kafka-only metadata checks. */
class KafkaServiceTest extends PubSubModuleContractTest {

    static String broker() {
        String env = System.getenv("KAFKA_BROKERS");
        return env == null || env.isBlank() ? "localhost:9092" : env;
    }

    @Override
    protected PubSubModule createModule() {
        return new KafkaService();
    }

    @Override
    protected String brokerList() {
        return broker();
    }

    @Override
    protected String testTopic() {
        return "linkscope-test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void receivedMessagesCarryPartitionAndOffset() {
        String topic = testTopic();
        module.subscribe(topic);
        awaitTrue("subscribed", java.time.Duration.ofSeconds(20), () -> com.linkscope.TestSupport.snapshot(com.linkscope.core.LogSink.get().entries()).stream()
                .anyMatch(e -> e.note() != null && e.note().startsWith("Subscribed to " + topic)));
        byte[] payload = "with-meta".getBytes(StandardCharsets.UTF_8);
        module.publish(topic, payload);
        awaitTrue("received", java.time.Duration.ofSeconds(10), () -> !module.messages().isEmpty());
        String meta = com.linkscope.TestSupport.snapshot(module.messages()).get(0).metadata();
        assertTrue(meta.matches("p\\d+ o\\d+.*"), meta);
        assertEquals(topic, com.linkscope.TestSupport.snapshot(module.messages()).get(0).topic());
    }

    @Test
    void bootstrapParsingStripsSchemesAndTrims() {
        assertEquals("localhost:9092,other:9093", KafkaService.toBootstrapServers(" kafka://localhost:9092 , other:9093,"));
        assertThrows(IllegalArgumentException.class, () -> KafkaService.toBootstrapServers(" , "));
    }
}
