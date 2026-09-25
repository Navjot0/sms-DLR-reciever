package com.smsframework.dlr.repository;

import com.smsframework.dlr.domain.ProcessingStatus;
import com.smsframework.dlr.entity.DlrEvent;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * dlr_events persistence. Idempotency is enforced by the partial unique index
 * ux_dlr_events_dedup_key, used through INSERT ... ON CONFLICT DO NOTHING.
 */
@Repository
public class DlrEventRepository {

    private static final String COLUMNS = """
            source, message_id, external_message_id, correlation_id, campaign_id, request_id, provider_event_id,
            mobile, sender, service, provider_status, normalized_status, status_code, error_code, error_reason,
            submit_at, dlr_received_at, entity_id, template_id, units, raw_payload, processing_status,
            processing_note, rejection_reason, dedup_key, duplicate_of, receiver_instance""";

    private static final String VALUES = """
            :source, :messageId, :externalMessageId, :correlationId, :campaignId, :requestId, :providerEventId,
            :mobile, :sender, :service, :providerStatus, :normalizedStatus, :statusCode, :errorCode, :errorReason,
            :submitAt, :dlrReceivedAt, :entityId, :templateId, :units, CAST(:rawPayload AS jsonb), :processingStatus,
            :processingNote, :rejectionReason, :dedupKey, :duplicateOf, :receiverInstance""";

    private static final String SELECT = "SELECT id, " + COLUMNS + ", created_at, updated_at FROM dlr_events ";

    private final NamedParameterJdbcTemplate jdbc;

    public DlrEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the event unless another non-duplicate event with the same dedup_key already exists
     * (possibly inserted concurrently by another receiver instance).
     *
     * @return the new id, or empty when the event is a duplicate
     */
    public Optional<Long> insertIfAbsent(DlrEvent e) {
        String sql = "INSERT INTO dlr_events (" + COLUMNS + ") VALUES (" + VALUES + ") "
                + "ON CONFLICT (dedup_key) WHERE dedup_key IS NOT NULL AND processing_status <> 'DUPLICATE' "
                + "DO NOTHING RETURNING id";
        Long id = jdbc.query(sql, params(e), rs -> rs.next() ? rs.getLong(1) : null);
        return Optional.ofNullable(id);
    }

    /** Unconditional insert (REJECTED and DUPLICATE events). */
    public long insert(DlrEvent e) {
        String sql = "INSERT INTO dlr_events (" + COLUMNS + ") VALUES (" + VALUES + ") RETURNING id";
        Long id = jdbc.queryForObject(sql, params(e), Long.class);
        if (id == null) {
            throw new IllegalStateException("INSERT did not return an id");
        }
        return id;
    }

    public Optional<Long> findPrimaryIdByDedupKey(String dedupKey) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM dlr_events WHERE dedup_key = :k AND processing_status <> 'DUPLICATE' LIMIT 1",
                new MapSqlParameterSource("k", dedupKey), Long.class);
        return ids.stream().findFirst();
    }

    public void updateProcessing(long id, ProcessingStatus status, String note) {
        jdbc.update("UPDATE dlr_events SET processing_status = :s, processing_note = :n, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = :id",
                new MapSqlParameterSource().addValue("s", status.name()).addValue("n", note).addValue("id", id));
    }

    public Optional<DlrEvent> findById(long id) {
        return jdbc.query(SELECT + "WHERE id = :id", new MapSqlParameterSource("id", id), MAPPER).stream().findFirst();
    }

    public List<DlrEvent> findByMessageId(String messageId, int limit) {
        return jdbc.query(SELECT + "WHERE message_id = :m ORDER BY id LIMIT :limit",
                new MapSqlParameterSource().addValue("m", messageId).addValue("limit", limit), MAPPER);
    }

    public List<DlrEvent> findByProcessingStatus(ProcessingStatus status, int limit) {
        return jdbc.query(SELECT + "WHERE processing_status = :s ORDER BY id DESC LIMIT :limit",
                new MapSqlParameterSource().addValue("s", status.name()).addValue("limit", limit), MAPPER);
    }

    private MapSqlParameterSource params(DlrEvent e) {
        return new MapSqlParameterSource()
                .addValue("source", e.getSource())
                .addValue("messageId", e.getMessageId())
                .addValue("externalMessageId", e.getExternalMessageId())
                .addValue("correlationId", e.getCorrelationId())
                .addValue("campaignId", e.getCampaignId())
                .addValue("requestId", e.getRequestId())
                .addValue("providerEventId", e.getProviderEventId())
                .addValue("mobile", e.getMobile())
                .addValue("sender", e.getSender())
                .addValue("service", e.getService())
                .addValue("providerStatus", e.getProviderStatus())
                .addValue("normalizedStatus", e.getNormalizedStatus())
                .addValue("statusCode", e.getStatusCode())
                .addValue("errorCode", e.getErrorCode())
                .addValue("errorReason", e.getErrorReason())
                .addValue("submitAt", e.getSubmitAt())
                .addValue("dlrReceivedAt", e.getDlrReceivedAt())
                .addValue("entityId", e.getEntityId())
                .addValue("templateId", e.getTemplateId())
                .addValue("units", e.getUnits())
                .addValue("rawPayload", e.getRawPayload())
                .addValue("processingStatus", e.getProcessingStatus().name())
                .addValue("processingNote", e.getProcessingNote())
                .addValue("rejectionReason", e.getRejectionReason())
                .addValue("dedupKey", e.getDedupKey())
                .addValue("duplicateOf", e.getDuplicateOf())
                .addValue("receiverInstance", e.getReceiverInstance());
    }

    static final RowMapper<DlrEvent> MAPPER = (ResultSet rs, int n) -> {
        DlrEvent e = new DlrEvent();
        e.setId(rs.getLong("id"));
        e.setSource(rs.getString("source"));
        e.setMessageId(rs.getString("message_id"));
        e.setExternalMessageId(rs.getString("external_message_id"));
        e.setCorrelationId(rs.getString("correlation_id"));
        e.setCampaignId(rs.getString("campaign_id"));
        e.setRequestId(rs.getString("request_id"));
        e.setProviderEventId(rs.getString("provider_event_id"));
        e.setMobile(rs.getString("mobile"));
        e.setSender(rs.getString("sender"));
        e.setService(rs.getString("service"));
        e.setProviderStatus(rs.getString("provider_status"));
        e.setNormalizedStatus(rs.getString("normalized_status"));
        e.setStatusCode(rs.getString("status_code"));
        e.setErrorCode(rs.getString("error_code"));
        e.setErrorReason(rs.getString("error_reason"));
        e.setSubmitAt(rs.getObject("submit_at", LocalDateTime.class));
        e.setDlrReceivedAt(rs.getObject("dlr_received_at", LocalDateTime.class));
        e.setEntityId(rs.getString("entity_id"));
        e.setTemplateId(rs.getString("template_id"));
        e.setUnits(getInteger(rs, "units"));
        e.setRawPayload(rs.getString("raw_payload"));
        e.setProcessingStatus(ProcessingStatus.valueOf(rs.getString("processing_status")));
        e.setProcessingNote(rs.getString("processing_note"));
        e.setRejectionReason(rs.getString("rejection_reason"));
        e.setDedupKey(rs.getString("dedup_key"));
        long dup = rs.getLong("duplicate_of");
        e.setDuplicateOf(rs.wasNull() ? null : dup);
        e.setReceiverInstance(rs.getString("receiver_instance"));
        e.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        e.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class));
        return e;
    };

    static Integer getInteger(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
