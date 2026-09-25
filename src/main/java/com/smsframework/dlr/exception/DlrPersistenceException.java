package com.smsframework.dlr.exception;

/** Database failure while processing a DLR. Returned as 503 so the provider retries. */
public class DlrPersistenceException extends RuntimeException {
    public DlrPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
