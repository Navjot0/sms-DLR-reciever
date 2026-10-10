package com.smsframework.dlr.domain;

/**
 * What the DLR pipeline did with a callback. Recognised DLR callbacks are persisted in dlr_events with one of
 * APPLIED / IGNORED / DUPLICATE / REJECTED; UNRECOGNIZED payloads are only kept as raw webhook captures.
 */
public enum ProcessingStatus {
    /** Valid DLR; it created or advanced the message's current state. */
    APPLIED,
    /** Valid DLR, but the state machine did not apply it (out-of-order / downgrade / same state). */
    IGNORED,
    /** Exact repeat of an already-recorded event (same idempotency key). */
    DUPLICATE,
    /** Invalid/malformed/unsupported DLR. Raw payload + rejection reason stored. */
    REJECTED,
    /**
     * Not a DLR this receiver understands (empty, not JSON, unknown structure or source). Never stored in
     * dlr_events: the request itself is kept in webhook_requests (see WebhookCaptureService).
     */
    UNRECOGNIZED
}
