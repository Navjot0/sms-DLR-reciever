package com.smsframework.dlr.click;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** dlr_click_events persistence + per-message click aggregation. */
@Repository
public class DlrClickRepository {

    private static final String COLUMNS = """
            source, event_type, message_id, provider_message_id, part_number, correlation_id, contact, url_key,
            url_type, short_url, destination_url, channel, visited_count, ip_address, operating_system,
            operating_system_version, browser, browser_version, device_type, clicked_at, provider_received_at,
            raw_payload, raw_body, processing_status, processing_note, dedup_key, duplicate_of, receiver_instance""";

    private static final String VALUES = """
            :source, :eventType, :messageId, :providerMessageId, :partNumber, :correlationId, :contact, :urlKey,
            :urlType, :shortUrl, :destinationUrl, :channel, :visitedCount, :ipAddress, :operatingSystem,
            :operatingSystemVersion, :browser, :browserVersion, :deviceType, :clickedAt, :providerReceivedAt,
            CAST(:rawPayload AS jsonb), :rawBody, :processingStatus, :processingNote, :dedupKey, :duplicateOf, :receiverInstance""";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DlrClickRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<Long> insertIfAbsent(NormalizedClickEvent e, String source, String raw, String dedupKey, String instance) {
        String sql = "INSERT INTO dlr_click_events (" + COLUMNS + ") VALUES (" + VALUES + ") "
                + "ON CONFLICT (dedup_key) WHERE dedup_key IS NOT NULL AND processing_status <> 'DUPLICATE' "
                + "DO NOTHING RETURNING id";
        return Optional.ofNullable(jdbc.query(sql, params(e, source, raw, dedupKey, instance, "APPLIED", null, null),
                rs -> rs.next() ? rs.getLong(1) : null));
    }

    public long insertDuplicate(NormalizedClickEvent e, String source, String raw, String dedupKey, String instance,
                                Long duplicateOf) {
        Long id = jdbc.queryForObject("INSERT INTO dlr_click_events (" + COLUMNS + ") VALUES (" + VALUES + ") RETURNING id",
                params(e, source, raw, dedupKey, instance, "DUPLICATE", "duplicate of click event " + duplicateOf,
                        duplicateOf), Long.class);
        return id == null ? -1 : id;
    }

    public Optional<Long> findPrimaryIdByDedupKey(String dedupKey) {
        return jdbc.queryForList("SELECT id FROM dlr_click_events WHERE dedup_key = :k AND processing_status <> 'DUPLICATE' LIMIT 1",
                new MapSqlParameterSource("k", dedupKey), Long.class).stream().findFirst();
    }

    public List<ClickEventView> findByMessageId(String messageId, int limit) {
        return jdbc.query("SELECT * FROM dlr_click_events WHERE message_id = :m ORDER BY id LIMIT :limit",
                new MapSqlParameterSource().addValue("m", messageId).addValue("limit", limit), (rs, n) -> view(rs));
    }

    /**
     * Click callbacks for a message exactly as received (raw_body; the stored JSON for rows from before V13),
     * oldest first, duplicates left out. part != null: only that recipient's ("&lt;id&gt;:&lt;part&gt;") clicks.
     */
    public List<String> rawTexts(String messageId, Integer part, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("m", messageId).addValue("limit", limit);
        String partFilter = "";
        if (part != null) {
            partFilter = " AND part_number = :part";
            p.addValue("part", part);
        }
        return jdbc.queryForList("SELECT coalesce(raw_body, raw_payload::text) FROM dlr_click_events WHERE message_id = :m"
                + " AND processing_status = 'APPLIED'" + partFilter + " ORDER BY id LIMIT :limit", p, String.class);
    }

