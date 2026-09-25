package com.smsframework.dlr.exception;

/**
 * Thrown by adapters when a callback is structurally invalid. The processing
 * service converts it into a persisted REJECTED event (never silently discarded).
 */
public class DlrValidationException extends RuntimeException {

    private final String source;

    public DlrValidationException(String source, String reason) {
        super(reason);
        this.source = source;
    }

    public String getSource() {
        return source;
    }

    public String getReason() {
        return getMessage();
    }
}
