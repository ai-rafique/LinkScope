package com.linkscope.modules.capture;

import com.linkscope.core.PayloadCodec;
import com.linkscope.core.protocol.ModbusDecoder;
import org.pcap4j.packet.ArpPacket;
import org.pcap4j.packet.DnsPacket;
import org.pcap4j.packet.EthernetPacket;
import org.pcap4j.packet.IcmpV4CommonPacket;
import org.pcap4j.packet.IcmpV6CommonPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.IpV6Packet;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.namednumber.ArpOperation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Turns a pcap4j packet into a {@link PacketRow}: addresses, ports, protocol, one-line info, layer details. */
public final class PacketDissector {

    /** Well-known ports to application names, used for the protocol column and display filters. */
    static final Map<Integer, String> APPS = Map.ofEntries(
            Map.entry(20, "FTP"), Map.entry(21, "FTP"), Map.entry(22, "SSH"), Map.entry(23, "Telnet"),
            Map.entry(25, "SMTP"), Map.entry(53, "DNS"), Map.entry(67, "DHCP"), Map.entry(68, "DHCP"),
            Map.entry(80, "HTTP"), Map.entry(110, "POP3"), Map.entry(123, "NTP"), Map.entry(143, "IMAP"),
            Map.entry(161, "SNMP"), Map.entry(162, "SNMP"), Map.entry(443, "TLS"), Map.entry(502, "Modbus"),
            Map.entry(514, "Syslog"), Map.entry(1883, "MQTT"), Map.entry(1900, "SSDP"), Map.entry(3389, "RDP"),
            Map.entry(4222, "NATS"), Map.entry(5060, "SIP"), Map.entry(5353, "mDNS"), Map.entry(5683, "CoAP"),
            Map.entry(8080, "HTTP"), Map.entry(8443, "HTTPS"), Map.entry(8883, "MQTT/TLS"), Map.entry(9092, "Kafka"));

    private PacketDissector() {
    }

