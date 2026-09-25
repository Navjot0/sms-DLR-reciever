package com.smsframework.dlr.billing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One persisted billing event (dlr_billing_events row) for debugging/audit endpoints. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BillingEventView(
        Long id,
        String source,
        UUID batchId,
        Integer batchIndex,
        String messageId,
        String billingMessageId,
        Integer partNumber,
        String transactionType,
        String product,
        Integer units,
        BigDecimal salePrice,
        String currency,
        BigDecimal surcharge,
        BigDecimal totalAmount,
        String processingStatus,
        String processingNote,
        String rejectionReason,
        Long duplicateOf,
        JsonNode rawEvent,
        OffsetDateTime createdAt) {
}
