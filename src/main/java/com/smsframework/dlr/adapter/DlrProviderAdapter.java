package com.smsframework.dlr.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.dto.NormalizedDlr;

/**
 * Extension point for DLR providers. To add a provider, create one Spring {@code @Component}
 * implementing this interface; no change to the controller, processing service, repository
 * or schema is required.
 */
public interface DlrProviderAdapter {

    /** Canonical source name, e.g. DEFAULT_SMS, WEBENGAGE. Stored in dlr_events.source. */
    String source();

    /**
     * @param source  explicit source resolved from header/query parameter (already canonicalised),
     *                or {@code null} when the caller did not specify one
     * @param payload parsed JSON body
     * @return true when this adapter handles the callback. With an explicit source, adapters must
     *         match on the name only; without one, they may inspect the payload structure.
     */
    boolean supports(String source, JsonNode payload);

    /**
     * Validates and converts the provider payload into the common model (including normalized status).
     *
     * @throws com.smsframework.dlr.exception.DlrValidationException if required fields are missing/invalid
     */
    NormalizedDlr normalize(JsonNode payload);

    /**
     * Best-effort extraction of the message id from a payload that FAILED validation, so that rejected
     * callbacks can still be found by message_id when debugging automation failures. Must never throw.
     */
    default String peekMessageId(JsonNode payload) {
        return null;
    }
}
