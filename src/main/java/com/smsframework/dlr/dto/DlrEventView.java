package com.smsframework.dlr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** One persisted callback (dlr_events row), for debugging/audit endpoints. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DlrEventView(
        Long id,
        String source,
        String messageId,
        String externalMessageId,
        String correlationId,
        String mobile,
        String providerStatus,
        String normalizedStatus,
        String statusCode,
        String errorCode,
        String errorReason,
        LocalDateTime dlrReceivedAt,
        Integer units,
        String processingStatus,
        String processingNote,
        String rejectionReason,
        Long duplicateOf,
        String receiverInstance,
        JsonNode rawPayload,
        OffsetDateTime createdAt) {
}
