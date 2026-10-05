package com.smsframework.dlr.rcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsframework.dlr.adapter.DlrProviderAdapter;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.domain.NormalizedStatus;
import com.smsframework.dlr.dto.NormalizedDlr;
import com.smsframework.dlr.exception.DlrValidationException;
import com.smsframework.dlr.service.StatusNormalizer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static com.smsframework.dlr.util.DlrValues.blankToNull;
import static com.smsframework.dlr.util.DlrValues.parseTimestamp;

/**
 * RCS delivery reports in the four operator wire formats (the same ones the RCS Simulator emits).
 * The operator is detected from the payload and stored in {@code service} (JIO / DOTGO / VI / AIRTEL).
 *
 * <pre>
 * Jio     {"entityType":"STATUS_EVENT","entity":{"eventType":"MESSAGE_SENT|MESSAGE_DELIVERED|MESSAGE_READ|MESSAGE_FAILED",
 *           "messageId":"..","sendTime":"..","error":{"code":..,"message":..}},"botId":"..","userPhoneNumber":".."}
 * Dotgo   {"message":{"data":"&lt;base64 {messageId,senderPhoneNumber,eventType,sendTime,reason?,code?}&gt;",
 *           "attributes":{"event_type":"SENT|DELIVERED|READ|FAILED","business_id":".."}}}
 * Vi      {"event":"message_status","RCSMessage":{"msgId":"..","status":"sent|delivered|read|failed","timestamp":".."},
 *           "messageContact":{"userContact":".."}}
 * Airtel  {"messageId":"..","eventType":"DELIVERED|READ|FAILED","sendTime":"..","agentId":"..",
 *           "error":{"message":..,"code":..}}
 * </pre>
 *
 * Mapping: message id -&gt; message_id, event type / status -&gt; provider_status (normalized to
 * SENT / DELIVERED / READ / FAILED), phone -&gt; mobile (leading "+" removed), time -&gt; dlr_received_at,
 * bot / agent id -&gt; sender, error code -&gt; status_code and error_code, error text -&gt; error_reason.
 */
@Component
@Order(250)
public class RcsDlrAdapter implements DlrProviderAdapter {

    public static final String SOURCE = "RCS";

    /** Operator wire format of a payload. */
    public enum Operator { JIO, DOTGO, VI, AIRTEL }

    /** The fields every operator format boils down to. */
    private record Fields(Operator operator, String messageId, String status, String mobile, String time,
                          String sender, String errorCode, String errorReason) {
    }

    private final StatusNormalizer statusNormalizer;
    private final ObjectMapper mapper;
    private final ZoneId zone;

    public RcsDlrAdapter(StatusNormalizer statusNormalizer, ObjectMapper mapper, DlrProperties properties) {
        this.statusNormalizer = statusNormalizer;
        this.mapper = mapper;
        this.zone = ZoneId.of(properties.getTimezone());
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean supports(String source, JsonNode payload) {
        if (source != null) {
            return SOURCE.equalsIgnoreCase(source);
        }
        return detect(payload) != null;
    }

    /** Which operator sent this payload, or null when it is not an RCS DLR. */
    public Operator detect(JsonNode p) {
        if (p == null || !p.isObject()) {
            return null;
        }
        if (p.path("entity").isObject() && (p.has("entityType") || p.path("entity").has("eventType"))) {
            return Operator.JIO;
        }
        if (p.path("message").path("data").isTextual() && !p.has("messageId")) {
            return Operator.DOTGO;
        }
        if (p.path("RCSMessage").isObject()) {
            return Operator.VI;
        }
        // Airtel is flat; WebEngage is also flat with "messageId" but always carries toNumber / status
        if (p.has("messageId") && p.has("eventType") && !p.has("toNumber") && !p.has("status")) {
            return Operator.AIRTEL;
        }
        return null;
    }

    @Override
    public String peekMessageId(JsonNode payload) {
        try {
            Operator op = detect(payload);
            return op == null ? null : extract(op, payload).messageId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public NormalizedDlr normalize(JsonNode payload) {
        Operator op = detect(payload);
        if (op == null) {
            throw new DlrValidationException(SOURCE,
                    "payload is not a Jio, Dotgo, Vi or Airtel RCS delivery report");
        }
        Fields f = extract(op, payload);
        List<String> missing = new ArrayList<>();
        if (f.messageId() == null) {
            missing.add("message id is missing");
        }
        if (f.status() == null) {
            missing.add("status / event type is missing");
        }
        if (!missing.isEmpty()) {
            throw new DlrValidationException(SOURCE, op.name().toLowerCase() + ": " + String.join("; ", missing));
        }
        NormalizedStatus normalized = statusNormalizer.normalize(SOURCE, f.status());
        return NormalizedDlr.of(SOURCE)
                .messageId(f.messageId())
                .mobile(f.mobile())
                .providerStatus(f.status())
                .normalizedStatus(normalized)
                .statusCode(f.errorCode())
                .errorCode(f.errorCode())
                .errorReason(f.errorReason())
                .dlrReceivedAt(parseTimestamp(f.time(), zone).orElse(null))
                .sender(f.sender())
                .service(op.name());
    }

    private Fields extract(Operator op, JsonNode p) {
        switch (op) {
            case JIO: {
                JsonNode e = p.path("entity");
                // an entityType other than STATUS_EVENT (e.g. a user reply) has no eventType -> rejected as "status missing"
                return new Fields(op, text(e, "messageId"), text(e, "eventType"), phone(text(p, "userPhoneNumber")),
                        text(e, "sendTime"), text(p, "botId"), text(e.path("error"), "code"),
                        text(e.path("error"), "message"));
            }
            case DOTGO: {
                JsonNode d = decode(p.path("message").path("data").asText());
                JsonNode attrs = p.path("message").path("attributes");
                String status = text(d, "eventType") != null ? text(d, "eventType") : text(attrs, "event_type");
                return new Fields(op, text(d, "messageId"), status, phone(text(d, "senderPhoneNumber")),
                        text(d, "sendTime"), text(attrs, "business_id"), text(d, "code"), text(d, "reason"));
            }
            case VI: {
                JsonNode m = p.path("RCSMessage");
                return new Fields(op, text(m, "msgId"), text(m, "status"),
                        phone(text(p.path("messageContact"), "userContact")), text(m, "timestamp"), null,
                        text(m.path("error"), "code"), text(m.path("error"), "message"));
            }
            default: {
                return new Fields(op, text(p, "messageId"), text(p, "eventType"), phone(text(p, "userPhoneNumber")),
                        text(p, "sendTime"), text(p, "agentId"), text(p.path("error"), "code"),
                        text(p.path("error"), "message"));
            }
        }
    }

    /** Dotgo wraps the event as base64(JSON) in a Pub/Sub push envelope. */
    private JsonNode decode(String base64) {
        try {
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(base64.trim());
            } catch (IllegalArgumentException e) {
                raw = Base64.getUrlDecoder().decode(base64.trim());
            }
            JsonNode n = mapper.readTree(new String(raw, StandardCharsets.UTF_8));
            if (n == null || !n.isObject()) {
                throw new IllegalArgumentException("not an object");
            }
            return n;
        } catch (Exception e) {
            throw new DlrValidationException(SOURCE, "dotgo: message.data is not base64-encoded JSON");
        }
    }

    private static String phone(String v) {
        return v != null && v.startsWith("+") ? blankToNull(v.substring(1)) : v;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || v.isContainerNode() ? null : blankToNull(v.asText());
    }
}
