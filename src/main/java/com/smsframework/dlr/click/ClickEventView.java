package com.smsframework.dlr.click;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** One persisted click event (dlr_click_events row). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClickEventView(
        Long id,
        String source,
        String messageId,
        String providerMessageId,
        Integer partNumber,
        String contact,
        String urlKey,
        String urlType,
        String shortUrl,
        String destinationUrl,
        String channel,
        Integer visitedCount,
        String ipAddress,
        String operatingSystem,
        String operatingSystemVersion,
        String browser,
        String browserVersion,
        String deviceType,
        LocalDateTime clickedAt,
        String processingStatus,
        String processingNote,
        Long duplicateOf,
        JsonNode rawPayload,
        OffsetDateTime createdAt) {
}
