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
        ClickSummary clicks,
        /* The DLR exactly as the provider / CPaaS sent it (for "<id>:<n>": that recipient's own DLR). */
        com.fasterxml.jackson.databind.JsonNode dlr,
        /* Raw webhook captures that carry this message id (set when there are any; see /api/v1/webhooks). */
        CaptureSummary captures,
        /* What the receiver has for this id -- NOT the delivery status (that is `status`):
           PARSED (a DLR was recognised and stored), INVALID (recognised format, invalid content),
           CAPTURED_NOT_INTERPRETED (a request carrying the id was captured but is no DLR the receiver understands),
           NOT_FOUND (nothing received for the id). */
        String recordState) {

    /**
     * Webhook requests captured for this message id. A capture is NOT a DLR: unrecognised payloads never change
     * received / status; automation can open them with GET /api/v1/webhooks/requests/{capture_id}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaptureSummary(long total, java.util.Map<String, Long> byInterpretationStatus,
                                 String latestCaptureId, String latestInterpretationStatus,
                                 OffsetDateTime latestReceivedAt) {
    }

    /**
     * "&lt;id&gt;:&lt;n&gt;" of a bulk / multipart send: the recipient's OWN latest DLR decides received/status/mobile,
     * never the whole message's state.
     */
    public DlrStatusResponse withRecipient(com.smsframework.dlr.entity.DlrEvent e) {
        return new DlrStatusResponse(messageId, e.getSource(), true, e.getProviderStatus(), e.getNormalizedStatus(),
                e.getStatusCode(), e.getErrorCode(), e.getErrorReason(), e.getMobile(), e.getUnits(),
                e.getDlrReceivedAt() != null ? e.getDlrReceivedAt() : receivedAt, e.getSubmitAt(),
                e.getCorrelationId() != null ? e.getCorrelationId() : correlationId,
                e.getExternalMessageId() != null ? e.getExternalMessageId() : externalMessageId, campaignId, requestId,
                e.getSender() != null ? e.getSender() : sender, e.getTemplateId() != null ? e.getTemplateId() : templateId,
                eventCount, duplicateCount, firstReceivedAt, e.getCreatedAt(), events, rejectedEvents,
                lastRejectionReason, billing, parts, requestedId, clicks, dlr, captures, recordState);
    }

    /** "&lt;id&gt;:&lt;n&gt;" whose recipient has no DLR yet while other recipients of the message do. */
    public DlrStatusResponse asRecipientNotReceived() {
        return new DlrStatusResponse(messageId, source, false, null, "PENDING", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, events,
                rejectedEvents, lastRejectionReason, billing, parts, requestedId, clicks, null, captures, recordState);
    }

    public DlrStatusResponse withRecordState(String state) {
        return new DlrStatusResponse(messageId, source, received, providerStatus, status, statusCode, errorCode,
                errorReason, mobile, units, receivedAt, submitAt, correlationId, externalMessageId, campaignId,
                requestId, sender, templateId, eventCount, duplicateCount, firstReceivedAt, statusUpdatedAt, events,
                rejectedEvents, lastRejectionReason, billing, parts, requestedId, clicks, dlr, captures, state);
    }

    public DlrStatusResponse withCaptures(CaptureSummary cs) {
        return new DlrStatusResponse(messageId, source, received, providerStatus, status, statusCode, errorCode,
                errorReason, mobile, units, receivedAt, submitAt, correlationId, externalMessageId, campaignId,
                requestId, sender, templateId, eventCount, duplicateCount, firstReceivedAt, statusUpdatedAt, events,
                rejectedEvents, lastRejectionReason, billing, parts, requestedId, clicks, dlr, cs, recordState);
    }

    public DlrStatusResponse withDlr(com.fasterxml.jackson.databind.JsonNode d) {
        return new DlrStatusResponse(messageId, source, received, providerStatus, status, statusCode, errorCode,
                errorReason, mobile, units, receivedAt, submitAt, correlationId, externalMessageId, campaignId,
                requestId, sender, templateId, eventCount, duplicateCount, firstReceivedAt, statusUpdatedAt, events,
                rejectedEvents, lastRejectionReason, billing, parts, requestedId, clicks, d, captures, recordState);
    }

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
                rejectedEvents, lastRejectionReason, null, null, null, null, null, null, null);
    }

    public DlrStatusResponse withEvents(List<DlrEventView> ev) {
        return new DlrStatusResponse(messageId, source, received, providerStatus, status, statusCode, errorCode,
                errorReason, mobile, units, receivedAt, submitAt, correlationId, externalMessageId, campaignId,
                requestId, sender, templateId, eventCount, duplicateCount, firstReceivedAt, statusUpdatedAt, ev,
                rejectedEvents, lastRejectionReason, billing, parts, requestedId, clicks, dlr, captures, recordState);
    }

    public DlrStatusResponse withBilling(BillingSummary b) {
        return new DlrStatusResponse(messageId, source, received, providerStatus, status, statusCode, errorCode,
                errorReason, mobile, units, receivedAt, submitAt, correlationId, externalMessageId, campaignId,
                requestId, sender, templateId, eventCount, duplicateCount, firstReceivedAt, statusUpdatedAt, events,
                rejectedEvents, lastRejectionReason, b, parts, requestedId, clicks, dlr, captures, recordState);
    }

    public DlrStatusResponse withParts(List<PartStatus> p, String requested) {
        return new DlrStatusResponse(messageId, source, received, providerStatus, status, statusCode, errorCode,
                errorReason, mobile, units, receivedAt, submitAt, correlationId, externalMessageId, campaignId,
                requestId, sender, templateId, eventCount, duplicateCount, firstReceivedAt, statusUpdatedAt, events,
                rejectedEvents, lastRejectionReason, billing, p, requested, clicks, dlr, captures, recordState);
    }

    public DlrStatusResponse withClicks(ClickSummary c) {
        return new DlrStatusResponse(messageId, source, received, providerStatus, status, statusCode, errorCode,
                errorReason, mobile, units, receivedAt, submitAt, correlationId, externalMessageId, campaignId,
                requestId, sender, templateId, eventCount, duplicateCount, firstReceivedAt, statusUpdatedAt, events,
                rejectedEvents, lastRejectionReason, billing, parts, requestedId, c, dlr, captures, recordState);
    }
}
