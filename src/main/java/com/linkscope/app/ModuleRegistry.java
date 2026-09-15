package com.linkscope.app;

import java.util.List;

/**
 * The ordered list of module tabs the shell creates at startup. Adding a module means
 * adding one line here plus its FXML/Controller/Service triplet; MainController never
 * special-cases any module.
 */
public final class ModuleRegistry {

    /** One tab: preset/module id, tab title, FXML file under {@code /com/linkscope/fxml/}, Ikonli icon literal. */
    public record ModuleDescriptor(String id, String title, String fxml, String icon) {
    }

    public static final List<ModuleDescriptor> MODULES = List.of(
            new ModuleDescriptor("udp", "UDP", "udp.fxml", "fth-zap"),
            new ModuleDescriptor("tcp", "TCP", "tcp.fxml", "fth-link"),
            new ModuleDescriptor("multicast", "Multicast", "multicast.fxml", "fth-radio"),
            new ModuleDescriptor("nats", "NATS", "nats.fxml", "fth-message-circle"),
            new ModuleDescriptor("kafka", "Kafka", "kafka.fxml", "fth-layers"),
            new ModuleDescriptor("mqtt", "MQTT", "mqtt.fxml", "fth-rss"),
            new ModuleDescriptor("serial", "Serial", "serial.fxml", "fth-cpu")
    );

    private ModuleRegistry() {
    }
}
