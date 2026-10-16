package com.smsframework.dlr.messages;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Individual messages: one row per DLR event, whether it arrived alone (SINGLE) or inside a bulk push (BULK).
 *
 * <ul>
 *   <li>every dlr_events row (recognised DLRs, also invalid / duplicate ones), with its bulk position when it came in
 *       a bulk request (webhook_batch_events) or its capture when it came alone (webhook_requests.dlr_event_id);</li>
 *   <li>bulk events that never became a DLR (unknown format, not an object, database error);</li>
 *   <li>single callbacks no adapter understood but that carry a message id.</li>
 * </ul>
 * Filtered and paged in the database; nothing is loaded wholesale.
 */
@Repository
public class MessageRepository {

    private static final String UNION = """
            SELECT 'DLR' AS rec, e.id AS rid, e.created_at AS received_at,
                   coalesce(e.provider_message_id, e.message_id) AS message_id, e.message_id AS base_message_id,
                   e.mobile, e.provider_status AS status, e.normalized_status, e.status_code AS code, e.error_code,
                   b.batch_id,
                   CASE WHEN e.batch_capture_id IS NOT NULL THEN 'BULK' ELSE 'SINGLE' END AS source_type,
                   b.event_index, coalesce(e.batch_capture_id, w.capture_id) AS capture_id,
                   e.processing_status, coalesce(e.rejection_reason, e.processing_note) AS processing_note,
                   e.source, coalesce(e.raw_body, e.raw_payload::text) AS raw_event, e.id AS dlr_event_id
            FROM dlr_events e
            LEFT JOIN webhook_batch_events b ON e.batch_capture_id IS NOT NULL AND b.dlr_event_id = e.id
            LEFT JOIN webhook_requests w ON e.batch_capture_id IS NULL AND w.dlr_event_id = e.id
            UNION ALL
            SELECT 'EVENT', b.id, b.created_at, b.message_id, b.base_message_id, b.recipient, b.provider_status,
                   NULL, b.status_code, NULL, b.batch_id, 'BULK', b.event_index, b.capture_id,
                   b.outcome, b.reason, b.source, b.raw_event, NULL
            FROM webhook_batch_events b WHERE b.dlr_event_id IS NULL
            UNION ALL
            SELECT 'CAPTURE', w.id, w.received_at, w.extracted_message_id, w.extracted_message_id, w.extracted_recipient,
                   w.provider_status, NULL, NULL, NULL, NULL, 'SINGLE', NULL, w.capture_id,
                   w.interpretation_status, w.interpretation_error, w.detected_source, w.body_text, NULL
            FROM webhook_requests w
            WHERE w.batch_event_count IS NULL AND w.dlr_event_id IS NULL AND w.extracted_message_id IS NOT NULL
              AND w.interpretation_status IN ('UNRECOGNIZED', 'MALFORMED_JSON', 'NOT_JSON')
            """;

    /** Search criteria; null / blank = no filter. */
    public record Filter(String messageId, String messageIdLike, String mobile, String status, String batchId,
                         String sourceType, OffsetDateTime from, OffsetDateTime to, UUID captureId,
                         String processingStatus) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public MessageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first; returns up to limit+1 rows so the caller can tell whether there is another page. */
    public List<Map<String, Object>> search(Filter f, int offset, int limit) {
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        MapSqlParameterSource p = new MapSqlParameterSource("o", offset).addValue("l", limit + 1);
        if (notBlank(f.messageId())) {
            where.append(" AND (m.message_id = :mid OR m.base_message_id = :mid)");
            p.addValue("mid", f.messageId().trim());
        }
        if (notBlank(f.messageIdLike())) {
            where.append(" AND m.message_id ILIKE :midl");
            p.addValue("midl", "%" + like(f.messageIdLike().trim()) + "%");
        }
        if (notBlank(f.mobile())) {
            where.append(" AND m.mobile LIKE :mob");
            p.addValue("mob", "%" + like(f.mobile().trim()) + "%");
        }
        if (notBlank(f.status())) {
            // provider status as sent (DELIVRD) or normalized (DELIVERED), case-insensitive
            where.append(" AND (upper(m.status) = :st OR m.normalized_status = :st)");
            p.addValue("st", f.status().trim().toUpperCase());
        }
        if (notBlank(f.batchId())) {
            where.append(" AND m.batch_id = :batch");
            p.addValue("batch", f.batchId().trim());
        }
        if (notBlank(f.sourceType())) {
            where.append(" AND m.source_type = :stype");
            p.addValue("stype", f.sourceType().trim().toUpperCase());
        }
        if (f.from() != null) {
            where.append(" AND m.received_at >= :from");
            p.addValue("from", f.from());
        }
        if (f.to() != null) {
            where.append(" AND m.received_at <= :to");
            p.addValue("to", f.to());
        }
        if (f.captureId() != null) {
            where.append(" AND m.capture_id = :cap");
            p.addValue("cap", f.captureId());
        }
        if (notBlank(f.processingStatus())) {
            where.append(" AND m.processing_status = :ps");
            p.addValue("ps", f.processingStatus().trim().toUpperCase());
        }
        return jdbc.query("SELECT * FROM (" + UNION + ") m" + where
                + " ORDER BY m.received_at DESC, m.rid DESC OFFSET :o LIMIT :l", p, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("received_at", rs.getObject("received_at", OffsetDateTime.class));
                    m.put("message_id", rs.getString("message_id"));
                    m.put("base_message_id", rs.getString("base_message_id"));
                    m.put("mobile", rs.getString("mobile"));
                    m.put("status", rs.getString("status"));                       // provider's own value
                    m.put("normalized_status", rs.getString("normalized_status"));
                    m.put("code", rs.getString("code"));
                    m.put("error_code", rs.getString("error_code"));
                    m.put("batch_id", rs.getString("batch_id"));
                    m.put("source_type", rs.getString("source_type"));
                    int idx = rs.getInt("event_index");
                    m.put("event_index", rs.wasNull() ? null : idx);
                    m.put("capture_id", rs.getObject("capture_id", UUID.class));
                    m.put("processing_status", rs.getString("processing_status"));  // what the receiver did
                    m.put("processing_note", rs.getString("processing_note"));
                    m.put("source", rs.getString("source"));
                    long ev = rs.getLong("dlr_event_id");
                    m.put("dlr_event_id", rs.wasNull() ? null : ev);
                    m.put("record", rs.getString("rec"));
                    m.put("raw_event", rs.getString("raw_event"));
                    return m;
                });
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
