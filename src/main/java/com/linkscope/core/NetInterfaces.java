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
}
