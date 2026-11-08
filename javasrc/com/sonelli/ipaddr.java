package com.sonelli;

/**
 * Minimal literal IPv4/IPv6 checks, replacing org.apache.http.conn.util.InetAddressUtils
 * so that the deprecated org.apache.http.legacy library is no longer load-bearing for
 * host validation. Never performs a DNS lookup.
 */
public final class ipaddr {

    private ipaddr() {
    }

    public static boolean isIPv4(String address) {
        if (address == null) {
            return false;
        }
        String[] groups = address.split("\\.", -1);
        if (groups.length != 4) {
            return false;
        }
        for (String group : groups) {
            if (group.isEmpty() || group.length() > 3) {
                return false;
            }
            for (int i = 0; i < group.length(); i++) {
                if (!Character.isDigit(group.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(group) > 255) {
                return false;
            }
        }
        return true;
    }

    public static boolean isIPv6(String address) {
        if (address == null) {
            return false;
        }
        String value = address;
        if (value.length() > 1 && value.charAt(0) == '[' && value.charAt(value.length() - 1) == ']') {
            value = value.substring(1, value.length() - 1);
        }
        if (value.indexOf(':') < 0) {
            return false;
        }
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        String[] head;
        String[] tail;
        int compressed = value.indexOf("::");
        if (compressed >= 0) {
            if (value.indexOf("::", compressed + 1) >= 0) {
                return false;
            }
            String before = value.substring(0, compressed);
            String after = value.substring(compressed + 2);
            head = before.isEmpty() ? new String[0] : before.split(":", -1);
            tail = after.isEmpty() ? new String[0] : after.split(":", -1);
        } else {
            head = value.split(":", -1);
            tail = new String[0];
        }
        int groups = 0;
        for (int i = 0; i < head.length; i++) {
            String group = head[i];
            if (group.isEmpty()) {
                return false;
            }
            if (i == head.length - 1 && isIPv4(group)) {
                groups += 2;
            } else if (isHexGroup(group)) {
                groups += 1;
            } else {
                return false;
            }
        }
        for (int i = 0; i < tail.length; i++) {
            String group = tail[i];
            if (group.isEmpty()) {
                return false;
            }
            if (i == tail.length - 1 && isIPv4(group)) {
                groups += 2;
            } else if (isHexGroup(group)) {
                groups += 1;
            } else {
                return false;
            }
        }
        return compressed >= 0 ? groups <= 7 : groups == 8;
    }

    private static boolean isHexGroup(String group) {
        if (group.isEmpty() || group.length() > 4) {
            return false;
        }
        for (int i = 0; i < group.length(); i++) {
            char c = group.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
