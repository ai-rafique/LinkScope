package com.linkscope.modules.pubsub;

import java.time.LocalTime;

/** One received broker message: topic it arrived on, raw payload, broker-specific metadata. */
public record PubSubMessage(LocalTime receivedAt, String topic, byte[] payload, String metadata) {

    public static PubSubMessage now(String topic, byte[] payload, String metadata) {
        return new PubSubMessage(LocalTime.now(), topic, payload, metadata == null ? "" : metadata);
    }
}
