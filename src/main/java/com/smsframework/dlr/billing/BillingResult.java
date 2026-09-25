package com.smsframework.dlr.billing;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of one billing callback, returned to the provider as the acknowledgement.
 * processing_status: APPLIED (all applied) | DUPLICATE (all duplicates) | REJECTED (all rejected) | PARTIAL (mixed).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BillingResult(
        String eventType,
        String source,
        UUID batchId,
        String processingStatus,
        int total,
        int applied,
        int duplicates,
        int rejected,
        List<EventOutcome> events) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventOutcome(
            int index,
            Long eventId,
            String messageId,
            String billingMessageId,
            String processingStatus,
            String rejectionReason,
            String note) {
    }
}
