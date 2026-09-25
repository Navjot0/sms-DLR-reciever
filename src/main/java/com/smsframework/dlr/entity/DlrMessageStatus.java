package com.smsframework.dlr.entity;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * Row of dlr_message_status: the current, state-machine-resolved DLR state of one message_id.
 * This is the table the automation query/verify APIs read.
 */
public class DlrMessageStatus {

    private String messageId;
    private String source;
    private String externalMessageId;
    private String correlationId;
    private String campaignId;
    private String requestId;
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
    private Long lastEventId;
    private int eventCount;
    private int duplicateCount;
    private OffsetDateTime firstReceivedAt;
    private OffsetDateTime statusUpdatedAt;
    private OffsetDateTime lastReceivedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getExternalMessageId() { return externalMessageId; }
    public void setExternalMessageId(String externalMessageId) { this.externalMessageId = externalMessageId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getCampaignId() { return campaignId; }
    public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
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
    public Long getLastEventId() { return lastEventId; }
    public void setLastEventId(Long lastEventId) { this.lastEventId = lastEventId; }
    public int getEventCount() { return eventCount; }
    public void setEventCount(int eventCount) { this.eventCount = eventCount; }
    public int getDuplicateCount() { return duplicateCount; }
    public void setDuplicateCount(int duplicateCount) { this.duplicateCount = duplicateCount; }
    public OffsetDateTime getFirstReceivedAt() { return firstReceivedAt; }
    public void setFirstReceivedAt(OffsetDateTime firstReceivedAt) { this.firstReceivedAt = firstReceivedAt; }
    public OffsetDateTime getStatusUpdatedAt() { return statusUpdatedAt; }
    public void setStatusUpdatedAt(OffsetDateTime statusUpdatedAt) { this.statusUpdatedAt = statusUpdatedAt; }
    public OffsetDateTime getLastReceivedAt() { return lastReceivedAt; }
    public void setLastReceivedAt(OffsetDateTime lastReceivedAt) { this.lastReceivedAt = lastReceivedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
