package com.smsframework.dlr.retention;

import com.smsframework.dlr.config.DlrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deletes data older than dlr.retention.days (default 7): status DLRs, message states, billing events and
 * short-link clicks. Runs on every instance on a timer; deletes are done in small batches, each its own
 * transaction, so callbacks are never blocked and two instances running at once only split the work.
 */
@Service
public class DataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

    /** Result of one clean-up run. */
    public record Result(boolean enabled, int retentionDays, OffsetDateTime cutoff, Map<String, Long> deleted,
                         long total, boolean moreRemaining) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final DlrProperties.Retention config;

    public DataRetentionService(NamedParameterJdbcTemplate jdbc, DlrProperties properties) {
        this.jdbc = jdbc;
        this.config = properties.getRetention();
    }

    @Scheduled(initialDelayString = "${dlr.retention.initial-delay-ms:60000}",
            fixedDelayString = "${dlr.retention.interval-ms:3600000}")
    public void scheduled() {
        if (!config.isEnabled()) {
            return;
        }
        try {
            Result r = purge();
            if (r.total() > 0) {
                log.info("Retention: deleted {} rows older than {} days ({}){}", r.total(), r.retentionDays(),
                        r.deleted(), r.moreRemaining() ? "; more remain for the next run" : "");
            }
        } catch (RuntimeException e) {
            log.error("Retention run failed; it will be retried on the next run", e);
        }
    }

    /** Deletes everything older than the retention period, up to max-batches-per-run batches per table. */
    public Result purge() {
        int days = Math.max(1, config.getDays());
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        MapSqlParameterSource p = new MapSqlParameterSource("cutoff", cutoff).addValue("n", config.getBatchSize());
        Map<String, Long> deleted = new LinkedHashMap<>();
        boolean more = false;

        // 1. message states that received nothing within the period (frees their last_event_id reference)
        more |= batch("dlr_message_status", deleted, p, """
                DELETE FROM dlr_message_status WHERE message_id IN (
                    SELECT message_id FROM dlr_message_status WHERE last_received_at < :cutoff LIMIT :n)""");
        // 2. status DLR events; an old event that still backs the state of an active message is kept
        more |= batch("dlr_events", deleted, p, """
                DELETE FROM dlr_events WHERE id IN (
                    SELECT e.id FROM dlr_events e WHERE e.created_at < :cutoff
                      AND NOT EXISTS (SELECT 1 FROM dlr_message_status s WHERE s.last_event_id = e.id)
                    ORDER BY e.id LIMIT :n)""");
        // 3. billing events and short-link clicks
        more |= batch("dlr_billing_events", deleted, p, """
                DELETE FROM dlr_billing_events WHERE id IN (
                    SELECT id FROM dlr_billing_events WHERE created_at < :cutoff ORDER BY id LIMIT :n)""");
        more |= batch("dlr_click_events", deleted, p, """
                DELETE FROM dlr_click_events WHERE id IN (
                    SELECT id FROM dlr_click_events WHERE created_at < :cutoff ORDER BY id LIMIT :n)""");

        long total = deleted.values().stream().mapToLong(Long::longValue).sum();
        return new Result(config.isEnabled(), days, cutoff, deleted, total, more);
    }

    /** Runs the delete in batches; returns true when the batch limit was hit with rows possibly left. */
    private boolean batch(String table, Map<String, Long> deleted, MapSqlParameterSource p, String sql) {
        long count = 0;
        int batches = 0;
        int n;
        do {
            n = jdbc.update(sql, p);
            count += n;
            batches++;
        } while (n >= config.getBatchSize() && batches < config.getMaxBatchesPerRun());
        deleted.put(table, count);
        return n >= config.getBatchSize();
    }
}
