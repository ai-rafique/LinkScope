package com.linkscope.modules.capture;

import java.time.Instant;
import java.util.Map;

/**
 * One captured frame as shown in the table. {@code protocol} is the highest recognised
 * layer (ARP, ICMP, TCP, UDP, USB, ...), {@code app} a guess from well-known ports (HTTP, MQTT,
 * Modbus, ...) or the USB transfer type, {@code payload} the bytes above the transport layer,
 * {@code details} the multi-line layer breakdown for the detail pane, {@code fields} extra
 * protocol-specific values the display filter can address by name (USB: {@code usb.addr},
 * {@code usb.endpoint}, {@code usb.dir}, {@code usb.type}, ...), stored lower-case.
 */
public record PacketRow(
        long number,
        Instant time,
        double relativeSeconds,
        String src,
        String dst,
        Integer srcPort,
        Integer dstPort,
        String protocol,
        String app,
        int length,
        String info,
        byte[] raw,
        byte[] payload,
        String details,
        Map<String, String> fields) {

    public PacketRow {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    /** Network rows carry no extra fields. */
    public PacketRow(long number, Instant time, double relativeSeconds, String src, String dst, Integer srcPort,
                     Integer dstPort, String protocol, String app, int length, String info, byte[] raw, byte[] payload,
                     String details) {
        this(number, time, relativeSeconds, src, dst, srcPort, dstPort, protocol, app, length, info, raw, payload, details,
                Map.of());
    }

    /** Protocol column: the app guess when there is one, else the transport/network layer. */
    public String protocolColumn() {
        return app == null || app.isEmpty() ? protocol : app;
    }

    /** Extra field by name (see {@link #fields()}), or null. */
    public String field(String name) {
        return fields.get(name);
    }

    public boolean involves(String address) {
        return address.equals(src) || address.equals(dst);
    }

    public boolean usesPort(int port) {
        return (srcPort != null && srcPort == port) || (dstPort != null && dstPort == port);
    }
}