    public static PacketRow dissect(Packet packet, long number, Instant time, double relativeSeconds) {
        byte[] raw = packet.getRawData();
        List<String> details = new ArrayList<>();
        String src = "";
        String dst = "";
        Integer srcPort = null;
        Integer dstPort = null;
        String protocol = "?";
        String app = "";
        String info = "";
        byte[] payload = new byte[0];

        EthernetPacket eth = packet.get(EthernetPacket.class);
        if (eth != null) {
            details.add("Ethernet: " + eth.getHeader().getSrcAddr() + " → " + eth.getHeader().getDstAddr()
                    + ", type " + eth.getHeader().getType());
            protocol = eth.getHeader().getType().name();
            info = "Ethernet type " + eth.getHeader().getType();
        }

        ArpPacket arp = packet.get(ArpPacket.class);
        if (arp != null) {
            ArpPacket.ArpHeader h = arp.getHeader();
            src = h.getSrcProtocolAddr().getHostAddress();
            dst = h.getDstProtocolAddr().getHostAddress();
            protocol = "ARP";
            if (h.getOperation().equals(ArpOperation.REQUEST)) {
                info = "Who has " + dst + "? Tell " + src;
            } else if (h.getOperation().equals(ArpOperation.REPLY)) {
                info = src + " is at " + h.getSrcHardwareAddr();
            } else {
                info = h.getOperation().name();
            }
            details.add("ARP: " + h.getOperation().name() + ", sender " + src + " (" + h.getSrcHardwareAddr() + "), target "
                    + dst + " (" + h.getDstHardwareAddr() + ")");
            return new PacketRow(number, time, relativeSeconds, src, dst, null, null, protocol, "", raw.length, info, raw,
                    payload, String.join("\n", details));
        }

        IpV4Packet ip4 = packet.get(IpV4Packet.class);
        IpV6Packet ip6 = packet.get(IpV6Packet.class);
        if (ip4 != null) {
            IpV4Packet.IpV4Header h = ip4.getHeader();
            src = h.getSrcAddr().getHostAddress();
            dst = h.getDstAddr().getHostAddress();
            protocol = h.getProtocol().name();
            details.add("IPv4: " + src + " → " + dst + ", ttl " + h.getTtlAsInt() + ", id " + h.getIdentificationAsInt()
                    + ", protocol " + h.getProtocol() + (h.getMoreFragmentFlag() || h.getFragmentOffset() != 0 ? ", fragment" : ""));
            info = h.getProtocol().name();
        } else if (ip6 != null) {
            IpV6Packet.IpV6Header h = ip6.getHeader();
            src = h.getSrcAddr().getHostAddress();
            dst = h.getDstAddr().getHostAddress();
            protocol = h.getNextHeader().name();
            details.add("IPv6: " + src + " → " + dst + ", hop limit " + h.getHopLimitAsInt() + ", next header " + h.getNextHeader());
            info = h.getNextHeader().name();
        }

        TcpPacket tcp = packet.get(TcpPacket.class);
        UdpPacket udp = packet.get(UdpPacket.class);
        IcmpV4CommonPacket icmp4 = packet.get(IcmpV4CommonPacket.class);
        IcmpV6CommonPacket icmp6 = packet.get(IcmpV6CommonPacket.class);

        if (tcp != null) {
            TcpPacket.TcpHeader h = tcp.getHeader();
            srcPort = h.getSrcPort().valueAsInt();
            dstPort = h.getDstPort().valueAsInt();
            protocol = "TCP";
            payload = tcp.getPayload() == null ? new byte[0] : tcp.getPayload().getRawData();
            List<String> flags = new ArrayList<>();
            if (h.getSyn()) {
                flags.add("SYN");
            }
            if (h.getAck()) {
                flags.add("ACK");
            }
            if (h.getPsh()) {
                flags.add("PSH");
            }
            if (h.getFin()) {
                flags.add("FIN");
            }
            if (h.getRst()) {
                flags.add("RST");
            }
            if (h.getUrg()) {
                flags.add("URG");
            }
            info = srcPort + " → " + dstPort + " [" + String.join(",", flags) + "] Seq=" + h.getSequenceNumberAsLong()
                    + " Ack=" + h.getAcknowledgmentNumberAsLong() + " Win=" + h.getWindowAsInt() + " Len=" + payload.length;
            details.add("TCP: " + info);
        } else if (udp != null) {
            UdpPacket.UdpHeader h = udp.getHeader();
            srcPort = h.getSrcPort().valueAsInt();
            dstPort = h.getDstPort().valueAsInt();
            protocol = "UDP";
            payload = udp.getPayload() == null ? new byte[0] : udp.getPayload().getRawData();
            info = srcPort + " → " + dstPort + " Len=" + payload.length;
            details.add("UDP: " + srcPort + " → " + dstPort + ", length " + h.getLengthAsInt());
        } else if (icmp4 != null) {
            IcmpV4CommonPacket.IcmpV4CommonHeader h = icmp4.getHeader();
            protocol = "ICMP";
            info = h.getType().name() + " (type " + h.getType().valueAsString() + ", code " + h.getCode().valueAsString() + ")";
            details.add("ICMPv4: " + info);
            payload = icmp4.getPayload() == null ? new byte[0] : icmp4.getPayload().getRawData();
        } else if (icmp6 != null) {
            IcmpV6CommonPacket.IcmpV6CommonHeader h = icmp6.getHeader();
            protocol = "ICMPv6";
            info = h.getType().name() + " (type " + h.getType().valueAsString() + ", code " + h.getCode().valueAsString() + ")";
            details.add("ICMPv6: " + info);
            payload = icmp6.getPayload() == null ? new byte[0] : icmp6.getPayload().getRawData();
        } else if (ip4 != null || ip6 != null) {
            Packet inner = ip4 != null ? ip4.getPayload() : ip6.getPayload();
            payload = inner == null ? new byte[0] : inner.getRawData();
        }

        if (srcPort != null) {
            app = appFor(srcPort, dstPort);
            if (!app.isEmpty()) {
                details.add(app + " (by port)");
            }
        }

        DnsPacket dns = packet.get(DnsPacket.class);
        if (dns != null) {
            DnsPacket.DnsHeader h = dns.getHeader();
            List<String> names = new ArrayList<>();
            h.getQuestions().forEach(q -> names.add(q.getQName().getName()));
            String kind = h.isResponse() ? "response" : "query";
            info = "DNS " + kind + (names.isEmpty() ? "" : " " + String.join(", ", names))
                    + (h.isResponse() ? " (" + h.getAnswers().size() + " answer(s))" : "");
            details.add("DNS: " + info);
        }

        if ("Modbus".equals(app) && payload.length >= 8) {
            ModbusDecoder.decodeTcp(payload).ifPresent(frame -> {
                details.add("Modbus TCP: " + frame.summary());
                details.add(frame.describe());
            });
        }

        if (payload.length > 0) {
            details.add("Payload: " + payload.length + " byte(s)\n" + com.linkscope.core.PayloadDecoder.hexDump(payload));
        }
        return new PacketRow(number, time, relativeSeconds, src, dst, srcPort, dstPort, protocol, app, raw.length, info, raw,
                payload, String.join("\n", details));
    }

    static String appFor(Integer a, Integer b) {
        if (a != null && APPS.containsKey(a)) {
            return APPS.get(a);
        }
        if (b != null && APPS.containsKey(b)) {
            return APPS.get(b);
        }
        return "";
    }

    /** Escaped ASCII of the payload, for the "contains" display filter and the copy action. */
    static String payloadText(PacketRow row) {
        return row.payload() == null ? "" : PayloadCodec.toAscii(row.payload());
    }
}
