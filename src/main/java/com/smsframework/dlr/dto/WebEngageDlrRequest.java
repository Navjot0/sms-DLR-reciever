package com.smsframework.dlr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * WebEngage SMS DLR:
 * <pre>
 * { "version": "1.0", "messageId": "...", "toNumber": "...", "status": "sms_sent", "statusCode": "0", "smsCount": "3" }
 * </pre>
 * Required: messageId, toNumber, status. Optional: statusCode, smsCount, version.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebEngageDlrRequest {

    @Size(max = 20, message = "version exceeds 20 characters")
    @JsonProperty("version")
    private String version;

    @NotBlank(message = "messageId is missing")
    @Size(max = 255, message = "messageId exceeds 255 characters")
    @JsonProperty("messageId")
    private String messageId;

    @NotBlank(message = "toNumber is missing")
    @Size(max = 30, message = "toNumber exceeds 30 characters")
    @JsonProperty("toNumber")
    private String toNumber;

    @NotBlank(message = "status is missing")
    @Size(max = 100, message = "status exceeds 100 characters")
    @JsonProperty("status")
    private String status;

    @Size(max = 100, message = "statusCode exceeds 100 characters")
    @JsonProperty("statusCode")
    private String statusCode;

    @Size(max = 20, message = "smsCount exceeds 20 characters")
    @JsonProperty("smsCount")
    private String smsCount;

    /** Optional failure description, if WebEngage sends one. */
    @JsonProperty("message")
    private String message;

    /** Optional event timestamp (epoch millis or ISO), if present. */
    @Size(max = 64, message = "timestamp exceeds 64 characters")
    @JsonProperty("timestamp")
    private String timestamp;

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getToNumber() { return toNumber; }
    public void setToNumber(String toNumber) { this.toNumber = toNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
    public String getSmsCount() { return smsCount; }
    public void setSmsCount(String smsCount) { this.smsCount = smsCount; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
