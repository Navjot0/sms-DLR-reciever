package com.smsframework.dlr.billing;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** dlr_billing_events persistence + per-message billing aggregation. */
@Repository
public class DlrBillingRepository {

    private static final String COLUMNS = """
            source, batch_id, batch_index, message_id, billing_message_id, part_number, transaction_type, product,
            units, sale_price, currency, surcharge, total_amount, raw_event, raw_payload, processing_status,
            processing_note, rejection_reason, dedup_key, duplicate_of, receiver_instance""";

    private static final String VALUES = """
            :source, :batchId, :batchIndex, :messageId, :billingMessageId, :partNumber, :transactionType, :product,
            :units, :salePrice, :currency, :surcharge, :totalAmount, CAST(:rawEvent AS jsonb), CAST(:rawPayload AS jsonb),
            :processingStatus, :processingNote, :rejectionReason, :dedupKey, :duplicateOf, :receiverInstance""";

    private final NamedParameterJdbcTemplate jdbc;

    public DlrBillingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return new id, or empty when an APPLIED event with the same dedup key already exists */
    public Optional<Long> insertIfAbsent(DlrBillingEvent e) {
        String sql = "INSERT INTO dlr_billing_events (" + COLUMNS + ") VALUES (" + VALUES + ") "
                + "ON CONFLICT (dedup_key) WHERE dedup_key IS NOT NULL AND processing_status <> 'DUPLICATE' "
                + "DO NOTHING RETURNING id";
        return Optional.ofNullable(jdbc.query(sql, params(e), rs -> rs.next() ? rs.getLong(1) : null));
    }

    public long insert(DlrBillingEvent e) {
        Long id = jdbc.queryForObject("INSERT INTO dlr_billing_events (" + COLUMNS + ") VALUES (" + VALUES + ") RETURNING id",
                params(e), Long.class);
        if (id == null) {
            throw new IllegalStateException("INSERT did not return an id");
        }
        return id;
    }

    public Optional<Long> findPrimaryIdByDedupKey(String dedupKey) {
        return jdbc.queryForList("SELECT id FROM dlr_billing_events WHERE dedup_key = :k AND processing_status <> 'DUPLICATE' LIMIT 1",
                new MapSqlParameterSource("k", dedupKey), Long.class).stream().findFirst();
    }

    public List<DlrBillingEvent> findByMessageId(String messageId, int limit) {
        return jdbc.query("SELECT * FROM dlr_billing_events WHERE message_id = :m ORDER BY id LIMIT :limit",
                new MapSqlParameterSource().addValue("m", messageId).addValue("limit", limit), MAPPER);
    }

    public List<DlrBillingEvent> findByProcessingStatus(String status, int limit) {
        return jdbc.query("SELECT * FROM dlr_billing_events WHERE processing_status = :s ORDER BY id DESC LIMIT :limit",
                new MapSqlParameterSource().addValue("s", status).addValue("limit", limit), MAPPER);
    }

