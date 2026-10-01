package com.smsframework.dlr.live;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response of GET /api/v1/dlr/live/stats: counts per category (Default SMS, WebEngage, Short URL)
 * plus a time series for the chart. Counts only include accepted callbacks: duplicates and invalid
 * (rejected) payloads are left out.
 */
public record LiveStatsResponse(
        int windowMinutes,          // 0 = all time
        OffsetDateTime from,        // null for all time
        OffsetDateTime to,
        Totals totals,
        List<Category> categories,
        int chartMinutes,           // range the chart covers
        int bucketMinutes,          // 1 (per minute) or 60 (per hour)
        List<Bucket> series) {

    public record Totals(long dlrs, long delivered, long failed, long rejected, long pending,
                         long billingEvents, BigDecimal billedAmount, String billedCurrency, long clicks,
                         long billingCallbacksRejected) {   // whole billing callbacks that could not be processed
    }

    /**
     * One category card. For DEFAULT_SMS / WEBENGAGE the DLR fields are filled; for SHORT_URL the click fields.
     */
    public record Category(String key, String label,
                           long dlrs, long delivered, long failed, long rejected, long pending,
                           long billingEvents, BigDecimal billedAmount, String billedCurrency,
                           long clicks, long clickedMessages, long links) {
    }

    public record Bucket(OffsetDateTime time, long defaultSms, long webengage, long shortUrl) {
    }
}
