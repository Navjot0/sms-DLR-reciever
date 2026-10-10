package com.smsframework.dlr.capture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** webhook_requests: raw captures (insert-only body) plus their derived interpretation. */
@Repository
public class WebhookCaptureRepository {

    /** Columns for lists: everything except the body itself (a 300-character preview instead). */
    private static final String SUMMARY = """
            id, capture_id, received_at, http_method, request_path, query_string, content_type, body_size_bytes,
            body_truncated, body_encoding, remote_address, interpretation_status, interpretation_error, detected_source,
            extracted_message_id, extracted_recipient, provider_status, normalized_status, dlr_event_id,
            left(body_text, 300) AS body_preview""";

    private static final TypeReference<Map<String, List<String>>> MULTI_MAP = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WebhookCaptureRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Inserts the capture (auto-commit: durable once this returns). Returns id and received_at. */
    public CapturedRequest insert(CapturedRequest c) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("capture_id", c.captureId())
                .addValue("method", c.method())
                .addValue("path", c.path())
                .addValue("qs", c.queryString())
                .addValue("qp", json(c.queryParams()))
                .addValue("headers", json(c.headers()))
                .addValue("ct", c.contentType())
                .addValue("body", c.body(), Types.BINARY)
                .addValue("size", c.bodySize())
                .addValue("truncated", c.bodyTruncated())
                .addValue("enc", c.bodyEncoding())
                // TEXT cannot hold NUL; raw_body keeps the exact bytes, body_text is only for search/display
                .addValue("text", clean(c.bodyText()))
                .addValue("remote", c.remoteAddress());
        return jdbc.queryForObject("""
                INSERT INTO webhook_requests (capture_id, http_method, request_path, query_string, query_params, headers,
                    content_type, raw_body, body_size_bytes, body_truncated, body_encoding, body_text, remote_address)
                VALUES (:capture_id, :method, :path, :qs, CAST(:qp AS jsonb), CAST(:headers AS jsonb), :ct, :body, :size,
                    :truncated, :enc, :text, :remote)
                RETURNING id, received_at""", p,
                (rs, n) -> c.withId(rs.getLong("id"), rs.getObject("received_at", OffsetDateTime.class)));
    }

    public void updateInterpretation(UUID captureId, Interpretation i) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", captureId)
                .addValue("status", i.status().name())
                .addValue("error", clean(i.error()))
                .addValue("source", trunc(clean(i.source()), 50))
                .addValue("mid", trunc(clean(i.messageId()), 255))
                .addValue("mids", i.messageIds() == null || i.messageIds().isEmpty() ? null
                        : "{" + String.join(",", i.messageIds().stream().map(x -> pgArrayItem(clean(x))).toList()) + "}")
                .addValue("recipient", trunc(clean(i.recipient()), 320))
                .addValue("pstatus", trunc(clean(i.providerStatus()), 100))
                .addValue("nstatus", i.normalizedStatus())
                .addValue("event", i.dlrEventId())
                .addValue("result", i.resultJson() == null ? null : i.resultJson().replace("\\u0000", "\\uFFFD"));
        jdbc.update("""
                UPDATE webhook_requests SET interpretation_status = :status, interpretation_error = :error,
                    detected_source = :source, extracted_message_id = :mid, message_ids = CAST(:mids AS text[]),
                    extracted_recipient = :recipient, provider_status = :pstatus, normalized_status = :nstatus,
                    dlr_event_id = :event, interpretation = CAST(:result AS jsonb)
                WHERE capture_id = :id""", p);
    }

    /**
     * Newest first. afterId: only newer captures (live polling); beforeId: only older ones (load more).
     * status: an interpretation status, or the groups "interpreted" / "unrecognized" / "errors".
     * q: capture id, message id, or text found in the body or headers.
     */
    public List<Map<String, Object>> list(Long afterId, Long beforeId, String status, String source, String messageId,
                                          String q, int limit) {
        StringBuilder where = new StringBuilder("WHERE TRUE");
        MapSqlParameterSource p = new MapSqlParameterSource("limit", limit);
        if (afterId != null) {
            where.append(" AND id > :after");
            p.addValue("after", afterId);
        }
        if (beforeId != null) {
            where.append(" AND id < :before");
            p.addValue("before", beforeId);
        }
        if (status != null && !status.isBlank()) {
            List<String> statuses = switch (status.trim().toLowerCase()) {
                case "interpreted" -> List.of("INTERPRETED", "PARTIAL");
                case "unrecognized", "unrecognised" -> List.of("UNRECOGNIZED", "NOT_JSON", "EMPTY", "NO_DLR");
                case "errors" -> List.of("ERROR", "TOO_LARGE", "PENDING");
                default -> List.of(status.trim().toUpperCase());
            };
            where.append(" AND interpretation_status IN (:statuses)");
            p.addValue("statuses", statuses);
        }
        if (source != null && !source.isBlank()) {
            where.append(" AND detected_source = :source");
            p.addValue("source", source.trim().toUpperCase());
        }
        if (messageId != null && !messageId.isBlank()) {
            where.append(" AND (extracted_message_id IN (:mids) OR message_ids && ARRAY[:mids]::text[])");
            p.addValue("mids", idVariants(messageId));
        }
        if (q != null && !q.isBlank()) {
            String term = q.trim();
            where.append(" AND (capture_id::text = :q OR extracted_message_id = :q OR :q = ANY (message_ids)"
                    + " OR body_text ILIKE :like OR headers::text ILIKE :like OR query_string ILIKE :like)");
            p.addValue("q", term).addValue("like", "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return jdbc.query("SELECT " + SUMMARY + " FROM webhook_requests " + where + " ORDER BY id DESC LIMIT :limit",
                p, (rs, n) -> summary(rs));
    }

    public long maxId() {
        Long v = jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(max(id), 0) FROM webhook_requests", Long.class);
        return v == null ? 0 : v;
    }

    /** Full detail of one capture (body as text when it is text, base64 otherwise). */
    public Optional<Map<String, Object>> detail(UUID captureId) {
        return jdbc.query("SELECT " + SUMMARY + ", query_params::text AS qp, headers::text AS hd, body_text, "
                        + "CASE WHEN body_text IS NULL AND raw_body IS NOT NULL THEN encode(raw_body, 'base64') END AS body_base64, "
                        + "message_ids, interpretation::text AS interp FROM webhook_requests WHERE capture_id = :id",
                new MapSqlParameterSource("id", captureId), (rs, n) -> {
                    Map<String, Object> m = summary(rs);
                    m.remove("body_preview");
                    m.put("query_params", readMulti(rs.getString("qp")));
                    m.put("headers", readMulti(rs.getString("hd")));
                    m.put("body_text", rs.getString("body_text"));
                    m.put("body_base64", rs.getString("body_base64"));
                    java.sql.Array ids = rs.getArray("message_ids");
                    m.put("message_ids", ids == null ? null : List.of((String[]) ids.getArray()));
                    m.put("interpretation", readTree(rs.getString("interp")));
                    return m;
                }).stream().findFirst();
    }

    /** Original body bytes and content type. Empty Optional when there is no such capture. */
    public Optional<RawBody> rawBody(UUID captureId) {
        return jdbc.query("SELECT raw_body, content_type, body_truncated FROM webhook_requests WHERE capture_id = :id",
                new MapSqlParameterSource("id", captureId),
                (rs, n) -> new RawBody(rs.getBytes("raw_body"), rs.getString("content_type"),
                        rs.getBoolean("body_truncated"))).stream().findFirst();
    }

    /**
     * Newest capture carrying one of these message ids (any interpretation status), optionally only with the given
     * interpretation statuses.
     */
    public Optional<Map<String, Object>> latestForMessage(List<String> messageIds, List<String> statuses) {
        MapSqlParameterSource p = new MapSqlParameterSource("mids", messageIds);
        String filter = "";
        if (statuses != null && !statuses.isEmpty()) {
            filter = " AND interpretation_status IN (:st)";
            p.addValue("st", statuses);
        }
        return jdbc.query("SELECT " + SUMMARY + " FROM webhook_requests WHERE (extracted_message_id IN (:mids) "
                        + "OR message_ids && ARRAY[:mids]::text[])" + filter + " ORDER BY id DESC LIMIT 1", p,
                (rs, n) -> summary(rs)).stream().findFirst();
    }

    /** Number of captures per interpretation status for these message ids. */
    public Map<String, Long> countsForMessage(List<String> messageIds) {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT interpretation_status, count(*) AS n FROM webhook_requests WHERE extracted_message_id IN (:mids) "
                        + "OR message_ids && ARRAY[:mids]::text[] GROUP BY interpretation_status ORDER BY 1",
                new MapSqlParameterSource("mids", messageIds),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> m.put(rs.getString(1), rs.getLong(2)));
        return m;
    }

    /**
     * Captures store every id they carry plus its form without ":part", so "abc" finds all captures of the
     * message and "abc:2" only those of that recipient.
     */
    private List<String> idVariants(String messageId) {
        return List.of(messageId.trim());
    }

    /** Public form of {@link #idVariants(String)} for callers that look a message up. */
    public List<String> messageIdVariants(String messageId) {
        return idVariants(messageId);
    }

    public record RawBody(byte[] bytes, String contentType, boolean truncated) {
    }

    private Map<String, Object> summary(ResultSet rs) throws SQLException {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("capture_id", rs.getObject("capture_id", UUID.class));
        m.put("received_at", rs.getObject("received_at", OffsetDateTime.class));
        m.put("method", rs.getString("http_method"));
        m.put("path", rs.getString("request_path"));
        m.put("query_string", rs.getString("query_string"));
        m.put("content_type", rs.getString("content_type"));
        m.put("body_size_bytes", rs.getLong("body_size_bytes"));
        m.put("body_truncated", rs.getBoolean("body_truncated"));
        m.put("body_encoding", rs.getString("body_encoding"));
        m.put("remote_address", rs.getString("remote_address"));
        m.put("interpretation_status", rs.getString("interpretation_status"));
        m.put("interpretation_error", rs.getString("interpretation_error"));
        m.put("detected_source", rs.getString("detected_source"));
        m.put("extracted_message_id", rs.getString("extracted_message_id"));
        m.put("extracted_recipient", rs.getString("extracted_recipient"));
        m.put("provider_status", rs.getString("provider_status"));
        m.put("normalized_status", rs.getString("normalized_status"));
        long ev = rs.getLong("dlr_event_id");
        m.put("dlr_event_id", rs.wasNull() ? null : ev);
        m.put("body_preview", rs.getString("body_preview"));
        return m;
    }

    /** PostgreSQL text cannot hold NUL. */
    private static String clean(String v) {
        return v == null ? null : v.replace((char) 0, (char) 0xFFFD);
    }

    private String json(Object o) {
        try {
            // jsonb rejects the \u0000 escape (e.g. a %00 in the query string)
            return o == null ? null : mapper.writeValueAsString(o).replace("\\u0000", "\\uFFFD");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, List<String>> readMulti(String s) {
        try {
            return s == null ? null : mapper.readValue(s, MULTI_MAP);
        } catch (Exception e) {
            return null;
        }
    }

    private Object readTree(String s) {
        try {
            return s == null ? null : mapper.readTree(s);
        } catch (Exception e) {
            return s;
        }
    }

    private static String pgArrayItem(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
