package com.smsframework.dlr.util;

/**
 * Masks MSISDNs for logs: keeps the first 4 and last 2 digits.
 * 917973059161 -> 9179******61
 */
public final class MobileMasker {

    private MobileMasker() {
    }

    public static String mask(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            return mobile;
        }
        String m = mobile.trim();
        int len = m.length();
        if (len <= 4) {
            return "*".repeat(len);
        }
        if (len <= 6) {
            return m.substring(0, 2) + "*".repeat(len - 2);
        }
        return m.substring(0, 4) + "*".repeat(len - 6) + m.substring(len - 2);
    }

    /** Shortens long ids for log readability: 2ee98174-eec2-... -> 2ee98174... (full id stays in the DB). */
    public static String abbreviate(String id, int keep) {
        if (id == null || id.length() <= keep) {
            return id;
        }
        return id.substring(0, keep) + "...";
    }
}
