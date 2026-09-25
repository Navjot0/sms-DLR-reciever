package com.smsframework.dlr.domain;

/**
 * What the receiver did with a callback. Every callback is persisted in
 * dlr_events with exactly one of these values.
 */
public enum ProcessingStatus {
    /** Valid DLR; it created or advanced the message's current state. */
    APPLIED,
    /** Valid DLR, but the state machine did not apply it (out-of-order / downgrade / same state). */
    IGNORED,
    /** Exact repeat of an already-recorded event (same idempotency key). */
    DUPLICATE,
    /** Invalid/malformed/unsupported DLR. Raw payload + rejection reason stored. */
    REJECTED
}
