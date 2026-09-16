package com.linkscope.app;

import java.util.List;

/**
 * The ordered list of modules the shell shows in its sidebar. Adding a module means
 * adding one line here plus its FXML/Controller/Service triplet; MainController never
 * special-cases any module.
 */
public final class ModuleRegistry {

    /**
     * One sidebar entry: preset/module id, display title, sidebar group heading, FXML file
     * under {@code /com/linkscope/fxml/}, Ikonli icon literal.
     */
    public record ModuleDescriptor(String id, String title, String group, String fxml, String icon) {
    }

    public static final List<ModuleDescriptor> MODULES = List.of(
            new ModuleDescriptor("udp", "UDP", "Sockets", "udp.fxml", "fth-zap"),
            new ModuleDescriptor("tcp", "TCP", "Sockets", "tcp.fxml", "fth-link"),
            new ModuleDescriptor("multicast", "Multicast", "Sockets", "multicast.fxml", "fth-radio"),
            new ModuleDescriptor("nats", "NATS", "Brokers", "nats.fxml", "fth-message-circle"),
            new ModuleDescriptor("kafka", "Kafka", "Brokers", "kafka.fxml", "fth-layers"),
            new ModuleDescriptor("mqtt", "MQTT", "Brokers", "mqtt.fxml", "fth-rss"),
            new ModuleDescriptor("serial", "Serial", "Devices", "serial.fxml", "fth-cpu"),
            new ModuleDescriptor("modbus", "Modbus", "Devices", "modbus.fxml", "fth-sliders"),
            new ModuleDescriptor("http", "HTTP", "Web", "http.fxml", "fth-globe"),
            new ModuleDescriptor("websocket", "WebSocket", "Web", "websocket.fxml", "fth-repeat"),
            new ModuleDescriptor("ssh", "SSH", "Remote", "ssh.fxml", "fth-terminal"),
            new ModuleDescriptor("decoder", "Decoder", "Tools", "decoder.fxml", "fth-columns"),
            new ModuleDescriptor("netscan", "Net Scan", "Tools", "netscan.fxml", "fth-search"),
            new ModuleDescriptor("ports", "Ports", "Tools", "portscan.fxml", "fth-server")
    );

    private ModuleRegistry() {
    }
}
