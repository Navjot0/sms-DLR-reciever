package com.smsframework.dlr.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsframework.dlr.billing.BillingEventView;
import com.smsframework.dlr.billing.DlrBillingEvent;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.domain.ProcessingStatus;
import com.smsframework.dlr.dto.DlrEventView;
import com.smsframework.dlr.dto.DlrStatusResponse;
import com.smsframework.dlr.dto.NormalizedDlr;
import com.smsframework.dlr.entity.DlrEvent;
import com.smsframework.dlr.entity.DlrMessageStatus;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/** Conversions between the normalized model, persistence rows and API responses. */
@Component
public class DlrMapper {

    private final ObjectMapper objectMapper;
    private final ZoneId zone;

    public DlrMapper(ObjectMapper objectMapper, DlrProperties properties) {
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(properties.getTimezone());
    }

    public DlrEvent toEvent(NormalizedDlr d, String rawPayload, String dedupKey, String instance) {
        DlrEvent e = new DlrEvent();
        e.setSource(d.getSource());
        e.setMessageId(d.getMessageId());
        e.setProviderMessageId(d.getProviderMessageId());
        e.setPartNumber(d.getPartNumber());
        e.setExternalMessageId(d.getExternalMessageId());
        e.setCorrelationId(d.getCorrelationId());
        e.setCampaignId(d.getCampaignId());
        e.setRequestId(d.getRequestId());
        e.setProviderEventId(d.getProviderEventId());
        e.setMobile(d.getMobile());
        e.setSender(d.getSender());
        e.setService(d.getService());
        e.setProviderStatus(d.getProviderStatus());
        e.setNormalizedStatus(d.getNormalizedStatus() == null ? null : d.getNormalizedStatus().name());
        e.setStatusCode(d.getStatusCode());
        e.setErrorCode(d.getErrorCode());
        e.setErrorReason(d.getErrorReason());
        e.setSubmitAt(d.getSubmitAt());
        e.setDlrReceivedAt(d.getDlrReceivedAt());
        e.setEntityId(d.getEntityId());
        e.setTemplateId(d.getTemplateId());
        e.setUnits(d.getUnits());
        e.setRawPayload(rawPayload);
        e.setDedupKey(dedupKey);
        e.setReceiverInstance(instance);
        // Tentative; the processing service finalises it (APPLIED / IGNORED) in the same transaction.
        e.setProcessingStatus(ProcessingStatus.APPLIED);
        return e;
    }

    public DlrEvent rejectedEvent(String source, String messageId, String rawPayload, String reason, String instance) {
        DlrEvent e = new DlrEvent();
        e.setSource(truncate(source == null ? "UNKNOWN" : source, 50));
        e.setMessageId(truncate(messageId, 255));
        e.setRawPayload(rawPayload);
        e.setProcessingStatus(ProcessingStatus.REJECTED);
        e.setRejectionReason(reason);
        e.setReceiverInstance(instance);
        return e;
    }

    public DlrStatusResponse toStatusResponse(DlrMessageStatus s) {
        return new DlrStatusResponse(
                s.getMessageId(),
                s.getSource(),
                true,
                s.getProviderStatus(),
                s.getNormalizedStatus(),
                s.getStatusCode(),
                s.getErrorCode(),
                s.getErrorReason(),
                s.getMobile(),
                s.getUnits(),
                // Provider timestamp when available, otherwise when the receiver recorded the current status
                // (expressed in dlr.timezone so both are comparable).
                s.getDlrReceivedAt() != null ? s.getDlrReceivedAt()
                        : (s.getStatusUpdatedAt() != null
                        ? s.getStatusUpdatedAt().atZoneSameInstant(zone).toLocalDateTime() : null),
                s.getSubmitAt(),
                s.getCorrelationId(),
                s.getExternalMessageId(),
                s.getCampaignId(),
                s.getRequestId(),
                s.getSender(),
                s.getTemplateId(),
                s.getEventCount(),
                s.getDuplicateCount(),
                s.getFirstReceivedAt(),
                s.getStatusUpdatedAt(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public DlrEventView toEventView(DlrEvent e) {
        return new DlrEventView(
                e.getId(), e.getSource(), e.getMessageId(), e.getProviderMessageId(), e.getPartNumber(),
                e.getExternalMessageId(), e.getCorrelationId(),
                e.getMobile(), e.getProviderStatus(), e.getNormalizedStatus(), e.getStatusCode(), e.getErrorCode(),
                e.getErrorReason(), e.getDlrReceivedAt(), e.getUnits(),
                e.getProcessingStatus() == null ? null : e.getProcessingStatus().name(),
                e.getProcessingNote(), e.getRejectionReason(), e.getDuplicateOf(), e.getReceiverInstance(),
                parse(e.getRawPayload()), e.getCreatedAt());
    }

    public BillingEventView toBillingView(DlrBillingEvent e) {
        return new BillingEventView(e.getId(), e.getSource(), e.getBatchId(), e.getBatchIndex(), e.getMessageId(),
                e.getBillingMessageId(), e.getPartNumber(), e.getTransactionType(), e.getProduct(), e.getUnits(),
                plain(e.getSalePrice()), e.getCurrency(), plain(e.getSurcharge()), plain(e.getTotalAmount()),
                e.getProcessingStatus(),
                e.getProcessingNote(), e.getRejectionReason(), e.getDuplicateOf(), parse(e.getRawEvent()),
                e.getCreatedAt());
    }

    /** NUMERIC(18,6) comes back as 1.000000; return 1 instead. */
    private static java.math.BigDecimal plain(java.math.BigDecimal d) {
        if (d == null) {
            return null;
        }
        java.math.BigDecimal s = d.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    /** Raw DLR as received, as JSON. */
    public JsonNode rawDlr(DlrEvent e) {
        if (e == null) {
            return null;
        }
        if (e.getRawBody() != null) {
            try {
                JsonNode n = objectMapper.readTree(e.getRawBody());
                if (n != null && !n.isMissingNode()) {
                    return n;      // same keys, same order as received
                }
            } catch (Exception ignored) {
                // not JSON: fall back to what was stored
            }
        }
        return parse(e.getRawPayload());
    }

    /** The DLR text exactly as received, or (rows stored before raw_body existed) the stored JSON. */
    public String rawDlrText(DlrEvent e) {
        if (e == null) {
            return null;
        }
        return e.getRawBody() != null ? e.getRawBody() : e.getRawPayload();
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return objectMapper.getNodeFactory().textNode(json);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
