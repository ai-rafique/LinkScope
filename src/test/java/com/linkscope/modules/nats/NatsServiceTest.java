package com.linkscope.modules.nats;

import com.linkscope.core.LogEntry;
import com.linkscope.modules.pubsub.PubSubModule;
import com.linkscope.modules.pubsub.PubSubModuleContractTest;
import io.nats.client.Dispatcher;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static com.linkscope.TestSupport.awaitTrue;
import static com.linkscope.TestSupport.logged;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the shared PubSubModule contract against NATS, plus NATS-only request/reply and URL parsing. */
class NatsServiceTest extends PubSubModuleContractTest {

    static String broker() {
        String env = System.getenv("NATS_SERVERS");
        return env == null || env.isBlank() ? "localhost:4222" : env;
    }

    @Override
    protected PubSubModule createModule() {
        return new NatsService();
    }

    @Override
    protected String brokerList() {
        return broker();
    }

    @Override
    protected String testTopic() {
        return "linkscope.test." + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void wildcardSubscriptionReceivesChildSubjects() {
        String base = testTopic();
        module.subscribe(base + ".>");
        awaitTrue("subscribed", () -> module.subscriptions().contains(base + ".>"));
        byte[] payload = "deep".getBytes(StandardCharsets.UTF_8);
        awaitTrue("wildcard delivery", () -> {
            module.publish(base + ".a.b", payload);
            return com.linkscope.TestSupport.snapshot(module.messages()).stream().anyMatch(m -> m.topic().equals(base + ".a.b"));
        });
    }

    @Test
    void requestReplyRoundTrip() {
        NatsService nats = (NatsService) module;
        String subject = testTopic();
        Dispatcher responder = nats.connection().createDispatcher(msg ->
                nats.connection().publish(msg.getReplyTo(), ("echo:" + new String(msg.getData(), StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8)));
        responder.subscribe(subject);

        byte[] question = "ping".getBytes(StandardCharsets.UTF_8);
        byte[] expected = "echo:ping".getBytes(StandardCharsets.UTF_8);
        nats.request(subject, question, Duration.ofSeconds(3));
        awaitTrue("reply recorded", () -> com.linkscope.TestSupport.snapshot(module.messages()).stream()
                .anyMatch(m -> m.metadata().startsWith("reply to " + subject)));
        assertArrayEquals(expected, com.linkscope.TestSupport.snapshot(module.messages()).get(0).payload());
        assertTrue(logged(NatsService.TAG, LogEntry.Kind.RX, expected));
    }

    @Test
    void requestTimeoutIsReportedAsError() {
        String subject = testTopic();
        module.request(subject, new byte[] {1}, Duration.ofMillis(200));
        awaitTrue("timeout error logged", () -> com.linkscope.TestSupport.snapshot(com.linkscope.core.LogSink.get().entries()).stream()
                .anyMatch(e -> e.kind() == LogEntry.Kind.ERROR && e.note().contains("No reply on " + subject)));
    }

}
