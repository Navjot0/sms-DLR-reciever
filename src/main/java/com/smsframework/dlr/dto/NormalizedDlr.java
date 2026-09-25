package com.smsframework.dlr.dto;

import com.smsframework.dlr.domain.NormalizedStatus;

import java.time.LocalDateTime;

/**
 * Provider-independent DLR produced by a {@link com.smsframework.dlr.adapter.DlrProviderAdapter}.
 * Not every provider fills every field; nulls are expected.
 */
public class NormalizedDlr {

    private String source;
    private String messageId;
    /** Id exactly as the provider sent it, when it differs from messageId (e.g. "c9b2...:1"). */
    private String providerMessageId;
    /** Part number of a multipart SMS, from the "&lt;id&gt;:&lt;part&gt;" suffix. */
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
    private NormalizedStatus normalizedStatus;
    private String statusCode;
    private String errorCode;
    private String errorReason;
    private LocalDateTime submitAt;
    private LocalDateTime dlrReceivedAt;
    private String entityId;
    private String templateId;
    private Integer units;

    public static NormalizedDlr of(String source) {
        NormalizedDlr d = new NormalizedDlr();
        d.source = source;
        return d;
    }

    // Fluent setters -----------------------------------------------------
    public NormalizedDlr messageId(String v) { this.messageId = v; return this; }
    public NormalizedDlr providerMessageId(String v) { this.providerMessageId = v; return this; }
    public NormalizedDlr partNumber(Integer v) { this.partNumber = v; return this; }
    public NormalizedDlr externalMessageId(String v) { this.externalMessageId = v; return this; }
    public NormalizedDlr correlationId(String v) { this.correlationId = v; return this; }
    public NormalizedDlr campaignId(String v) { this.campaignId = v; return this; }
    public NormalizedDlr requestId(String v) { this.requestId = v; return this; }
    public NormalizedDlr providerEventId(String v) { this.providerEventId = v; return this; }
    public NormalizedDlr mobile(String v) { this.mobile = v; return this; }
    public NormalizedDlr sender(String v) { this.sender = v; return this; }
    public NormalizedDlr service(String v) { this.service = v; return this; }
    public NormalizedDlr providerStatus(String v) { this.providerStatus = v; return this; }
    public NormalizedDlr normalizedStatus(NormalizedStatus v) { this.normalizedStatus = v; return this; }
    public NormalizedDlr statusCode(String v) { this.statusCode = v; return this; }
    public NormalizedDlr errorCode(String v) { this.errorCode = v; return this; }
    public NormalizedDlr errorReason(String v) { this.errorReason = v; return this; }
    public NormalizedDlr submitAt(LocalDateTime v) { this.submitAt = v; return this; }
    public NormalizedDlr dlrReceivedAt(LocalDateTime v) { this.dlrReceivedAt = v; return this; }
    public NormalizedDlr entityId(String v) { this.entityId = v; return this; }
    public NormalizedDlr templateId(String v) { this.templateId = v; return this; }
    public NormalizedDlr units(Integer v) { this.units = v; return this; }

    // Getters ------------------------------------------------------------
    public String getSource() { return source; }
    public String getMessageId() { return messageId; }
    public String getProviderMessageId() { return providerMessageId; }
    public Integer getPartNumber() { return partNumber; }
    public String getExternalMessageId() { return externalMessageId; }
    public String getCorrelationId() { return correlationId; }
    public String getCampaignId() { return campaignId; }
    public String getRequestId() { return requestId; }
    public String getProviderEventId() { return providerEventId; }
    public String getMobile() { return mobile; }
    public String getSender() { return sender; }
    public String getService() { return service; }
    public String getProviderStatus() { return providerStatus; }
    public NormalizedStatus getNormalizedStatus() { return normalizedStatus; }
    public String getStatusCode() { return statusCode; }
    public String getErrorCode() { return errorCode; }
    public String getErrorReason() { return errorReason; }
    public LocalDateTime getSubmitAt() { return submitAt; }
    public LocalDateTime getDlrReceivedAt() { return dlrReceivedAt; }
    public String getEntityId() { return entityId; }
    public String getTemplateId() { return templateId; }
    public Integer getUnits() { return units; }

    @Override
    public String toString() {
        return "NormalizedDlr{source=" + source + ", messageId=" + messageId + ", providerStatus=" + providerStatus
                + ", normalizedStatus=" + normalizedStatus + ", statusCode=" + statusCode + "}";
    }
}
