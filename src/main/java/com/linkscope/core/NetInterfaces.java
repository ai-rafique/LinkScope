package com.linkscope.core;

import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Network interface listing with a sensible order: physical adapters that have a routable
 * IPv4 address first, then virtual adapters (Hyper-V, WSL, Docker, VPN, VMware, Npcap),
 * then loopback. "Auto" choices in the app pick the first entry.
 */
public final class NetInterfaces {
    private static final String[] VIRTUAL_MARKERS = {
        "virtual", "vethernet", "hyper-v", "vmware", "virtualbox", "vbox", "wsl", "docker", "npcap", "loopback",
        "tap-", "tap ", "tun", "wintun", "wireguard", "openvpn", "zerotier", "tailscale", "bluetooth", "pseudo",
        "teredo", "isatap", "6to4", "miniport", "vpn"
    };

    private NetInterfaces() {
    }

    /** Up, multicast-capable interfaces with an IPv4 address, best candidates first. Never throws. */
    public static List<NetworkInterface> candidates() {
        List<NetworkInterface> physical = new ArrayList<>();
        List<NetworkInterface> virtual = new ArrayList<>();
        List<NetworkInterface> loopbacks = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!nif.isUp() || !nif.supportsMulticast() || !hasIpv4(nif)) {
                        continue;
                    }
                    if (nif.isLoopback()) {
                        loopbacks.add(nif);
                    } else if (isVirtual(nif)) {
                        virtual.add(nif);
                    } else {
                        physical.add(nif);
                    }
                } catch (SocketException ignored) {
                    // skip broken interface
                }
            }
        } catch (SocketException ignored) {
            // no interfaces at all
        }
        // Within a class, adapters with a routable (non link-local) address go first.
        physical.sort((a, b) -> Boolean.compare(!hasRoutableIpv4(a), !hasRoutableIpv4(b)));
        virtual.sort((a, b) -> Boolean.compare(!hasRoutableIpv4(a), !hasRoutableIpv4(b)));
        List<NetworkInterface> out = new ArrayList<>(physical);
        out.addAll(virtual);
        out.addAll(loopbacks);
        return out;
    }

    /** Heuristic on the adapter's names; a wrong guess only affects ordering, never availability. */
    public static boolean isVirtual(NetworkInterface nif) {
        if (looksVirtual(nif.getDisplayName() + " " + nif.getName())) {
            return true;
        }
        try {
            return nif.isVirtual() || nif.getHardwareAddress() == null;
        } catch (SocketException e) {
            return true;
        }
    }

    public static boolean hasIpv4(NetworkInterface nif) {
        for (InterfaceAddress a : nif.getInterfaceAddresses()) {
            if (a.getAddress() instanceof Inet4Address) {
                return true;
            }
        }
        return false;
    }

    /** Same heuristic on any adapter name or description text (capture devices come with their own strings). */
    public static boolean looksVirtual(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : VIRTUAL_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasRoutableIpv4(NetworkInterface nif) {
        for (InterfaceAddress a : nif.getInterfaceAddresses()) {
            if (a.getAddress() instanceof Inet4Address ip && !ip.isLinkLocalAddress() && !ip.isLoopbackAddress()) {
                return true;
            }
        }
        return false;
    }

    /** First IPv4 address as text, or "" when none. */
    public static String ipv4Of(NetworkInterface nif) {
        for (InterfaceAddress a : nif.getInterfaceAddresses()) {
            if (a.getAddress() instanceof Inet4Address ip) {
                return ip.getHostAddress();
            }
        }
        return "";
    }

    /** Human-readable label: display name plus first IPv4 address, with a tag for virtual adapters. */
    public static String describe(NetworkInterface nif) {
        String ip = ipv4Of(nif);
        return nif.getDisplayName() + (ip.isEmpty() ? "" : " (" + ip + ")") + (isVirtual(nif) && !isLoopback(nif) ? " [virtual]" : "");
    }

    private static boolean isLoopback(NetworkInterface nif) {
        try {
            return nif.isLoopback();
        } catch (SocketException e) {
            return false;
        }
    }

    // --- IPv4 networks (for scanners) ------------------------------------------------------

    /** One IPv4 address of an up interface, with its prefix length and the adapter it belongs to. */
    public record Ipv4Network(NetworkInterface nif, String address, int prefix, boolean loopback, boolean virtual) {
        /** The /24 that contains the address, as "a.b.c" (the scanners sweep one /24 at a time). */
        public String base24() {
            return NetInterfaces.base24(address);
        }

        public String cidr() {
            return address + "/" + prefix;
        }

        public String label() {
            return nif.getDisplayName() + "   " + cidr() + (virtual && !loopback ? "  [virtual]" : "");
        }
    }

    /** "192.168.1.5" → "192.168.1". */
    public static String base24(String address) {
        int dot = address.lastIndexOf('.');
        return dot < 0 ? address : address.substring(0, dot);
    }

    /** Every IPv4 address of every up interface, physical routable first, then virtual, loopback last. Never throws. */
    public static List<Ipv4Network> ipv4Networks() {
        List<Ipv4Network> physical = new ArrayList<>();
        List<Ipv4Network> virtual = new ArrayList<>();
        List<Ipv4Network> loopbacks = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!nif.isUp()) {
                        continue;
                    }
                } catch (SocketException e) {
                    continue;
                }
                boolean lo = isLoopback(nif);
                boolean virt = !lo && isVirtual(nif);
                for (InterfaceAddress a : nif.getInterfaceAddresses()) {
                    if (a.getAddress() instanceof Inet4Address ip) {
                        Ipv4Network n = new Ipv4Network(nif, ip.getHostAddress(), a.getNetworkPrefixLength(), lo, virt);
                        (lo ? loopbacks : virt ? virtual : physical).add(n);
                    }
                }
            }
        } catch (SocketException ignored) {
            // nothing to list
        }
        java.util.Comparator<Ipv4Network> routableFirst = (a, b) -> Boolean.compare(a.address().startsWith("169.254."), b.address().startsWith("169.254."));
        physical.sort(routableFirst);
        virtual.sort(routableFirst);
        List<Ipv4Network> out = new ArrayList<>(physical);
        out.addAll(virtual);
        out.addAll(loopbacks);
        return out;
    }

    /** A default route: the gateway address and, when the OS reports it, the local address it is reached from. */
    public record Gateway(String address, String viaAddress) {
    }

    /**
     * Default IPv4 gateways from the OS routing table (Windows {@code route print}, Linux
     * {@code ip route}, macOS {@code netstat -rn}). Best effort: empty when unavailable.
     */
    public static List<Gateway> defaultGateways() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return parseWindowsRoutes(runForOutput("route", "print", "-4"));
            }
            if (os.contains("mac")) {
                return parseMacRoutes(runForOutput("netstat", "-rn"));
            }
            return parseLinuxRoutes(runForOutput("ip", "route"));
        } catch (java.io.IOException | RuntimeException e) {
            return List.of();
        }
    }

    static List<Gateway> parseWindowsRoutes(String text) {
        List<Gateway> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*0\\.0\\.0\\.0\\s+0\\.0\\.0\\.0\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(text);
        while (m.find()) {
            out.add(new Gateway(m.group(1), m.group(2)));
        }
        return out;
    }

    static List<Gateway> parseLinuxRoutes(String text) {
        List<Gateway> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^default via (\\d+\\.\\d+\\.\\d+\\.\\d+)(?:.*?\\bsrc (\\d+\\.\\d+\\.\\d+\\.\\d+))?").matcher(text);
        while (m.find()) {
            out.add(new Gateway(m.group(1), m.group(2) == null ? "" : m.group(2)));
        }
        return out;
    }

    static List<Gateway> parseMacRoutes(String text) {
        List<Gateway> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^default\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(text);
        while (m.find()) {
            out.add(new Gateway(m.group(1), ""));
        }
        return out;
    }

    /** Runs a short system command and returns its output; used for routing and ARP tables. */
    public static String runForOutput(String... command) throws java.io.IOException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] out;
        try (java.io.InputStream in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        try {
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new String(out, java.nio.charset.StandardCharsets.UTF_8);
    }
}
