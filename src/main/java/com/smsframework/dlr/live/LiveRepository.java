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
                FROM dlr_events WHERE id > :as AND processing_status NOT IN ('DUPLICATE', 'REJECTED') ORDER BY id DESC LIMIT :limit""", p, (rs, n) -> new LiveFeedItem(
                "STATUS", rs.getLong("id"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("source"),
                rs.getString("message_id"), rs.getString("provider_message_id"), integer(rs, "part_number"),
                rs.getString("mobile"), rs.getString("provider_status"), rs.getString("normalized_status"),
                rs.getString("processing_status"), rs.getString("note"),
                null, null, null, null, null, null, null)));
        items.addAll(jdbc.query("""
                SELECT id, created_at, source, message_id, billing_message_id, part_number, processing_status,
                       coalesce(rejection_reason, processing_note) AS note, transaction_type, units, total_amount, currency
                FROM dlr_billing_events WHERE id > :ab AND processing_status NOT IN ('DUPLICATE', 'REJECTED') ORDER BY id DESC LIMIT :limit""", p, (rs, n) -> new LiveFeedItem(
                "BILLING", rs.getLong("id"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("source"),
                rs.getString("message_id"), rs.getString("billing_message_id"), integer(rs, "part_number"), null,
                null, null, rs.getString("processing_status"), rs.getString("note"),
                rs.getString("transaction_type"), integer(rs, "units"), plain(rs.getBigDecimal("total_amount")),
                rs.getString("currency"), null, null, null)));
        items.addAll(jdbc.query("""
                SELECT id, created_at, source, message_id, provider_message_id, part_number, contact, processing_status,
                       processing_note, url_key, visited_count, device_type
                FROM dlr_click_events WHERE id > :ac AND processing_status NOT IN ('DUPLICATE', 'REJECTED') ORDER BY id DESC LIMIT :limit""", p, (rs, n) -> new LiveFeedItem(
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

    static final String DEFAULT_SMS = "DEFAULT_SMS";
    static final String WEBENGAGE = "WEBENGAGE";
    static final String SHORT_URL = "SHORT_URL";
    private static final String ACCEPTED = "processing_status IN ('APPLIED', 'IGNORED')";

    /**
     * Counts per category for the last {@code minutes} (0 = all time). Only accepted callbacks are counted:
     * duplicates and invalid payloads are excluded.
     */
    public LiveStatsResponse stats(int minutes) {
        MapSqlParameterSource p = new MapSqlParameterSource("m", minutes);
        String window = minutes > 0 ? "created_at > now() - make_interval(mins => :m)" : "TRUE";

        // status DLRs: source x normalized status
        Map<String, Map<String, Long>> status = new LinkedHashMap<>();
        status.put(DEFAULT_SMS, new LinkedHashMap<>());
        status.put(WEBENGAGE, new LinkedHashMap<>());
        jdbc.query("SELECT source, normalized_status, count(*) FROM dlr_events WHERE " + ACCEPTED + " AND " + window
                + " GROUP BY 1, 2", p, rs -> {
            status.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>())
                    .put(String.valueOf(rs.getString(2)), rs.getLong(3));
        });

        // billing per source (net of credits/refunds)
        Map<String, Object[]> billing = new LinkedHashMap<>();
        jdbc.query("""
                SELECT source, count(*),
                       coalesce(sum(CASE WHEN transaction_type = 'debit' THEN total_amount
                                         WHEN transaction_type IN ('credit','refund','reversal') THEN -total_amount
                                         ELSE 0 END), 0),
                       CASE WHEN count(DISTINCT currency) = 1 THEN min(currency)
                            WHEN count(DISTINCT currency) > 1 THEN 'MIXED' END
                FROM dlr_billing_events WHERE processing_status = 'APPLIED'""" + " AND " + window + " GROUP BY 1", p,
                rs -> { billing.put(rs.getString(1), new Object[]{rs.getLong(2), rs.getBigDecimal(3), rs.getString(4)}); });

        long[] click = jdbc.queryForObject("SELECT count(*), count(DISTINCT message_id), count(DISTINCT url_key) "
                + "FROM dlr_click_events WHERE processing_status = 'APPLIED' AND " + window, p,
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});

        List<LiveStatsResponse.Category> categories = new ArrayList<>();
        long tDlrs = 0, tDel = 0, tFail = 0, tRej = 0, tPend = 0, tBillEv = 0;
        BigDecimal tAmount = BigDecimal.ZERO;
        java.util.Set<String> currencies = new java.util.TreeSet<>();
        for (Map.Entry<String, Map<String, Long>> e : status.entrySet()) {
            Map<String, Long> by = e.getValue();
            long dlrs = by.values().stream().mapToLong(Long::longValue).sum();
            long delivered = by.getOrDefault("DELIVERED", 0L);
            long failed = by.getOrDefault("FAILED", 0L) + by.getOrDefault("EXPIRED", 0L);
            long rejected = by.getOrDefault("REJECTED", 0L);
            long pending = dlrs - delivered - failed - rejected;      // SENT / UNKNOWN
            Object[] b = billing.getOrDefault(e.getKey(), new Object[]{0L, BigDecimal.ZERO, null});
            categories.add(new LiveStatsResponse.Category(e.getKey(), label(e.getKey()), dlrs, delivered, failed,
                    rejected, pending, (Long) b[0], plain((BigDecimal) b[1]), (String) b[2], 0, 0, 0));
            tDlrs += dlrs; tDel += delivered; tFail += failed; tRej += rejected; tPend += pending;
        }
        for (Object[] b : billing.values()) {
            tBillEv += (Long) b[0];
            tAmount = tAmount.add((BigDecimal) b[1]);
            if (b[2] != null) {
                currencies.add((String) b[2]);
            }
        }
        categories.add(new LiveStatsResponse.Category(SHORT_URL, label(SHORT_URL), 0, 0, 0, 0, 0, 0, null, null,
                click[0], click[1], click[2]));
        String currency = currencies.isEmpty() ? null : currencies.size() == 1 ? currencies.iterator().next() : "MIXED";
        LiveStatsResponse.Totals totals = new LiveStatsResponse.Totals(tDlrs, tDel, tFail, tRej, tPend, tBillEv,
                plain(tAmount), currency, click[0]);

        // chart: per minute up to 24 h, per hour beyond; "all time" charts the last 7 days
        int chartMinutes = minutes > 0 ? minutes : 7 * 24 * 60;
        int bucketMinutes = chartMinutes > 24 * 60 ? 60 : 1;
        String unit = bucketMinutes == 60 ? "hour" : "minute";
        MapSqlParameterSource cp = new MapSqlParameterSource("m", chartMinutes);
        String chartWindow = "created_at > now() - make_interval(mins => :m)";
        TreeMap<OffsetDateTime, long[]> buckets = new TreeMap<>();
        jdbc.query("SELECT date_trunc('" + unit + "', created_at), source, count(*) FROM dlr_events WHERE " + ACCEPTED
                + " AND " + chartWindow + " GROUP BY 1, 2", cp, rs -> {
            int slot = WEBENGAGE.equals(rs.getString(2)) ? 1 : 0;
            buckets.computeIfAbsent(rs.getObject(1, OffsetDateTime.class), k -> new long[3])[slot] += rs.getLong(3);
        });
        jdbc.query("SELECT date_trunc('" + unit + "', created_at), count(*) FROM dlr_click_events "
                + "WHERE processing_status = 'APPLIED' AND " + chartWindow + " GROUP BY 1", cp, rs -> {
            buckets.computeIfAbsent(rs.getObject(1, OffsetDateTime.class), k -> new long[3])[2] += rs.getLong(2);
        });
        List<LiveStatsResponse.Bucket> series = new ArrayList<>();
        buckets.forEach((t, c) -> series.add(new LiveStatsResponse.Bucket(t, c[0], c[1], c[2])));

        OffsetDateTime now = jdbc.getJdbcTemplate().queryForObject("SELECT now()", OffsetDateTime.class);
        return new LiveStatsResponse(minutes, minutes > 0 ? now.minusMinutes(minutes) : null, now, totals, categories,
                chartMinutes, bucketMinutes, series);
    }

    static String label(String key) {
        return switch (key) {
            case DEFAULT_SMS -> "Default SMS";
            case WEBENGAGE -> "WebEngage";
            case SHORT_URL -> "Short URL";
            default -> key;
        };
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