    /** Click summaries for the given message ids (APPLIED events only); messages without clicks are absent. */
    public Map<String, ClickSummary> summarize(List<String> messageIds) {
        Map<String, ClickSummary> out = new HashMap<>();
        if (messageIds.isEmpty()) {
            return out;
        }
        String sql = """
                SELECT message_id,
                       count(*)                                   AS clicks,
                       max(visited_count)                         AS visited_count,
                       count(DISTINCT ip_address)                 AS unique_ips,
                       string_agg(DISTINCT url_key, ',')          AS url_keys,
                       min(coalesce(clicked_at, created_at::timestamp)) AS first_clicked_at,
                       max(coalesce(clicked_at, created_at::timestamp)) AS last_clicked_at,
                       (array_agg(destination_url ORDER BY id DESC))[1] AS last_destination_url,
                       (array_agg(device_type ORDER BY id DESC))[1]     AS last_device_type
                FROM dlr_click_events
                WHERE processing_status = 'APPLIED' AND message_id = ANY (?)
                GROUP BY message_id""";
        jdbc.getJdbcTemplate().query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            Array ids = con.createArrayOf("varchar", messageIds.toArray());
            ps.setArray(1, ids);
            return ps;
        }, rs -> {
            String keys = rs.getString("url_keys");
            int visited = rs.getInt("visited_count");
            Integer visitedCount = rs.wasNull() ? null : visited;
            out.put(rs.getString("message_id"), new ClickSummary(true, rs.getInt("clicks"), visitedCount,
                    rs.getInt("unique_ips"), keys == null ? List.of() : Arrays.stream(keys.split(",")).sorted().toList(),
                    rs.getObject("first_clicked_at", LocalDateTime.class), rs.getObject("last_clicked_at", LocalDateTime.class),
                    rs.getString("last_destination_url"), rs.getString("last_device_type")));
        });
        return out;
    }

    private MapSqlParameterSource params(NormalizedClickEvent e, String source, String raw, String dedupKey,
                                         String instance, String status, String note, Long duplicateOf) {
        return new MapSqlParameterSource()
                .addValue("source", source)
                .addValue("eventType", e.eventType())
                .addValue("messageId", e.messageId())
                .addValue("providerMessageId", e.providerMessageId())
                .addValue("partNumber", e.partNumber())
                .addValue("correlationId", e.correlationId())
                .addValue("contact", e.contact())
                .addValue("urlKey", e.urlKey())
                .addValue("urlType", e.urlType())
                .addValue("shortUrl", e.shortUrl())
                .addValue("destinationUrl", e.destinationUrl())
                .addValue("channel", e.channel())
                .addValue("visitedCount", e.visitedCount())
                .addValue("ipAddress", e.ipAddress())
                .addValue("operatingSystem", e.operatingSystem())
                .addValue("operatingSystemVersion", e.operatingSystemVersion())
                .addValue("browser", e.browser())
                .addValue("browserVersion", e.browserVersion())
                .addValue("deviceType", e.deviceType())
                .addValue("clickedAt", e.clickedAt())
                .addValue("providerReceivedAt", e.providerReceivedAt())
                .addValue("rawPayload", raw)
                .addValue("rawBody", raw)                      // exactly as received
                .addValue("processingStatus", status)
                .addValue("processingNote", note)
                .addValue("dedupKey", dedupKey)
                .addValue("duplicateOf", duplicateOf)
                .addValue("receiverInstance", instance);
    }

    private ClickEventView view(ResultSet rs) throws SQLException {
        int part = rs.getInt("part_number");
        Integer partNumber = rs.wasNull() ? null : part;
        int visited = rs.getInt("visited_count");
        Integer visitedCount = rs.wasNull() ? null : visited;
        long dup = rs.getLong("duplicate_of");
        Long duplicateOf = rs.wasNull() ? null : dup;
        com.fasterxml.jackson.databind.JsonNode raw;
        try {
            raw = objectMapper.readTree(rs.getString("raw_payload"));
        } catch (Exception ex) {
            raw = null;
        }
        return new ClickEventView(rs.getLong("id"), rs.getString("source"), rs.getString("message_id"),
                rs.getString("provider_message_id"), partNumber, rs.getString("contact"), rs.getString("url_key"),
                rs.getString("url_type"), rs.getString("short_url"), rs.getString("destination_url"),
                rs.getString("channel"), visitedCount, rs.getString("ip_address"), rs.getString("operating_system"),
                rs.getString("operating_system_version"), rs.getString("browser"), rs.getString("browser_version"),
                rs.getString("device_type"), rs.getObject("clicked_at", LocalDateTime.class),
                rs.getString("processing_status"), rs.getString("processing_note"), duplicateOf, raw,
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("raw_body"));
    }
}