    /** Billing summaries for the given message ids (APPLIED events only). Messages without billing are absent. */
    public Map<String, BillingSummary> summarize(List<String> messageIds, List<String> debitTypes, List<String> creditTypes) {
        Map<String, BillingSummary> out = new HashMap<>();
        if (messageIds.isEmpty()) {
            return out;
        }
        String sql = """
                SELECT message_id,
                       count(*)                                                      AS events,
                       count(DISTINCT part_number)                                   AS parts,
                       sum(CASE WHEN transaction_type = ANY (?) THEN coalesce(units, 0)
                                WHEN transaction_type = ANY (?) THEN -coalesce(units, 0) ELSE 0 END) AS net_units,
                       sum(CASE WHEN transaction_type = ANY (?) THEN coalesce(total_amount, 0) ELSE 0 END) AS debit_amount,
                       sum(CASE WHEN transaction_type = ANY (?) THEN coalesce(total_amount, 0) ELSE 0 END) AS credit_amount,
                       min(currency) AS min_currency, max(currency) AS max_currency,
                       max(created_at) AS last_billed_at
                FROM dlr_billing_events
                WHERE processing_status = 'APPLIED' AND message_id = ANY (?)
                GROUP BY message_id""";
        jdbc.getJdbcTemplate().query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            Array debit = con.createArrayOf("varchar", debitTypes.toArray());
            Array credit = con.createArrayOf("varchar", creditTypes.toArray());
            ps.setArray(1, debit);
            ps.setArray(2, credit);
            ps.setArray(3, debit);
            ps.setArray(4, credit);
            ps.setArray(5, con.createArrayOf("varchar", messageIds.toArray()));
            return ps;
        }, rs -> {
            java.math.BigDecimal debit = rs.getBigDecimal("debit_amount");
            java.math.BigDecimal credit = rs.getBigDecimal("credit_amount");
            String min = rs.getString("min_currency");
            String max = rs.getString("max_currency");
            String currency = min == null ? max : (min.equals(max) ? min : "MIXED");
            out.put(rs.getString("message_id"), new BillingSummary(true, rs.getInt("events"), rs.getInt("parts"),
                    rs.getInt("net_units"), strip(debit), strip(credit), strip(debit.subtract(credit)), currency,
                    rs.getObject("last_billed_at", OffsetDateTime.class)));
        });
        return out;
    }

    private static java.math.BigDecimal strip(java.math.BigDecimal d) {
        if (d == null) {
            return null;
        }
        java.math.BigDecimal s = d.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private MapSqlParameterSource params(DlrBillingEvent e) {
        return new MapSqlParameterSource()
                .addValue("source", e.getSource())
                .addValue("batchId", e.getBatchId())
                .addValue("batchIndex", e.getBatchIndex())
                .addValue("messageId", e.getMessageId())
                .addValue("billingMessageId", e.getBillingMessageId())
                .addValue("partNumber", e.getPartNumber())
                .addValue("transactionType", e.getTransactionType())
                .addValue("product", e.getProduct())
                .addValue("units", e.getUnits())
                .addValue("salePrice", e.getSalePrice())
                .addValue("currency", e.getCurrency())
                .addValue("surcharge", e.getSurcharge())
                .addValue("totalAmount", e.getTotalAmount())
                .addValue("rawEvent", e.getRawEvent())
                .addValue("rawPayload", e.getRawPayload())
                .addValue("processingStatus", e.getProcessingStatus())
                .addValue("processingNote", e.getProcessingNote())
                .addValue("rejectionReason", e.getRejectionReason())
                .addValue("dedupKey", e.getDedupKey())
                .addValue("duplicateOf", e.getDuplicateOf())
                .addValue("receiverInstance", e.getReceiverInstance());
    }

    static final RowMapper<DlrBillingEvent> MAPPER = (rs, n) -> {
        DlrBillingEvent e = new DlrBillingEvent();
        e.setId(rs.getLong("id"));
        e.setSource(rs.getString("source"));
        e.setBatchId(rs.getObject("batch_id", UUID.class));
        e.setBatchIndex(rs.getInt("batch_index"));
        e.setMessageId(rs.getString("message_id"));
        e.setBillingMessageId(rs.getString("billing_message_id"));
        int part = rs.getInt("part_number");
        e.setPartNumber(rs.wasNull() ? null : part);
        e.setTransactionType(rs.getString("transaction_type"));
        e.setProduct(rs.getString("product"));
        int units = rs.getInt("units");
        e.setUnits(rs.wasNull() ? null : units);
        e.setSalePrice(rs.getBigDecimal("sale_price"));
        e.setCurrency(rs.getString("currency"));
        e.setSurcharge(rs.getBigDecimal("surcharge"));
        e.setTotalAmount(rs.getBigDecimal("total_amount"));
        e.setRawEvent(rs.getString("raw_event"));
        e.setRawPayload(rs.getString("raw_payload"));
        e.setProcessingStatus(rs.getString("processing_status"));
        e.setProcessingNote(rs.getString("processing_note"));
        e.setRejectionReason(rs.getString("rejection_reason"));
        e.setDedupKey(rs.getString("dedup_key"));
        long dup = rs.getLong("duplicate_of");
        e.setDuplicateOf(rs.wasNull() ? null : dup);
        e.setReceiverInstance(rs.getString("receiver_instance"));
        e.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        return e;
    };
}
