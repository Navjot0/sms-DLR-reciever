package com.smsframework.dlr.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome of one bulk-push callback ({"batchId":..,"events":[..]}): per-outcome event counts. The events
 * themselves are in webhook_batch_events (GET /api/v1/webhooks/requests/{capture_id}/events).
 *
 * interpreted = applied + ignored + duplicates (recognised DLRs); rejected = recognised format, invalid content;
 * unrecognized = format no adapter knows; malformed = not a JSON object; failed = database error for that event.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchResult(String batchId, int events, int applied, int ignored, int duplicates, int rejected,
                          int unrecognized, int malformed, int failed, Boolean limitExceeded, Integer maxEvents,
                          String source, @JsonIgnore List<String> messageIds) {

    public int interpreted() {
        return applied + ignored + duplicates;
    }

    /** Counts as stored in webhook_requests.batch_summary. */
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("events", events);
        m.put("interpreted", interpreted());
        m.put("applied", applied);
        m.put("ignored", ignored);
        m.put("duplicate", duplicates);
        m.put("rejected", rejected);
        m.put("unrecognized", unrecognized);
        m.put("malformed", malformed);
        m.put("failed", failed);
        if (Boolean.TRUE.equals(limitExceeded)) {
            m.put("limit_exceeded", true);
            m.put("max_events", maxEvents);
        }
        return m;
    }
}
