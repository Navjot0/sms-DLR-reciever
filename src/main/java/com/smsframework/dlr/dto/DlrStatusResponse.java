package com.smsframework.dlr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smsframework.dlr.billing.BillingSummary;
import com.smsframework.dlr.click.ClickSummary;

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
        String lastRejectionReason,
        /* Billing DLR summary. Always present once a status DLR is received; otherwise only when billed. */
        BillingSummary billing,
        /* Multipart SMS: latest status per part ("<message_id>:<part>" DLRs). Absent for single-part ids. */
        List<PartStatus> parts,
        /* The id used in the request when it differs from message_id (e.g. "c9b2...:1" -> "c9b2..."). */
        String requestedId,
        /* Short-link click summary. Always present once a status DLR is received; otherwise only when clicked. */
        ClickSummary clicks) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PartStatus(int part, String providerMessageId, String providerStatus, String status,
                             String statusCode) {
    }

    public static DlrStatusResponse notReceived(String messageId) {
        return notReceived(messageId, null, null);
    }

    public static DlrStatusResponse notReceived(String messageId, Integer rejectedEvents, String lastRejectionReason) {
        return new DlrStatusResponse(messageId, null, false, null, "PENDING", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                rejectedEvents, lastRejectionReason, null, null, null, null);
    }

    public DlrStatusResponse withEvents(List<DlrEventView> ev) {
        return new DlrStatusResponse(messageId, source, received, providerStatus, status, statusCode, errorCode,
                errorReason, mobile, units, receivedAt, submitAt, correlationId, externalMessageId, campaignId,
                requestId, sender, templateId, eventCount, duplicateCount, firstReceivedAt, statusUpdatedAt, ev,
                rejectedEvents, lastRejectionReason, billing, parts, requestedId, clicks);
    }

    public DlrStatusResponse withBilling(BillingSummary b) {
        return new DlrStatusResponse(messageId, source, received, providerStatus, status, statusCode, errorCode,
                errorReason, mobile, units, receivedAt, submitAt, correlationId, externalMessageId, campaignId,
                requestId, sender, templateId, eventCount, duplicateCount, firstReceivedAt, statusUpdatedAt, events,
                rejectedEvents, lastRejectionReason, b, parts, requestedId, clicks);
    }

    public DlrStatusResponse withParts(List<PartStatus> p, String requested) {
        return new DlrStatusResponse(messageId, source, received, providerStatus, status, statusCode, errorCode,
                errorReason, mobile, units, receivedAt, submitAt, correlationId, externalMessageId, campaignId,
                requestId, sender, templateId, eventCount, duplicateCount, firstReceivedAt, statusUpdatedAt, events,
                rejectedEvents, lastRejectionReason, billing, p, requested, clicks);
    }

    public DlrStatusResponse withClicks(ClickSummary c) {
        return new DlrStatusResponse(messageId, source, received, providerStatus, status, statusCode, errorCode,
                errorReason, mobile, units, receivedAt, submitAt, correlationId, externalMessageId, campaignId,
                requestId, sender, templateId, eventCount, duplicateCount, firstReceivedAt, statusUpdatedAt, events,
                rejectedEvents, lastRejectionReason, billing, parts, requestedId, c);
    }
}
