package com.smsframework.dlr.billing;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Extension point for billing DLR formats (analogous to DlrProviderAdapter for status DLRs).
 */
public interface BillingDlrAdapter {

    /** True when the payload is a billing callback this adapter understands (e.g. event_type == "billing"). */
    boolean isBillingPayload(JsonNode payload);

    /** Sources (canonical names) allowed to send this billing format. */
    boolean acceptsSource(String source);

    /**
     * Splits the callback into individually validated events.
     *
     * @throws com.smsframework.dlr.exception.DlrValidationException when the callback as a whole is invalid
     *         (e.g. "events" missing or empty); individual bad events are returned with a rejection reason instead.
     */
    List<NormalizedBillingEvent> normalize(JsonNode payload);
}
