package com.smsframework.dlr.click;

import java.time.LocalDateTime;

/** One validated short-link click. messageId has the ":part" suffix removed (see providerMessageId). */
public record NormalizedClickEvent(
        String eventType,
        String messageId,
        String providerMessageId,
        Integer partNumber,
        String correlationId,
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
        LocalDateTime providerReceivedAt) {
}
