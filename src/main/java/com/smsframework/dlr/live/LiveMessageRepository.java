package com.smsframework.dlr.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.util.MessageIdParts;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Message lookup for the live UI, scoped to what was clicked.
 *
 * <p>A bulk campaign sends one message_id for every recipient and suffixes it per recipient ("&lt;id&gt;:2329").
 * The core status API keys on the base id, so it reports the campaign's first final status. Here, when the
 * requested id carries a suffix, status, billing, clicks and the timeline are those of that one recipient,
 * plus a per-status count of all recipients of the message.
 */
@Repository
public class LiveMessageRepository {

    private static final String FINAL = "normalized_status IN ('DELIVERED', 'FAILED', 'EXPIRED', 'REJECTED')";
    /** Same rule as the state machine: the first final status wins; without one, the latest status. */
    private static final String STATUS_ORDER =
            "(" + FINAL + ") DESC, CASE WHEN " + FINAL + " THEN id ELSE -id END";
    private static final String ACCEPTED = "processing_status IN ('APPLIED', 'IGNORED')";
    private static final int TIMELINE_LIMIT = 300;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String separator;

    public LiveMessageRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, DlrProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.separator = properties.getMessageIdPartSeparator();
    }

    public Map<String, Object> lookup(String requestedId) {
        String id = requestedId.trim();
        MessageIdParts.Parsed parsed = MessageIdParts.parse(id, separator);
        String base = parsed.messageId();
        Integer part = parsed.part();
        MapSqlParameterSource p = new MapSqlParameterSource("m", base).addValue("p", part);
        String partFilter = part == null ? "" : " AND part_number = :p";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested_id", id);
        out.put("message_id", base);
        out.put("part", part);
        out.put("status", part == null ? messageStatus(p) : recipientStatus(p));
        out.put("recipients", recipients(p));
        out.put("billing", billing(p, partFilter));
        out.put("clicks", clicks(p, partFilter));
        out.put("timeline", timeline(p, partFilter));
        return out;
    }

    private Map<String, Object> recipientStatus(MapSqlParameterSource p) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT source, provider_message_id, part_number, mobile, provider_status, normalized_status, status_code,
                       error_reason, correlation_id, coalesce(dlr_received_at::text, '') AS dlr_received_at, created_at
                FROM dlr_events WHERE message_id = :m AND part_number = :p AND\s""" + ACCEPTED
                + " ORDER BY " + STATUS_ORDER + " LIMIT 1", p, (rs, n) -> statusRow(rs));
        return rows.isEmpty() ? Map.of("received", false) : rows.get(0);
    }

    private Map<String, Object> messageStatus(MapSqlParameterSource p) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT source, NULL AS provider_message_id, NULL::int AS part_number, mobile, provider_status,
                       normalized_status, status_code, error_reason, correlation_id,
                       coalesce(dlr_received_at::text, '') AS dlr_received_at, status_updated_at AS created_at
                FROM dlr_message_status WHERE message_id = :m""", p, (rs, n) -> statusRow(rs));
        return rows.isEmpty() ? Map.of("received", false) : rows.get(0);
    }

    private static Map<String, Object> statusRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("received", true);
        m.put("source", rs.getString("source"));
        m.put("provider_message_id", rs.getString("provider_message_id"));
        m.put("mobile", rs.getString("mobile"));
        m.put("status", rs.getString("normalized_status"));
        m.put("provider_status", rs.getString("provider_status"));
        m.put("status_code", rs.getString("status_code"));
        m.put("error_reason", rs.getString("error_reason"));
        m.put("correlation_id", rs.getString("correlation_id"));
        String providerTime = rs.getString("dlr_received_at");
        m.put("dlr_received_at", providerTime == null || providerTime.isEmpty() ? null : providerTime);
        m.put("received_at", rs.getObject("created_at", OffsetDateTime.class));
        return m;
    }

    /** Per-status count over every recipient (suffix) of the message; empty when it has none. */
    private Map<String, Object> recipients(MapSqlParameterSource p) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.query("SELECT normalized_status, count(*) FROM (SELECT DISTINCT ON (part_number) part_number, normalized_status "
                + "FROM dlr_events WHERE message_id = :m AND part_number IS NOT NULL AND " + ACCEPTED
                + " ORDER BY part_number, " + STATUS_ORDER + ") t GROUP BY 1 ORDER BY 2 DESC", p,
                rs -> { byStatus.put(rs.getString(1), rs.getLong(2)); });
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("by_status", byStatus);
        return m;
    }

    private Map<String, Object> billing(MapSqlParameterSource p, String partFilter) {
        return jdbc.queryForObject("""
                SELECT count(*) AS events, coalesce(sum(units), 0) AS units,
                       coalesce(sum(CASE WHEN transaction_type = 'debit' THEN total_amount
                                         WHEN transaction_type IN ('credit','refund','reversal') THEN -total_amount
                                         ELSE 0 END), 0) AS amount,
                       min(currency) AS currency
                FROM dlr_billing_events WHERE message_id = :m AND processing_status = 'APPLIED'""" + partFilter, p,
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("billed", rs.getLong("events") > 0);
                    m.put("events", rs.getLong("events"));
                    m.put("units", rs.getLong("units"));
                    m.put("amount", plain(rs.getBigDecimal("amount")));
                    m.put("currency", rs.getString("currency"));
                    return m;
                });
    }

    private Map<String, Object> clicks(MapSqlParameterSource p, String partFilter) {
        return jdbc.queryForObject("""
                SELECT count(*) AS clicks, max(visited_count) AS visited, min(created_at) AS first_at,
                       max(created_at) AS last_at, string_agg(DISTINCT url_key, ', ') AS links,
                       (array_agg(device_type ORDER BY id DESC))[1] AS device
                FROM dlr_click_events WHERE message_id = :m AND processing_status = 'APPLIED'""" + partFilter, p,
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("clicked", rs.getLong("clicks") > 0);
                    m.put("clicks", rs.getLong("clicks"));
                    m.put("visited_count", rs.getObject("visited"));
                    m.put("links", rs.getString("links"));
                    m.put("first_clicked_at", rs.getObject("first_at", OffsetDateTime.class));
                    m.put("last_clicked_at", rs.getObject("last_at", OffsetDateTime.class));
                    m.put("last_device", rs.getString("device"));
                    return m;
                });
    }

    private List<Map<String, Object>> timeline(MapSqlParameterSource p, String partFilter) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.addAll(jdbc.query("SELECT id, created_at, part_number, mobile, provider_status, normalized_status, raw_payload "
                + "FROM dlr_events WHERE message_id = :m AND " + ACCEPTED + partFilter
                + " ORDER BY id DESC LIMIT " + TIMELINE_LIMIT, p, (rs, n) -> item(rs, "STATUS", m -> {
                    m.put("mobile", rs.getString("mobile"));
                    m.put("status", rs.getString("normalized_status"));
                    m.put("provider_status", rs.getString("provider_status"));
                })));
        items.addAll(jdbc.query("SELECT id, created_at, part_number, transaction_type, units, total_amount, currency, "
                + "raw_event AS raw_payload FROM dlr_billing_events WHERE message_id = :m AND processing_status = 'APPLIED'"
                + partFilter + " ORDER BY id DESC LIMIT " + TIMELINE_LIMIT, p, (rs, n) -> item(rs, "BILLING", m -> {
                    m.put("transaction_type", rs.getString("transaction_type"));
                    m.put("units", rs.getObject("units"));
                    m.put("amount", plain(rs.getBigDecimal("total_amount")));
                    m.put("currency", rs.getString("currency"));
                })));
        items.addAll(jdbc.query("SELECT id, created_at, part_number, contact, url_key, visited_count, device_type, browser, "
                + "raw_payload FROM dlr_click_events WHERE message_id = :m AND processing_status = 'APPLIED'" + partFilter
                + " ORDER BY id DESC LIMIT " + TIMELINE_LIMIT, p, (rs, n) -> item(rs, "CLICK", m -> {
                    m.put("mobile", rs.getString("contact"));
                    m.put("url_key", rs.getString("url_key"));
                    m.put("visited_count", rs.getObject("visited_count"));
                    m.put("device_type", rs.getString("device_type"));
                    m.put("browser", rs.getString("browser"));
                })));
        items.sort(Comparator.comparing((Map<String, Object> m) -> (OffsetDateTime) m.get("created_at")));
        return items.size() > TIMELINE_LIMIT ? items.subList(items.size() - TIMELINE_LIMIT, items.size()) : items;
    }

    private interface Filler {
        void fill(Map<String, Object> m) throws SQLException;
    }

    private Map<String, Object> item(ResultSet rs, String kind, Filler filler) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("id", rs.getLong("id"));
        m.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
        int part = rs.getInt("part_number");
        m.put("part", rs.wasNull() ? null : part);
        filler.fill(m);
        try {
            String raw = rs.getString("raw_payload");
            m.put("raw", raw == null ? null : objectMapper.readTree(raw));
        } catch (Exception e) {
            m.put("raw", rs.getString("raw_payload"));
        }
        return m;
    }

    private static BigDecimal plain(BigDecimal d) {
        if (d == null) {
            return null;
        }
        BigDecimal s = d.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
