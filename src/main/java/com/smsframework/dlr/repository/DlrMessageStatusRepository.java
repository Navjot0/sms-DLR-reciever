package com.smsframework.dlr.repository;

import com.smsframework.dlr.dto.NormalizedDlr;
import com.smsframework.dlr.entity.DlrMessageStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * dlr_message_status persistence (current state per message_id).
 * All state changes happen under a row lock (SELECT ... FOR UPDATE) so concurrent DLRs for the
 * same message on different receiver instances are serialised by PostgreSQL.
 */
@Repository
public class DlrMessageStatusRepository {

    private static final String SELECT = "SELECT * FROM dlr_message_status ";

    private final NamedParameterJdbcTemplate jdbc;

    public DlrMessageStatusRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DlrMessageStatus> findForUpdate(String messageId) {
        return jdbc.query(SELECT + "WHERE message_id = :m FOR UPDATE", new MapSqlParameterSource("m", messageId), MAPPER)
                .stream().findFirst();
    }

    /** @return true if this call created the row, false if it already existed (e.g. created concurrently). */
    public boolean insertIfAbsent(NormalizedDlr d, long eventId) {
        String sql = """
                INSERT INTO dlr_message_status (message_id, source, external_message_id, correlation_id, campaign_id,
                    request_id, mobile, sender, service, provider_status, normalized_status, status_code, error_code,
                    error_reason, submit_at, dlr_received_at, entity_id, template_id, units, last_event_id,
                    event_count, duplicate_count)
                VALUES (:messageId, :source, :externalMessageId, :correlationId, :campaignId, :requestId, :mobile,
                    :sender, :service, :providerStatus, :normalizedStatus, :statusCode, :errorCode, :errorReason,
                    :submitAt, :dlrReceivedAt, :entityId, :templateId, :units, :eventId, 1, 0)
                ON CONFLICT (message_id) DO NOTHING""";
        return jdbc.update(sql, params(d, eventId)) == 1;
    }

    /** Incoming DLR becomes the current state. Correlation fields are only overwritten by non-null values. */
    public void applyTransition(NormalizedDlr d, long eventId) {
        String sql = """
                UPDATE dlr_message_status SET
                    source = :source,
                    external_message_id = COALESCE(:externalMessageId, external_message_id),
                    correlation_id = COALESCE(:correlationId, correlation_id),
                    campaign_id = COALESCE(:campaignId, campaign_id),
                    request_id = COALESCE(:requestId, request_id),
                    mobile = COALESCE(:mobile, mobile),
                    sender = COALESCE(:sender, sender),
                    service = COALESCE(:service, service),
                    provider_status = :providerStatus,
                    normalized_status = :normalizedStatus,
                    status_code = :statusCode,
                    error_code = :errorCode,
                    error_reason = :errorReason,
                    submit_at = COALESCE(:submitAt, submit_at),
                    dlr_received_at = COALESCE(:dlrReceivedAt, dlr_received_at),
                    entity_id = COALESCE(:entityId, entity_id),
                    template_id = COALESCE(:templateId, template_id),
                    units = COALESCE(:units, units),
                    last_event_id = :eventId,
                    event_count = event_count + 1,
                    status_updated_at = CURRENT_TIMESTAMP,
                    last_received_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE message_id = :messageId""";
        jdbc.update(sql, params(d, eventId));
    }

    /**
     * A valid event that did not change the state (same state / out-of-order). Only fills correlation
     * fields that are still empty, never touches status fields.
     */
    public void recordIgnored(NormalizedDlr d) {
        String sql = """
                UPDATE dlr_message_status SET
                    external_message_id = COALESCE(external_message_id, :externalMessageId),
                    correlation_id = COALESCE(correlation_id, :correlationId),
                    campaign_id = COALESCE(campaign_id, :campaignId),
                    request_id = COALESCE(request_id, :requestId),
                    submit_at = COALESCE(submit_at, :submitAt),
                    event_count = event_count + 1,
                    last_received_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE message_id = :messageId""";
        jdbc.update(sql, params(d, 0L));
    }

    public void incrementDuplicate(String messageId) {
        jdbc.update("UPDATE dlr_message_status SET duplicate_count = duplicate_count + 1, "
                + "last_received_at = CURRENT_TIMESTAMP WHERE message_id = :m", new MapSqlParameterSource("m", messageId));
    }

