package com.smsframework.dlr.entity;

import com.smsframework.dlr.domain.ProcessingStatus;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * Row of dlr_events: one per callback received (append-only audit log).
 * Persisted via explicit SQL (see DlrEventRepository) so idempotency is enforced by
 * PostgreSQL (INSERT ... ON CONFLICT) rather than by application memory.
 */
public class DlrEvent {

    private Long id;
    private String source;
    private String messageId;
    private String providerMessageId;
    private Integer partNumber;
    private String externalMessageId;
    private String correlationId;
    private String campaignId;
    private String requestId;
    private String providerEventId;
    private String mobile;
    private String sender;
    private String service;
    private String providerStatus;
    private String normalizedStatus;
    private String statusCode;
    private String errorCode;
    private String errorReason;
    private LocalDateTime submitAt;
    private LocalDateTime dlrReceivedAt;
    private String entityId;
    private String templateId;
    private Integer units;
    /** Raw JSON text, stored as JSONB. Always present. */
    private String rawPayload;
    private ProcessingStatus processingStatus;
    private String processingNote;
    private String rejectionReason;
    private String dedupKey;
    private Long duplicateOf;
    private String receiverInstance;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }
    public Integer getPartNumber() { return partNumber; }
    public void setPartNumber(Integer partNumber) { this.partNumber = partNumber; }
    public String getExternalMessageId() { return externalMessageId; }
    public void setExternalMessageId(String externalMessageId) { this.externalMessageId = externalMessageId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getCampaignId() { return campaignId; }
    public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getProviderEventId() { return providerEventId; }
    public void setProviderEventId(String providerEventId) { this.providerEventId = providerEventId; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getProviderStatus() { return providerStatus; }
    public void setProviderStatus(String providerStatus) { this.providerStatus = providerStatus; }
    public String getNormalizedStatus() { return normalizedStatus; }
    public void setNormalizedStatus(String normalizedStatus) { this.normalizedStatus = normalizedStatus; }
    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }
    public LocalDateTime getSubmitAt() { return submitAt; }
    public void setSubmitAt(LocalDateTime submitAt) { this.submitAt = submitAt; }
    public LocalDateTime getDlrReceivedAt() { return dlrReceivedAt; }
    public void setDlrReceivedAt(LocalDateTime dlrReceivedAt) { this.dlrReceivedAt = dlrReceivedAt; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public Integer getUnits() { return units; }
    public void setUnits(Integer units) { this.units = units; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
    public ProcessingStatus getProcessingStatus() { return processingStatus; }
    public void setProcessingStatus(ProcessingStatus processingStatus) { this.processingStatus = processingStatus; }
    public String getProcessingNote() { return processingNote; }
    public void setProcessingNote(String processingNote) { this.processingNote = processingNote; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
    public Long getDuplicateOf() { return duplicateOf; }
    public void setDuplicateOf(Long duplicateOf) { this.duplicateOf = duplicateOf; }
    public String getReceiverInstance() { return receiverInstance; }
    public void setReceiverInstance(String receiverInstance) { this.receiverInstance = receiverInstance; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
