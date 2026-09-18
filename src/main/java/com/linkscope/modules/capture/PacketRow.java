package com.linkscope.modules.capture;

import java.time.Instant;

/**
 * One captured frame as shown in the table. {@code protocol} is the highest recognised
 * layer (ARP, ICMP, TCP, UDP, ...), {@code app} a guess from well-known ports (HTTP, MQTT,
 * Modbus, ...), {@code payload} the bytes above the transport layer, {@code details} the
 * multi-line layer breakdown for the detail pane.
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
        String details) {

    /** Protocol column: the app guess when there is one, else the transport/network layer. */
    public String protocolColumn() {
        return app == null || app.isEmpty() ? protocol : app;
    }

    public boolean involves(String address) {
        return address.equals(src) || address.equals(dst);
    }

    public boolean usesPort(int port) {
        return (srcPort != null && srcPort == port) || (dstPort != null && dstPort == port);
    }
}
