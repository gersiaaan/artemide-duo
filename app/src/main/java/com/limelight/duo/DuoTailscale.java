package com.limelight.duo;

import java.net.NetworkInterface;
import java.util.Collections;

/** Tailscale helpers: its addresses (100.64.0.0/10, fd7a:115c:a1e0::/48) and whether this device is on it. */
public final class DuoTailscale {
    private DuoTailscale() {}

    public static boolean isTailscale(String address) {
        if (address == null) {
            return false;
        }
        String a = address.trim().toLowerCase();
        if (a.startsWith("[")) {
            a = a.substring(1);
        }
        if (a.startsWith("fd7a:115c:a1e0:")) {
            return true;
        }
        String[] p = a.split("\\.");
        if (p.length != 4) {
            return false;
        }
        try {
            int o1 = Integer.parseInt(p[0]);
            int o2 = Integer.parseInt(p[1]);
            return o1 == 100 && o2 >= 64 && o2 <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** This device's own Tailscale IPv4 address, or null when Tailscale isn't connected. */
    public static String ownAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) {
                    continue;
                }
                for (java.net.InetAddress ad : Collections.list(ni.getInetAddresses())) {
                    String h = ad.getHostAddress();
                    if (ad instanceof java.net.Inet4Address && isTailscale(h)) {
                        return h;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
