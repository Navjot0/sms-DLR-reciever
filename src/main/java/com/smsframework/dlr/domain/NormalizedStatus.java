package com.smsframework.dlr.domain;

/**
 * Provider-independent delivery status. The original provider status is always
 * kept alongside this value (provider_status column).
 */
public enum NormalizedStatus {
    SENT,
    DELIVERED,
    FAILED,
    EXPIRED,
    REJECTED,
    UNKNOWN;

    public static NormalizedStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return NormalizedStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
