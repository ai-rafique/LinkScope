package com.linkscope.modules.capture;

import org.junit.jupiter.api.Test;
import org.pcap4j.packet.ArpPacket;
import org.pcap4j.packet.EthernetPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.IpV4Rfc791Tos;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.UnknownPacket;
import org.pcap4j.packet.namednumber.ArpHardwareType;
import org.pcap4j.packet.namednumber.ArpOperation;
import org.pcap4j.packet.namednumber.EtherType;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;
import org.pcap4j.packet.namednumber.TcpPort;
import org.pcap4j.packet.namednumber.UdpPort;
import org.pcap4j.util.MacAddress;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure dissection on packets built in memory; no capture driver involved. */
class PacketDissectorTest {
    static final MacAddress SRC_MAC = MacAddress.getByName("00:11:22:33:44:55");
    static final MacAddress DST_MAC = MacAddress.getByName("01:00:5e:40:0a:04");

    static Inet4Address ip(String s) throws Exception {
        return (Inet4Address) InetAddress.getByName(s);
    }

    static Packet udpPacket(String src, String dst, int sport, int dport, byte[] payload) throws Exception {
        UdpPacket.Builder udp = new UdpPacket.Builder()
                .srcPort(new UdpPort((short) sport, "")).dstPort(new UdpPort((short) dport, ""))
                .srcAddr(ip(src)).dstAddr(ip(dst))
                .correctChecksumAtBuild(true).correctLengthAtBuild(true)
                .payloadBuilder(new UnknownPacket.Builder().rawData(payload));
        IpV4Packet.Builder ipv4 = new IpV4Packet.Builder()
                .version(IpVersion.IPV4).tos(IpV4Rfc791Tos.newInstance((byte) 0)).ttl((byte) 64)
                .protocol(IpNumber.UDP).srcAddr(ip(src)).dstAddr(ip(dst))
                .correctChecksumAtBuild(true).correctLengthAtBuild(true).payloadBuilder(udp);
        return new EthernetPacket.Builder().srcAddr(SRC_MAC).dstAddr(DST_MAC).type(EtherType.IPV4)
                .payloadBuilder(ipv4).paddingAtBuild(true).build();
    }

    static Packet tcpSyn(String src, String dst, int sport, int dport) throws Exception {
        TcpPacket.Builder tcp = new TcpPacket.Builder()
                .srcPort(new TcpPort((short) sport, "")).dstPort(new TcpPort((short) dport, ""))
                .sequenceNumber(1000).acknowledgmentNumber(0).dataOffset((byte) 5).syn(true).window((short) 8192)
                .srcAddr(ip(src)).dstAddr(ip(dst)).correctChecksumAtBuild(true).correctLengthAtBuild(true);
        IpV4Packet.Builder ipv4 = new IpV4Packet.Builder()
                .version(IpVersion.IPV4).tos(IpV4Rfc791Tos.newInstance((byte) 0)).ttl((byte) 64)
                .protocol(IpNumber.TCP).srcAddr(ip(src)).dstAddr(ip(dst))
                .correctChecksumAtBuild(true).correctLengthAtBuild(true).payloadBuilder(tcp);
        return new EthernetPacket.Builder().srcAddr(SRC_MAC).dstAddr(DST_MAC).type(EtherType.IPV4)
                .payloadBuilder(ipv4).paddingAtBuild(true).build();
    }

    static Packet arpRequest(String sender, String target) throws Exception {
        ArpPacket.Builder arp = new ArpPacket.Builder()
                .hardwareType(ArpHardwareType.ETHERNET).protocolType(EtherType.IPV4)
                .hardwareAddrLength((byte) 6).protocolAddrLength((byte) 4).operation(ArpOperation.REQUEST)
                .srcHardwareAddr(SRC_MAC).srcProtocolAddr(ip(sender))
                .dstHardwareAddr(MacAddress.ETHER_BROADCAST_ADDRESS).dstProtocolAddr(ip(target));
        return new EthernetPacket.Builder().srcAddr(SRC_MAC).dstAddr(MacAddress.ETHER_BROADCAST_ADDRESS).type(EtherType.ARP)
                .payloadBuilder(arp).paddingAtBuild(true).build();
    }

