package com.smsframework.dlr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/** Response of GET /api/v1/dlr/{messageId}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DlrStatusResponse(
        String messageId,
        String source,
        boolean received,
        String providerStatus,
        String status,
        String statusCode,
        String errorCode,
        String errorReason,
        String mobile,
        Integer units,
        LocalDateTime receivedAt,
        LocalDateTime submitAt,
        String correlationId,
        String externalMessageId,
        String campaignId,
        String requestId,
        String sender,
        String templateId,
        Integer eventCount,
        Integer duplicateCount,
        OffsetDateTime firstReceivedAt,
        OffsetDateTime statusUpdatedAt,
        List<DlrEventView> events,
        /* Only set when no valid DLR exists but REJECTED callbacks carried this message_id. */
        Integer rejectedEvents,
        String lastRejectionReason) {

    public static DlrStatusResponse notReceived(String messageId) {
        return notReceived(messageId, null, null);
    }

    public static DlrStatusResponse notReceived(String messageId, Integer rejectedEvents, String lastRejectionReason) {
        return new DlrStatusResponse(messageId, null, false, null, "PENDING", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                rejectedEvents, lastRejectionReason);
    }

    public DlrStatusResponse withEvents(List<DlrEventView> ev) {
        return new DlrStatusResponse(messageId, source, received, providerStatus, status, statusCode, errorCode,
                errorReason, mobile, units, receivedAt, submitAt, correlationId, externalMessageId, campaignId,
                requestId, sender, templateId, eventCount, duplicateCount, firstReceivedAt, statusUpdatedAt, ev,
                rejectedEvents, lastRejectionReason);
    }
}
