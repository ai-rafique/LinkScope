package com.linkscope.core;

import java.util.Map;

/** Well-known TCP/UDP port numbers to service names, shared by the packet dissector and the port survey. */
public final class KnownPorts {
    public static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(20, "FTP data"), Map.entry(21, "FTP"), Map.entry(22, "SSH"), Map.entry(23, "Telnet"),
            Map.entry(25, "SMTP"), Map.entry(53, "DNS"), Map.entry(67, "DHCP"), Map.entry(68, "DHCP"), Map.entry(69, "TFTP"),
            Map.entry(80, "HTTP"), Map.entry(88, "Kerberos"), Map.entry(110, "POP3"), Map.entry(111, "RPC portmap"),
            Map.entry(123, "NTP"), Map.entry(135, "MS RPC"), Map.entry(137, "NetBIOS"), Map.entry(138, "NetBIOS"),
            Map.entry(139, "NetBIOS"), Map.entry(143, "IMAP"), Map.entry(161, "SNMP"), Map.entry(162, "SNMP trap"),
            Map.entry(179, "BGP"), Map.entry(389, "LDAP"), Map.entry(443, "TLS"), Map.entry(445, "SMB"),
            Map.entry(465, "SMTPS"), Map.entry(500, "IKE"), Map.entry(502, "Modbus"), Map.entry(514, "Syslog"),
            Map.entry(515, "LPD"), Map.entry(554, "RTSP"), Map.entry(587, "SMTP"), Map.entry(631, "IPP"),
            Map.entry(636, "LDAPS"), Map.entry(993, "IMAPS"), Map.entry(995, "POP3S"), Map.entry(1194, "OpenVPN"),
            Map.entry(1433, "MSSQL"), Map.entry(1521, "Oracle"), Map.entry(1723, "PPTP"), Map.entry(1883, "MQTT"),
            Map.entry(1900, "SSDP"), Map.entry(2049, "NFS"), Map.entry(2404, "IEC 60870-5-104"), Map.entry(3306, "MySQL"),
            Map.entry(3389, "RDP"), Map.entry(4222, "NATS"), Map.entry(4840, "OPC UA"), Map.entry(5060, "SIP"),
            Map.entry(5353, "mDNS"), Map.entry(5432, "PostgreSQL"), Map.entry(5672, "AMQP"), Map.entry(5683, "CoAP"),
            Map.entry(5900, "VNC"), Map.entry(6379, "Redis"), Map.entry(8080, "HTTP"), Map.entry(8443, "HTTPS"),
            Map.entry(8883, "MQTT/TLS"), Map.entry(9092, "Kafka"), Map.entry(9200, "Elasticsearch"), Map.entry(20000, "DNP3"),
            Map.entry(27017, "MongoDB"), Map.entry(44818, "EtherNet/IP"), Map.entry(47808, "BACnet"));

    private KnownPorts() {
    }

    /** Service name for a port, or "" when it is not a well-known one. */
    public static String nameOf(int port) {
        return NAMES.getOrDefault(port, "");
    }
}
