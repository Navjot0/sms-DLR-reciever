package com.smsframework.dlr.click;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Acknowledgement of one click callback (APPLIED | DUPLICATE). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClickResult(
        String eventType,
        String source,
        Long eventId,
        String messageId,
        String providerMessageId,
        String urlKey,
        Integer visitedCount,
        String processingStatus,
        String note) {
}
