package com.smsframework.dlr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All receiver behaviour is driven from here (prefix {@code dlr}). Defaults below
 * make the service usable with zero configuration for local automation.
 */
@ConfigurationProperties(prefix = "dlr")
public class DlrProperties {

    /** Identifies this receiver instance in dlr_events.receiver_instance (useful behind a load balancer). */
    private String instanceId = "";

    /** Zone used to convert zoned/epoch provider timestamps. Unzoned provider timestamps are stored as sent. */
    private String timezone = "Asia/Kolkata";

    /**
     * Status and billing DLRs of a multipart SMS carry "&lt;message_id&gt;:&lt;part&gt;" (e.g. "c9b2e601-...:1").
     * The text before the LAST separator is the message_id used for correlation/lookup; the numeric suffix is
     * stored as part_number and the id as received as provider_message_id. Empty = no splitting.
     */
    private String messageIdPartSeparator = ":";

    private final SourceResolution sourceResolution = new SourceResolution();

    /**
     * Global status mapping: NORMALIZED_STATUS -> list of provider statuses (case-insensitive).
     * Anything not listed maps to UNKNOWN.
     */
    private Map<String, List<String>> statusMapping = defaultStatusMapping();

    /** Optional per-source overrides, e.g. providerStatusMapping.WEBENGAGE.DELIVERED=[sms_delivered]. Checked before the global mapping. */
    private Map<String, Map<String, List<String>>> providerStatusMapping = new LinkedHashMap<>();

    private final StateMachine stateMachine = new StateMachine();

    private final Idempotency idempotency = new Idempotency();

    private final Api api = new Api();

    private final Security security = new Security();

    private final Billing billing = new Billing();

    private final Clicks clicks = new Clicks();

    // ------------------------------------------------------------------

