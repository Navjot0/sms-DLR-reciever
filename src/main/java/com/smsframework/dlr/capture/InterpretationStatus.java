package com.smsframework.dlr.capture;

/**
 * Outcome of DLR interpretation for a captured request. Capture itself always succeeded when one of these is
 * recorded; this only says whether the body was understood as a delivery report.
 */
public enum InterpretationStatus {
    /** Captured, interpretation not finished (or the process stopped half way). */
    PENDING,
    /** Recognised and processed as a DLR / billing / click / Meta / email / RCS event. */
    INTERPRETED,
    /** A batch (Meta webhook, email list) where some events were valid and some were not. */
    PARTIAL,
    /** Recognised format, but the DLR failed validation (e.g. missing mobile). Stored as REJECTED in dlr_events. */
    INVALID_DLR,
    /** Recognised callback that carries no delivery report (SNS subscription, WhatsApp inbound message...). */
    NO_DLR,
    /** Valid JSON that no adapter recognises. Captured; message id / status extracted on a best-effort basis. */
    UNRECOGNIZED,
    /** Looks like JSON (content type or first character) but does not parse. */
    MALFORMED_JSON,
    /** Plain text, XML, form-encoded, binary or any other non-JSON body. */
    NOT_JSON,
    /** No body. */
    EMPTY,
    /** Body larger than dlr.api.max-payload-bytes: request metadata kept, body not stored. */
    TOO_LARGE,
    /** Interpretation failed unexpectedly; the capture is intact. */
    ERROR
}
