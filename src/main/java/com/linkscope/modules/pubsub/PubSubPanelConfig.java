package com.linkscope.modules.pubsub;

/**
 * Per-broker wording for the shared pub/sub panel. Everything else about the panel is
 * identical across NATS, Kafka and MQTT.
 *
 * @param brokerPrompt  placeholder for the broker field, e.g. {@code localhost:4222}
 * @param topicNoun     "subject" for NATS, "topic" for Kafka/MQTT
 * @param wildcardHint  tooltip text describing wildcard syntax, or empty when unsupported
 * @param hint          empty-state / quick-start sentence shown under the panel
 */
public record PubSubPanelConfig(String brokerPrompt, String topicNoun, String wildcardHint, String hint) {

    public String topicNounCapitalized() {
        return Character.toUpperCase(topicNoun.charAt(0)) + topicNoun.substring(1);
    }
}