    public static Map<String, List<String>> defaultStatusMapping() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("DELIVERED", new ArrayList<>(List.of("DELIVRD", "DELIVERED", "SMS_DELIVERED")));
        m.put("FAILED", new ArrayList<>(List.of("UNDELIV", "FAILED", "SMS_FAILED")));
        m.put("EXPIRED", new ArrayList<>(List.of("EXPIRED", "SMS_EXPIRED")));
        m.put("REJECTED", new ArrayList<>(List.of("REJECTD", "REJECTED")));
        m.put("SENT", new ArrayList<>(List.of("SMS_SENT", "SUBMITTED")));
        return m;
    }

    public static Map<String, List<String>> defaultTransitions() {
        Map<String, List<String>> t = new LinkedHashMap<>();
        // UNKNOWN is a weak state: anything known may replace it.
        t.put("UNKNOWN", new ArrayList<>(List.of("SENT", "DELIVERED", "FAILED", "EXPIRED", "REJECTED")));
        t.put("SENT", new ArrayList<>(List.of("DELIVERED", "FAILED", "EXPIRED", "REJECTED")));
        // Final states: no transitions by default (no downgrade, no flip-flop).
        t.put("DELIVERED", new ArrayList<>());
        t.put("FAILED", new ArrayList<>());
        t.put("EXPIRED", new ArrayList<>());
        t.put("REJECTED", new ArrayList<>());
        return t;
    }

    // ------------------------------------------------------------------

    public static class SourceResolution {
        /** Headers checked (in order) for an explicit source. */
        private List<String> headers = new ArrayList<>(List.of("X-DLR-Source", "X-DLR-Provider", "source", "provider"));
        /** Query parameters checked (in order) after headers. */
        private List<String> queryParams = new ArrayList<>(List.of("source", "provider"));
        /** If no explicit source is supplied, ask each adapter whether it recognises the payload structure. */
        private boolean detectFromPayload = true;
        /** Alias -> canonical source, e.g. WE -> WEBENGAGE. Keys are case-insensitive. */
        private Map<String, String> aliases = new LinkedHashMap<>(Map.of(
                "DEFAULT", "DEFAULT_SMS",
                "SMS", "DEFAULT_SMS",
                "WE", "WEBENGAGE",
                "WEB_ENGAGE", "WEBENGAGE"));

        public List<String> getHeaders() { return headers; }
        public void setHeaders(List<String> headers) { this.headers = headers; }
        public List<String> getQueryParams() { return queryParams; }
        public void setQueryParams(List<String> queryParams) { this.queryParams = queryParams; }
        public boolean isDetectFromPayload() { return detectFromPayload; }
        public void setDetectFromPayload(boolean detectFromPayload) { this.detectFromPayload = detectFromPayload; }
        public Map<String, String> getAliases() { return aliases; }
        public void setAliases(Map<String, String> aliases) { this.aliases = aliases; }
    }

    public static class StateMachine {
        /** CURRENT -> allowed NEXT statuses. A status missing as a key allows nothing. */
        private Map<String, List<String>> transitions = defaultTransitions();

        public Map<String, List<String>> getTransitions() { return transitions; }
        public void setTransitions(Map<String, List<String>> transitions) { this.transitions = transitions; }
    }

    public static class Idempotency {
        /**
         * Fields hashed into the dedup key. Allowed: source, message_id, provider_status, normalized_status,
         * status_code, error_code, provider_event_id, dlr_received_at, submit_at, mobile.
         */
        private List<String> keyFields = new ArrayList<>(List.of(
                "source", "message_id", "provider_status", "status_code", "provider_event_id", "dlr_received_at"));
        /** Persist duplicate callbacks (processing_status=DUPLICATE) for audit. If false they are only counted/logged. */
        private boolean storeDuplicates = true;

        public List<String> getKeyFields() { return keyFields; }
        public void setKeyFields(List<String> keyFields) { this.keyFields = keyFields; }
        public boolean isStoreDuplicates() { return storeDuplicates; }
        public void setStoreDuplicates(boolean storeDuplicates) { this.storeDuplicates = storeDuplicates; }
    }

    public static class Api {
        /** Max message_ids accepted by POST /api/v1/dlr/verify. */
        private int maxVerifyIds = 10_000;
        /** Max callback body size in bytes. */
        private int maxPayloadBytes = 64 * 1024;
        /** HTTP status returned for a REJECTED (but persisted) callback. 400 tells the provider not to retry a bad payload. */
        private int rejectedHttpStatus = 400;
        /** Max events returned by event listing endpoints. */
        private int maxEventsPageSize = 500;

        public int getMaxVerifyIds() { return maxVerifyIds; }
        public void setMaxVerifyIds(int maxVerifyIds) { this.maxVerifyIds = maxVerifyIds; }
        public int getMaxPayloadBytes() { return maxPayloadBytes; }
        public void setMaxPayloadBytes(int maxPayloadBytes) { this.maxPayloadBytes = maxPayloadBytes; }
        public int getRejectedHttpStatus() { return rejectedHttpStatus; }
        public void setRejectedHttpStatus(int rejectedHttpStatus) { this.rejectedHttpStatus = rejectedHttpStatus; }
        public int getMaxEventsPageSize() { return maxEventsPageSize; }
        public void setMaxEventsPageSize(int maxEventsPageSize) { this.maxEventsPageSize = maxEventsPageSize; }
    }

    public static class Clicks {
        /** Accept short-link click events ({"event":"short_link","data":{...}}) on /api/v1/dlr/receive. */
        private boolean enabled = true;
        /** Values of "event" that mark a click callback (case-insensitive). */
        private List<String> eventTypes = new ArrayList<>(List.of("short_link"));
        /** Source recorded when the callback carries no X-DLR-Source. */
        private String defaultSource = "DEFAULT_SMS";
        /** Sources allowed to send click events. */
        private List<String> sources = new ArrayList<>(List.of("DEFAULT_SMS"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getEventTypes() { return eventTypes; }
        public void setEventTypes(List<String> eventTypes) { this.eventTypes = eventTypes; }
        public String getDefaultSource() { return defaultSource; }
        public void setDefaultSource(String defaultSource) { this.defaultSource = defaultSource; }
        public List<String> getSources() { return sources; }
        public void setSources(List<String> sources) { this.sources = sources; }
    }

    public static class Billing {
        /** Accept billing DLRs ({"event_type":"billing","events":[...]}) on /api/v1/dlr/receive. */
        private boolean enabled = true;
        /** Value of event_type that marks a billing callback (case-insensitive). */
        private String eventType = "billing";
        /** Source recorded when the caller does not send one (billing callbacks come from the SMS gateway). */
        private String defaultSource = "DEFAULT_SMS";
        /** Sources allowed to send billing DLRs. */
        private List<String> sources = new ArrayList<>(List.of("DEFAULT_SMS"));
        /** Transaction types that add to the billed amount. */
        private List<String> debitTypes = new ArrayList<>(List.of("debit"));
        /** Transaction types that subtract from the billed amount. */
        private List<String> creditTypes = new ArrayList<>(List.of("credit", "refund", "reversal"));
        /** Max events accepted in one billing callback. */
        private int maxEventsPerCallback = 1000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        public String getDefaultSource() { return defaultSource; }
        public void setDefaultSource(String defaultSource) { this.defaultSource = defaultSource; }
        public List<String> getSources() { return sources; }
        public void setSources(List<String> sources) { this.sources = sources; }
        public List<String> getDebitTypes() { return debitTypes; }
        public void setDebitTypes(List<String> debitTypes) { this.debitTypes = debitTypes; }
        public List<String> getCreditTypes() { return creditTypes; }
        public void setCreditTypes(List<String> creditTypes) { this.creditTypes = creditTypes; }
        public int getMaxEventsPerCallback() { return maxEventsPerCallback; }
        public void setMaxEventsPerCallback(int maxEventsPerCallback) { this.maxEventsPerCallback = maxEventsPerCallback; }
    }

    public static class Security {
        /** Master switch for callback authentication. Must be true in profiles listed in enforceInProfiles. */
        private boolean enabled = false;
        /** Spring profiles in which startup fails if security is disabled. */
        private List<String> enforceInProfiles = new ArrayList<>(List.of("prod", "production"));
        /** Path(s) protected by callback authentication. */
        private List<String> callbackPaths = new ArrayList<>(List.of("/api/v1/dlr/receive"));
        /** Also require the API key on query/verify endpoints (/api/v1/dlr/**). */
        private boolean protectQueryApi = false;

        private final ApiKey apiKey = new ApiKey();
        private final Bearer bearer = new Bearer();
        private final IpAllowlist ipAllowlist = new IpAllowlist();
        private final Hmac hmac = new Hmac();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getEnforceInProfiles() { return enforceInProfiles; }
        public void setEnforceInProfiles(List<String> enforceInProfiles) { this.enforceInProfiles = enforceInProfiles; }
        public List<String> getCallbackPaths() { return callbackPaths; }
        public void setCallbackPaths(List<String> callbackPaths) { this.callbackPaths = callbackPaths; }
        public boolean isProtectQueryApi() { return protectQueryApi; }
        public void setProtectQueryApi(boolean protectQueryApi) { this.protectQueryApi = protectQueryApi; }
        public ApiKey getApiKey() { return apiKey; }
        public Bearer getBearer() { return bearer; }
        public IpAllowlist getIpAllowlist() { return ipAllowlist; }
        public Hmac getHmac() { return hmac; }

        public boolean anyMechanismEnabled() {
            return apiKey.enabled || bearer.enabled || ipAllowlist.enabled || hmac.enabled;
        }
    }

    public static class ApiKey {
        private boolean enabled = false;
        private String headerName = "X-API-Key";
        /** Accepted keys (supply via env, e.g. DLR_API_KEYS=key1,key2). */
        private List<String> keys = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHeaderName() { return headerName; }
        public void setHeaderName(String headerName) { this.headerName = headerName; }
        public List<String> getKeys() { return keys; }
        public void setKeys(List<String> keys) { this.keys = keys; }
    }

    public static class Bearer {
        private boolean enabled = false;
        /** Accepted bearer tokens (supply via env, e.g. DLR_BEARER_TOKENS). */
        private List<String> tokens = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getTokens() { return tokens; }
        public void setTokens(List<String> tokens) { this.tokens = tokens; }
    }

    public static class IpAllowlist {
        private boolean enabled = false;
        /** IPv4/IPv6 addresses or CIDR ranges. */
        private List<String> cidrs = new ArrayList<>();
        /** Use the left-most X-Forwarded-For address. Only enable behind a trusted load balancer. */
        private boolean trustForwardedFor = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getCidrs() { return cidrs; }
        public void setCidrs(List<String> cidrs) { this.cidrs = cidrs; }
        public boolean isTrustForwardedFor() { return trustForwardedFor; }
        public void setTrustForwardedFor(boolean trustForwardedFor) { this.trustForwardedFor = trustForwardedFor; }
    }

    public static class Hmac {
        private boolean enabled = false;
        private String headerName = "X-DLR-Signature";
        /** Shared secret (supply via env DLR_HMAC_SECRET). */
        private String secret = "";
        private String algorithm = "HmacSHA256";
        /**
         * Optional timestamp header. When present, the signature is computed over "timestamp.body"
         * and requests older than maxSkewSeconds are refused (replay protection).
         */
        private String timestampHeaderName = "X-DLR-Timestamp";
        private boolean requireTimestamp = false;
        private long maxSkewSeconds = 300;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHeaderName() { return headerName; }
        public void setHeaderName(String headerName) { this.headerName = headerName; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
        public String getTimestampHeaderName() { return timestampHeaderName; }
        public void setTimestampHeaderName(String timestampHeaderName) { this.timestampHeaderName = timestampHeaderName; }
        public boolean isRequireTimestamp() { return requireTimestamp; }
        public void setRequireTimestamp(boolean requireTimestamp) { this.requireTimestamp = requireTimestamp; }
        public long getMaxSkewSeconds() { return maxSkewSeconds; }
        public void setMaxSkewSeconds(long maxSkewSeconds) { this.maxSkewSeconds = maxSkewSeconds; }
    }

    // ------------------------------------------------------------------

    public String getMessageIdPartSeparator() { return messageIdPartSeparator; }
    public void setMessageIdPartSeparator(String messageIdPartSeparator) { this.messageIdPartSeparator = messageIdPartSeparator; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public SourceResolution getSourceResolution() { return sourceResolution; }
    public Map<String, List<String>> getStatusMapping() { return statusMapping; }
    public void setStatusMapping(Map<String, List<String>> statusMapping) { this.statusMapping = statusMapping; }
    public Map<String, Map<String, List<String>>> getProviderStatusMapping() { return providerStatusMapping; }
    public void setProviderStatusMapping(Map<String, Map<String, List<String>>> providerStatusMapping) { this.providerStatusMapping = providerStatusMapping; }
    public StateMachine getStateMachine() { return stateMachine; }
    public Idempotency getIdempotency() { return idempotency; }
    public Api getApi() { return api; }
    public Security getSecurity() { return security; }
    public Billing getBilling() { return billing; }
    public Clicks getClicks() { return clicks; }
}
