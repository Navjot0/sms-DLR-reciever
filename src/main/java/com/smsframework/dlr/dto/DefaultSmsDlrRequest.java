package com.smsframework.dlr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

/**
 * Default SMS DLR:
 * <pre>
 * { "payload": { "message_id": "...", "mobile": "...", "status": "DELIVRD", ... } }
 * </pre>
 * Required: payload, payload.message_id, payload.mobile, payload.status.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DefaultSmsDlrRequest {

    @NotNull(message = "payload is missing")
    @Valid
    @JsonProperty("payload")
    private Payload payload;

    public Payload getPayload() { return payload; }
    public void setPayload(Payload payload) { this.payload = payload; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Payload {
        @NotBlank(message = "message_id is missing")
        @Size(max = 255, message = "message_id exceeds 255 characters")
        @JsonProperty("message_id")
        private String messageId;

        @Size(max = 50, message = "service exceeds 50 characters")
        @JsonProperty("service")
        private String service;

        @Size(max = 100, message = "sender exceeds 100 characters")
        @JsonProperty("sender")
        private String sender;

        @NotBlank(message = "mobile is missing")
        @Size(max = 30, message = "mobile exceeds 30 characters")
        @JsonProperty("mobile")
        private String mobile;

        @NotBlank(message = "status is missing")
        @Size(max = 100, message = "status exceeds 100 characters")
        @JsonProperty("status")
        private String status;

        @Size(max = 100, message = "code exceeds 100 characters")
        @JsonProperty("code")
        private String code;

        @Size(max = 64, message = "submit_at exceeds 64 characters")
        @JsonProperty("submit_at")
        private String submitAt;

        @Size(max = 64, message = "dlr_received_at exceeds 64 characters")
        @JsonProperty("dlr_received_at")
        private String dlrReceivedAt;

        @Size(max = 255, message = "entity_id exceeds 255 characters")
        @JsonProperty("entity_id")
        private String entityId;

        @Size(max = 255, message = "template_id exceeds 255 characters")
        @JsonProperty("template_id")
        private String templateId;

        @Size(max = 20, message = "units exceeds 20 characters")
        @JsonProperty("units")
        private String units;

        @Size(max = 500, message = "correlation_id exceeds 500 characters")
        @JsonProperty("correlation_id")
        private String correlationId;

        // Optional correlation / diagnostics fields some gateways add.
        @Size(max = 255, message = "external_message_id exceeds 255 characters")
        @JsonProperty("external_message_id")
        private String externalMessageId;

        @Size(max = 255, message = "campaign_id exceeds 255 characters")
        @JsonProperty("campaign_id")
        private String campaignId;

        @Size(max = 255, message = "request_id exceeds 255 characters")
        @JsonProperty("request_id")
        private String requestId;

        @Size(max = 255, message = "event_id exceeds 255 characters")
        @JsonProperty("event_id")
        private String eventId;

        @Size(max = 100, message = "error_code exceeds 100 characters")
        @JsonProperty("error_code")
        private String errorCode;

        @JsonProperty("error_reason")
        private String errorReason;

        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        public String getService() { return service; }
        public void setService(String service) { this.service = service; }
        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }
        public String getMobile() { return mobile; }
        public void setMobile(String mobile) { this.mobile = mobile; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getSubmitAt() { return submitAt; }
        public void setSubmitAt(String submitAt) { this.submitAt = submitAt; }
        public String getDlrReceivedAt() { return dlrReceivedAt; }
        public void setDlrReceivedAt(String dlrReceivedAt) { this.dlrReceivedAt = dlrReceivedAt; }
        public String getEntityId() { return entityId; }
        public void setEntityId(String entityId) { this.entityId = entityId; }
        public String getTemplateId() { return templateId; }
        public void setTemplateId(String templateId) { this.templateId = templateId; }
        public String getUnits() { return units; }
        public void setUnits(String units) { this.units = units; }
        public String getCorrelationId() { return correlationId; }
        public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
        public String getExternalMessageId() { return externalMessageId; }
        public void setExternalMessageId(String externalMessageId) { this.externalMessageId = externalMessageId; }
        public String getCampaignId() { return campaignId; }
        public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public String getEventId() { return eventId; }
        public void setEventId(String eventId) { this.eventId = eventId; }
        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
        public String getErrorReason() { return errorReason; }
        public void setErrorReason(String errorReason) { this.errorReason = errorReason; }
    }
}