    public Optional<DlrMessageStatus> findByMessageId(String messageId) {
        return jdbc.query(SELECT + "WHERE message_id = :m", new MapSqlParameterSource("m", messageId), MAPPER)
                .stream().findFirst();
    }

    public List<DlrMessageStatus> findByMessageIds(List<String> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        JdbcTemplate plain = jdbc.getJdbcTemplate();
        return plain.query(con -> {
            PreparedStatement ps = con.prepareStatement(SELECT + "WHERE message_id = ANY (?)");
            Array array = con.createArrayOf("varchar", messageIds.toArray());
            ps.setArray(1, array);
            return ps;
        }, MAPPER);
    }

    public List<DlrMessageStatus> findByCorrelationId(String correlationId, int limit) {
        return jdbc.query(SELECT + "WHERE correlation_id = :c ORDER BY created_at LIMIT :limit",
                new MapSqlParameterSource().addValue("c", correlationId).addValue("limit", limit), MAPPER);
    }

    public List<DlrMessageStatus> findByExternalMessageId(String externalMessageId, int limit) {
        return jdbc.query(SELECT + "WHERE external_message_id = :e ORDER BY created_at LIMIT :limit",
                new MapSqlParameterSource().addValue("e", externalMessageId).addValue("limit", limit), MAPPER);
    }

    private MapSqlParameterSource params(NormalizedDlr d, long eventId) {
        return new MapSqlParameterSource()
                .addValue("messageId", d.getMessageId())
                .addValue("source", d.getSource())
                .addValue("externalMessageId", d.getExternalMessageId())
                .addValue("correlationId", d.getCorrelationId())
                .addValue("campaignId", d.getCampaignId())
                .addValue("requestId", d.getRequestId())
                .addValue("mobile", d.getMobile())
                .addValue("sender", d.getSender())
                .addValue("service", d.getService())
                .addValue("providerStatus", d.getProviderStatus())
                .addValue("normalizedStatus", d.getNormalizedStatus().name())
                .addValue("statusCode", d.getStatusCode())
                .addValue("errorCode", d.getErrorCode())
                .addValue("errorReason", d.getErrorReason())
                .addValue("submitAt", d.getSubmitAt())
                .addValue("dlrReceivedAt", d.getDlrReceivedAt())
                .addValue("entityId", d.getEntityId())
                .addValue("templateId", d.getTemplateId())
                .addValue("units", d.getUnits())
                .addValue("eventId", eventId);
    }

    static final RowMapper<DlrMessageStatus> MAPPER = (ResultSet rs, int n) -> {
        DlrMessageStatus s = new DlrMessageStatus();
        s.setMessageId(rs.getString("message_id"));
        s.setSource(rs.getString("source"));
        s.setExternalMessageId(rs.getString("external_message_id"));
        s.setCorrelationId(rs.getString("correlation_id"));
        s.setCampaignId(rs.getString("campaign_id"));
        s.setRequestId(rs.getString("request_id"));
        s.setMobile(rs.getString("mobile"));
        s.setSender(rs.getString("sender"));
        s.setService(rs.getString("service"));
        s.setProviderStatus(rs.getString("provider_status"));
        s.setNormalizedStatus(rs.getString("normalized_status"));
        s.setStatusCode(rs.getString("status_code"));
        s.setErrorCode(rs.getString("error_code"));
        s.setErrorReason(rs.getString("error_reason"));
        s.setSubmitAt(rs.getObject("submit_at", LocalDateTime.class));
        s.setDlrReceivedAt(rs.getObject("dlr_received_at", LocalDateTime.class));
        s.setEntityId(rs.getString("entity_id"));
        s.setTemplateId(rs.getString("template_id"));
        s.setUnits(DlrEventRepository.getInteger(rs, "units"));
        s.setLastEventId(rs.getLong("last_event_id"));
        s.setEventCount(rs.getInt("event_count"));
        s.setDuplicateCount(rs.getInt("duplicate_count"));
        s.setFirstReceivedAt(rs.getObject("first_received_at", OffsetDateTime.class));
        s.setStatusUpdatedAt(rs.getObject("status_updated_at", OffsetDateTime.class));
        s.setLastReceivedAt(rs.getObject("last_received_at", OffsetDateTime.class));
        s.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        s.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class));
        return s;
    };
}
