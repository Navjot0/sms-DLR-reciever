package com.smsframework.dlr.live;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One row of the live feed: a status DLR, billing event or short-link click, newest data first. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LiveFeedItem(
        String kind,               // STATUS | BILLING | CLICK
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
        // the callback as received (status/click: whole payload; billing: this event)
        @com.fasterxml.jackson.annotation.JsonRawValue String raw) {
}
