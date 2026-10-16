package com.smsframework.dlr.capture;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** webhook_batch_events: one row per event of a bulk DLR callback, in its original position. */
@Repository
public class BatchEventRepository {

    /** One event of a bulk request and what the receiver did with it. */
    public record Row(UUID captureId, String batchId, int index, String messageId, String baseMessageId,
                      Integer partNumber, String recipient, String source, String providerStatus,
                      String normalizedStatus, String outcome, String reason, Long dlrEventId, String rawEvent,
                      String statusCode) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public BatchEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Multi-row insert (JDBC batch). A retried capture never re-inserts the same (capture, index). */
    public void insertAll(List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        SqlParameterSource[] params = rows.stream().map(r -> new MapSqlParameterSource()
                .addValue("capture", r.captureId()).addValue("batch", trunc(r.batchId(), 255))
                .addValue("idx", r.index()).addValue("mid", trunc(r.messageId(), 255))
                .addValue("base", trunc(r.baseMessageId(), 255)).addValue("part", r.partNumber())
                .addValue("rcpt", trunc(r.recipient(), 320)).addValue("source", trunc(r.source(), 50))
                .addValue("pstatus", trunc(r.providerStatus(), 100)).addValue("nstatus", r.normalizedStatus())
                .addValue("outcome", r.outcome()).addValue("reason", clean(r.reason()))
                .addValue("event", r.dlrEventId()).addValue("raw", clean(r.rawEvent()))
                .addValue("code", trunc(r.statusCode(), 50))).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO webhook_batch_events (capture_id, batch_id, event_index, message_id, base_message_id,
                    part_number, recipient, source, provider_status, normalized_status, outcome, reason, dlr_event_id, raw_event,
                    status_code)
                VALUES (:capture, :batch, :idx, :mid, :base, :part, :rcpt, :source, :pstatus, :nstatus, :outcome, :reason,
                    :event, :raw, :code)
                ON CONFLICT (capture_id, event_index) DO NOTHING""", params);
    }

    /** Events of one capture in their original order; outcome filters (e.g. UNRECOGNIZED) when given. */
    public List<Map<String, Object>> page(UUID captureId, String outcome, int offset, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("c", captureId).addValue("o", offset).addValue("l", limit);
        String filter = "";
        if (outcome != null && !outcome.isBlank()) {
            filter = " AND outcome = :out";
            p.addValue("out", outcome.trim().toUpperCase());
        }
        return jdbc.query("SELECT event_index, message_id, base_message_id, part_number, recipient, source, provider_status, status_code, "
                + "normalized_status, outcome, reason, dlr_event_id, raw_event, created_at FROM webhook_batch_events "
                + "WHERE capture_id = :c" + filter + " ORDER BY event_index OFFSET :o LIMIT :l", p, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("index", rs.getInt("event_index"));
                    m.put("message_id", rs.getString("message_id"));
                    m.put("base_message_id", rs.getString("base_message_id"));
                    int part = rs.getInt("part_number");
                    m.put("part_number", rs.wasNull() ? null : part);
                    m.put("recipient", rs.getString("recipient"));
                    m.put("source", rs.getString("source"));
                    m.put("provider_status", rs.getString("provider_status"));
                    m.put("code", rs.getString("status_code"));
                    m.put("normalized_status", rs.getString("normalized_status"));
                    m.put("outcome", rs.getString("outcome"));
                    m.put("reason", rs.getString("reason"));
                    long ev = rs.getLong("dlr_event_id");
                    m.put("dlr_event_id", rs.wasNull() ? null : ev);
                    m.put("raw_event", rs.getString("raw_event"));
                    m.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
                    return m;
                });
    }

    public long count(UUID captureId, String outcome) {
        MapSqlParameterSource p = new MapSqlParameterSource("c", captureId);
        String filter = "";
        if (outcome != null && !outcome.isBlank()) {
            filter = " AND outcome = :out";
            p.addValue("out", outcome.trim().toUpperCase());
        }
        Long n = jdbc.queryForObject("SELECT count(*) FROM webhook_batch_events WHERE capture_id = :c" + filter, p, Long.class);
        return n == null ? 0 : n;
    }

    private static String trunc(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    private static String clean(String v) {
        return v == null ? null : v.replace((char) 0, (char) 0xFFFD);
    }
}
