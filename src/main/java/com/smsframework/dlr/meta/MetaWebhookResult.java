package com.smsframework.dlr.meta;

import java.util.List;

/**
 * Ack for a Meta webhook. Always answered with HTTP 200 (Meta retries non-2xx and eventually disables the
 * webhook); per-status outcomes are here and every status is persisted, rejected ones included.
 */
public record MetaWebhookResult(String source, int statuses, int applied, int ignored, int duplicates, int rejected,
                                String note, List<Item> results) {

    public record Item(Long eventId, String messageId, String providerStatus, String normalizedStatus,
                       String processingStatus, String currentStatus, String note) {
    }
}
