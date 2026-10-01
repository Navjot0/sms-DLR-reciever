package com.smsframework.dlr.live;

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
import java.util.TreeMap;

/** Read-only queries behind the live UI. Everything comes from PostgreSQL, so every instance shows the same data. */
@Repository
public class LiveRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public LiveRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Events with id greater than the cursors (or the newest `limit` when cursors are 0), newest first. */
    public List<LiveFeedItem> feed(long afterStatus, long afterBilling, long afterClick, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("as", afterStatus).addValue("ab", afterBilling).addValue("ac", afterClick)
                .addValue("limit", limit);
        List<LiveFeedItem> items = new ArrayList<>();
        items.addAll(jdbc.query("""
                SELECT id, created_at, source, message_id, provider_message_id, part_number, mobile, provider_status,
                       normalized_status, processing_status, coalesce(rejection_reason, processing_note) AS note
                FROM dlr_events WHERE id > :as ORDER BY id DESC LIMIT :limit""", p, (rs, n) -> new LiveFeedItem(
                "STATUS", rs.getLong("id"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("source"),
                rs.getString("message_id"), rs.getString("provider_message_id"), integer(rs, "part_number"),
                rs.getString("mobile"), rs.getString("provider_status"), rs.getString("normalized_status"),
                rs.getString("processing_status"), rs.getString("note"),
                null, null, null, null, null, null, null)));
        items.addAll(jdbc.query("""
                SELECT id, created_at, source, message_id, billing_message_id, part_number, processing_status,
                       coalesce(rejection_reason, processing_note) AS note, transaction_type, units, total_amount, currency
                FROM dlr_billing_events WHERE id > :ab ORDER BY id DESC LIMIT :limit""", p, (rs, n) -> new LiveFeedItem(
                "BILLING", rs.getLong("id"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("source"),
                rs.getString("message_id"), rs.getString("billing_message_id"), integer(rs, "part_number"), null,
                null, null, rs.getString("processing_status"), rs.getString("note"),
                rs.getString("transaction_type"), integer(rs, "units"), plain(rs.getBigDecimal("total_amount")),
                rs.getString("currency"), null, null, null)));
        items.addAll(jdbc.query("""
                SELECT id, created_at, source, message_id, provider_message_id, part_number, contact, processing_status,
                       processing_note, url_key, visited_count, device_type
                FROM dlr_click_events WHERE id > :ac ORDER BY id DESC LIMIT :limit""", p, (rs, n) -> new LiveFeedItem(
                "CLICK", rs.getLong("id"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("source"),
                rs.getString("message_id"), rs.getString("provider_message_id"), integer(rs, "part_number"),
                rs.getString("contact"), null, null, rs.getString("processing_status"), rs.getString("processing_note"),
                null, null, null, null, rs.getString("url_key"), integer(rs, "visited_count"),
                rs.getString("device_type"))));
        items.sort(Comparator.comparing(LiveFeedItem::createdAt).thenComparing(LiveFeedItem::id).reversed());
        return items.size() > limit ? items.subList(0, limit) : items;
    }

    public long maxId(String table) {
        Long v = jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(max(id), 0) FROM " + table, Long.class);
        return v == null ? 0 : v;
    }

    public LiveStatsResponse stats(int minutes) {
        MapSqlParameterSource p = new MapSqlParameterSource("m", minutes);
        String window = "created_at > now() - make_interval(mins => :m)";

        Map<String, Long> byProcessing = new LinkedHashMap<>();
        jdbc.query("SELECT processing_status, count(*) c FROM dlr_events WHERE " + window + " GROUP BY 1 ORDER BY 1", p,
                rs -> { byProcessing.put(rs.getString(1), rs.getLong(2)); });
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.query("SELECT normalized_status, count(*) c FROM dlr_events WHERE " + window
                        + " AND processing_status = 'APPLIED' GROUP BY 1 ORDER BY 1", p,
                rs -> { byStatus.put(rs.getString(1), rs.getLong(2)); });
        long statusTotal = byProcessing.values().stream().mapToLong(Long::longValue).sum();

        Object[] billing = jdbc.queryForObject("""
                SELECT count(*),
                       coalesce(sum(CASE WHEN transaction_type = 'debit' THEN total_amount
                                         WHEN transaction_type IN ('credit','refund','reversal') THEN -total_amount
                                         ELSE 0 END), 0),
                       CASE WHEN count(DISTINCT currency) = 1 THEN min(currency)
                            WHEN count(DISTINCT currency) > 1 THEN 'MIXED' END
                FROM dlr_billing_events WHERE processing_status = 'APPLIED'""" + " AND " + window, p,
                (rs, n) -> new Object[]{rs.getLong(1), rs.getBigDecimal(2), rs.getString(3)});
        Long clicks = jdbc.queryForObject("SELECT count(*) FROM dlr_click_events WHERE processing_status = 'APPLIED' AND "
                + window, p, Long.class);

        TreeMap<OffsetDateTime, long[]> buckets = new TreeMap<>();
        for (String[] t : new String[][]{{"dlr_events", "0"}, {"dlr_billing_events", "1"}, {"dlr_click_events", "2"}}) {
            int slot = Integer.parseInt(t[1]);
            jdbc.query("SELECT date_trunc('minute', created_at) AS m, count(*) FROM " + t[0] + " WHERE " + window
                    + " GROUP BY 1", p, rs -> {
                buckets.computeIfAbsent(rs.getObject(1, OffsetDateTime.class), k -> new long[3])[slot] = rs.getLong(2);
            });
        }
        OffsetDateTime now = jdbc.getJdbcTemplate().queryForObject("SELECT now()", OffsetDateTime.class);
        List<LiveStatsResponse.Bucket> perMinute = new ArrayList<>();
        buckets.forEach((m, c) -> perMinute.add(new LiveStatsResponse.Bucket(m, c[0], c[1], c[2])));
        return new LiveStatsResponse(minutes, now.minusMinutes(minutes), now, statusTotal, byStatus, byProcessing,
                (Long) billing[0], plain((BigDecimal) billing[1]), (String) billing[2], clicks == null ? 0 : clicks,
                perMinute);
    }

    private static Integer integer(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static BigDecimal plain(BigDecimal d) {
        if (d == null) {
            return null;
        }
        BigDecimal s = d.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