    @Test
    void udpMulticastWithModbusLikePayload() throws Exception {
        byte[] payload = "hello group".getBytes(StandardCharsets.UTF_8);
        PacketRow row = PacketDissector.dissect(udpPacket("10.0.0.7", "239.192.10.4", 40000, 16004, payload), 1, Instant.EPOCH, 0);
        assertEquals("10.0.0.7", row.src());
        assertEquals("239.192.10.4", row.dst());
        assertEquals(40000, row.srcPort());
        assertEquals(16004, row.dstPort());
        assertEquals("UDP", row.protocol());
        assertEquals("", row.app());
        assertEquals("UDP", row.protocolColumn());
        assertArrayEquals(payload, row.payload());
        assertTrue(row.info().startsWith("40000 → 16004 Len=11"), row.info());
        assertTrue(row.details().contains("IPv4: 10.0.0.7 → 239.192.10.4"), row.details());
        assertTrue(row.details().contains("68 65 6c 6c 6f"), "hex dump of the payload in details");
    }

    @Test
    void tcpSynToMqttPortIsLabelledByApp() throws Exception {
        PacketRow row = PacketDissector.dissect(tcpSyn("192.168.1.5", "192.168.1.10", 51000, 1883), 2, Instant.EPOCH, 0.5);
        assertEquals("TCP", row.protocol());
        assertEquals("MQTT", row.app());
        assertEquals("MQTT", row.protocolColumn());
        assertTrue(row.info().contains("[SYN]"), row.info());
        assertTrue(row.info().contains("Seq=1000"), row.info());
        assertEquals(0, row.payload().length);
    }

    @Test
    void modbusTcpPayloadIsDecoded() throws Exception {
        byte[] mbap = {0, 5, 0, 0, 0, 6, 1, 3, 0, 0, 0, 10};
        // Build a TCP packet carrying the frame on port 502.
        TcpPacket.Builder tcp = new TcpPacket.Builder()
                .srcPort(new TcpPort((short) 50000, "")).dstPort(new TcpPort((short) 502, ""))
                .sequenceNumber(1).acknowledgmentNumber(1).dataOffset((byte) 5).ack(true).psh(true).window((short) 100)
                .srcAddr(ip("10.0.0.1")).dstAddr(ip("10.0.0.2")).correctChecksumAtBuild(true).correctLengthAtBuild(true)
                .payloadBuilder(new UnknownPacket.Builder().rawData(mbap));
        IpV4Packet.Builder ipv4 = new IpV4Packet.Builder()
                .version(IpVersion.IPV4).tos(IpV4Rfc791Tos.newInstance((byte) 0)).ttl((byte) 64)
                .protocol(IpNumber.TCP).srcAddr(ip("10.0.0.1")).dstAddr(ip("10.0.0.2"))
                .correctChecksumAtBuild(true).correctLengthAtBuild(true).payloadBuilder(tcp);
        Packet p = new EthernetPacket.Builder().srcAddr(SRC_MAC).dstAddr(DST_MAC).type(EtherType.IPV4)
                .payloadBuilder(ipv4).paddingAtBuild(true).build();
        PacketRow row = PacketDissector.dissect(p, 3, Instant.EPOCH, 1);
        assertEquals("Modbus", row.app());
        assertTrue(row.details().contains("Modbus TCP: TCP tid 5 unit 1 · FC 03 Read Holding Registers · request addr 0 qty 10"),
                row.details());
    }

    @Test
    void arpRequestReadsLikeWireshark() throws Exception {
        PacketRow row = PacketDissector.dissect(arpRequest("192.168.1.1", "192.168.1.5"), 4, Instant.EPOCH, 0);
        assertEquals("ARP", row.protocol());
        assertEquals("Who has 192.168.1.5? Tell 192.168.1.1", row.info());
        assertNull(row.srcPort());
        assertEquals("192.168.1.1", row.src());
        assertEquals(42, row.length() >= 42 ? 42 : row.length(), "ARP frame padded to the Ethernet minimum");
    }
}
