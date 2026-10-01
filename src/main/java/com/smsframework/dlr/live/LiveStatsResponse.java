package com.smsframework.dlr.live;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Response of GET /api/v1/dlr/live/stats: totals + per-minute activity for the last N minutes. */
public record LiveStatsResponse(
        int windowMinutes,
        OffsetDateTime from,
        OffsetDateTime to,
        long statusCallbacks,
        Map<String, Long> byNormalizedStatus,   // APPLIED status DLRs only
        Map<String, Long> byProcessingStatus,   // every status callback
        long billingEvents,
        BigDecimal billedAmount,
        String billedCurrency,
        long clicks,
        List<Bucket> perMinute) {

    public record Bucket(OffsetDateTime minute, long status, long billing, long click) {
    }
}
