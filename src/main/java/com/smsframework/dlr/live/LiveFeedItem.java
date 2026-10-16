package com.smsframework.dlr.live;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One row of the live feed: a status DLR, billing event, short-link click, or one BATCH row per bulk-push request
 * (its DLRs are not listed one by one; open the batch to see them).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LiveFeedItem(
        String kind,               // STATUS | BILLING | CLICK | BATCH
        long id,
        OffsetDateTime createdAt,
        String source,
        String messageId,
        String providerMessageId,
        Integer partNumber,
        String mobile,
        String providerStatus,
        String normalizedStatus,
        String processingStatus,
        String note,
        // billing
        String transactionType,
        Integer units,
        BigDecimal totalAmount,
        String currency,
        // click
        String urlKey,
        Integer visitedCount,
        String deviceType,
        // RCS operator (JIO / DOTGO / VI / AIRTEL), WhatsApp pricing category, SMS service
        String service,
        // the callback as received (status/click: whole payload; billing: this event)
        @com.fasterxml.jackson.annotation.JsonRawValue String raw,
        // the DLR text exactly as received (status DLRs); null when only the normalized JSON is available
        String rawText,
        // BATCH rows: the request, its batch id, number of events and per-outcome counts
        String captureId,
        String batchId,
        Integer eventCount,
        @com.fasterxml.jackson.annotation.JsonRawValue String batchSummary,
        String interpretationStatus) {

    public LiveFeedItem(String kind, long id, OffsetDateTime createdAt, String source, String messageId,
                        String providerMessageId, Integer partNumber, String mobile, String providerStatus,
                        String normalizedStatus, String processingStatus, String note, String transactionType,
                        Integer units, BigDecimal totalAmount, String currency, String urlKey, Integer visitedCount,
                        String deviceType, String service, String raw, String rawText) {
        this(kind, id, createdAt, source, messageId, providerMessageId, partNumber, mobile, providerStatus,
                normalizedStatus, processingStatus, note, transactionType, units, totalAmount, currency, urlKey,
                visitedCount, deviceType, service, raw, rawText, null, null, null, null, null);
    }
}
