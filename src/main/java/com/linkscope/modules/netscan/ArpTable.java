package com.linkscope.modules.netscan;

import com.linkscope.core.NetInterfaces;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OS neighbour (ARP) table: IPv4 address to MAC. A sweep makes the kernel resolve every
 * address it touched, so after a scan the table tells which hosts answered ARP even when
 * they ignore ping and every probed port. That is the most reliable presence test on a LAN.
 */
public final class ArpTable {
    private static final Pattern ENTRY = Pattern.compile(
            "(\\d{1,3}(?:\\.\\d{1,3}){3})[^\\n]*?(?<![0-9a-fA-F:-])([0-9a-fA-F]{1,2}(?:[-:][0-9a-fA-F]{1,2}){5})(?![0-9a-fA-F:-])");

    private ArpTable() {
    }

    /** Reads the table with the platform tool; empty when unavailable. Blocking, off the FX thread. */
    public static Map<String, String> read() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            String text = os.contains("win") || os.contains("mac")
                    ? NetInterfaces.runForOutput("arp", "-a")
                    : NetInterfaces.runForOutput("ip", "neigh");
            return parse(text);
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    /** Parses Windows/macOS {@code arp -a} or Linux {@code ip neigh}; broadcast, multicast and zero MACs are dropped. */
    static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = ENTRY.matcher(text);
        while (m.find()) {
            String mac = normalize(m.group(2));
            if (mac.equals("ff:ff:ff:ff:ff:ff") || mac.equals("00:00:00:00:00:00") || mac.startsWith("01:00:5e")
                    || mac.startsWith("33:33")) {
                continue;
            }
            out.putIfAbsent(m.group(1), mac);
        }
        return out;
    }

    static String normalize(String mac) {
        String[] parts = mac.split("[-:]");
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            if (parts[i].length() == 1) {
                sb.append('0');
            }
            sb.append(parts[i].toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
