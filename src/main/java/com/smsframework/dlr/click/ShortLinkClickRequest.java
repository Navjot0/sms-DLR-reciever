package com.smsframework.dlr.click;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Short-link click callback:
 * <pre>
 * {"event":"short_link","url_type":"dynamic","received_at":"2026-09-25 23:27:25",
 *  "data":{"visited_count":2,"url_type":"dynamic","contact":"919177873237","url_key":"ZIO7ER",
 *          "short_url":"stqa.gtls.in/DUMMY/bBz/ZIO7ER","destination_url":"https://...","channel":"sms",
 *          "ip_address":"152.58.121.146","operating_system":"Windows","operating_system_version":"10",
 *          "browser":"Chrome","browser_version":"153","device_type":"desktop",
 *          "clicked_at":"2026-09-25 23:27:24","message_id":"68c3d2ef-...:1","correlation_id":""}}
 * </pre>
 * Required: event, data, data.message_id, data.url_key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShortLinkClickRequest {

    @NotBlank(message = "event is missing")
    @Size(max = 50, message = "event exceeds 50 characters")
    @JsonProperty("event")
    private String event;

    @Size(max = 50, message = "url_type exceeds 50 characters")
    @JsonProperty("url_type")
    private String urlType;

    @Size(max = 64, message = "received_at exceeds 64 characters")
    @JsonProperty("received_at")
    private String receivedAt;

    @NotNull(message = "data is missing")
    @Valid
    @JsonProperty("data")
    private Data data;

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public String getUrlType() { return urlType; }
    public void setUrlType(String urlType) { this.urlType = urlType; }
    public String getReceivedAt() { return receivedAt; }
    public void setReceivedAt(String receivedAt) { this.receivedAt = receivedAt; }
    public Data getData() { return data; }
    public void setData(Data data) { this.data = data; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        @Size(max = 20, message = "data.visited_count exceeds 20 characters")
        @JsonProperty("visited_count") private String visitedCount;
        @Size(max = 50, message = "data.url_type exceeds 50 characters")
        @JsonProperty("url_type") private String urlType;
        @Size(max = 30, message = "data.contact exceeds 30 characters")
        @JsonProperty("contact") private String contact;
        @NotBlank(message = "data.url_key is missing")
        @Size(max = 100, message = "data.url_key exceeds 100 characters")
        @JsonProperty("url_key") private String urlKey;
        @Size(max = 2048, message = "data.short_url exceeds 2048 characters")
        @JsonProperty("short_url") private String shortUrl;
        @Size(max = 8192, message = "data.destination_url exceeds 8192 characters")
        @JsonProperty("destination_url") private String destinationUrl;
        @Size(max = 30, message = "data.channel exceeds 30 characters")
        @JsonProperty("channel") private String channel;
        @Size(max = 64, message = "data.ip_address exceeds 64 characters")
        @JsonProperty("ip_address") private String ipAddress;
        @Size(max = 100, message = "data.operating_system exceeds 100 characters")
        @JsonProperty("operating_system") private String operatingSystem;
        @Size(max = 50, message = "data.operating_system_version exceeds 50 characters")
        @JsonProperty("operating_system_version") private String operatingSystemVersion;
        @Size(max = 100, message = "data.browser exceeds 100 characters")
        @JsonProperty("browser") private String browser;
        @Size(max = 50, message = "data.browser_version exceeds 50 characters")
        @JsonProperty("browser_version") private String browserVersion;
        @Size(max = 50, message = "data.device_type exceeds 50 characters")
        @JsonProperty("device_type") private String deviceType;
        @Size(max = 64, message = "data.clicked_at exceeds 64 characters")
        @JsonProperty("clicked_at") private String clickedAt;
        @NotBlank(message = "data.message_id is missing")
        @Size(max = 255, message = "data.message_id exceeds 255 characters")
        @JsonProperty("message_id") private String messageId;
        @Size(max = 500, message = "data.correlation_id exceeds 500 characters")
        @JsonProperty("correlation_id") private String correlationId;

        public String getVisitedCount() { return visitedCount; }
        public void setVisitedCount(String v) { this.visitedCount = v; }
        public String getUrlType() { return urlType; }
        public void setUrlType(String v) { this.urlType = v; }
        public String getContact() { return contact; }
        public void setContact(String v) { this.contact = v; }
        public String getUrlKey() { return urlKey; }
        public void setUrlKey(String v) { this.urlKey = v; }
        public String getShortUrl() { return shortUrl; }
        public void setShortUrl(String v) { this.shortUrl = v; }
        public String getDestinationUrl() { return destinationUrl; }
        public void setDestinationUrl(String v) { this.destinationUrl = v; }
        public String getChannel() { return channel; }
        public void setChannel(String v) { this.channel = v; }
        public String getIpAddress() { return ipAddress; }
        public void setIpAddress(String v) { this.ipAddress = v; }
        public String getOperatingSystem() { return operatingSystem; }
        public void setOperatingSystem(String v) { this.operatingSystem = v; }
        public String getOperatingSystemVersion() { return operatingSystemVersion; }
        public void setOperatingSystemVersion(String v) { this.operatingSystemVersion = v; }
        public String getBrowser() { return browser; }
        public void setBrowser(String v) { this.browser = v; }
        public String getBrowserVersion() { return browserVersion; }
        public void setBrowserVersion(String v) { this.browserVersion = v; }
        public String getDeviceType() { return deviceType; }
        public void setDeviceType(String v) { this.deviceType = v; }
        public String getClickedAt() { return clickedAt; }
        public void setClickedAt(String v) { this.clickedAt = v; }
        public String getMessageId() { return messageId; }
        public void setMessageId(String v) { this.messageId = v; }
        public String getCorrelationId() { return correlationId; }
        public void setCorrelationId(String v) { this.correlationId = v; }
    }
}
