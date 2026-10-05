package com.smsframework.dlr.email;

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

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.smsframework.dlr.util.DlrValues.blankToNull;
import static com.smsframework.dlr.util.DlrValues.parseTimestamp;

/**
 * Email delivery reports in the two provider formats the platform's mail package consumes.
 *
 * <pre>
 * Amazon SES event (direct, or wrapped by SNS as {"Type":"Notification","Message":"&lt;this JSON as a string&gt;"}):
 *   {"eventType":"Send|Delivery|Open|Click|Bounce|Complaint|Reject|DeliveryDelay",
 *    "mail":{"messageId":"..","timestamp":"..","source":"from@x","destination":["to@y"]},
 *    "delivery":{"timestamp":"..","recipients":["to@y"]},
 *    "bounce":{"bounceType":"Permanent","bounceSubType":"General","timestamp":"..",
 *              "bouncedRecipients":[{"emailAddress":"to@y","status":"5.1.1","diagnosticCode":".."}]},
 *    "open":{"timestamp":"..","ipAddress":"..","userAgent":".."}, "click":{"timestamp":"..","link":".."}}
 *
 * Kenscio (one event or a list of events):
 *   {"xJob":"&lt;message hash&gt;","eventType":"DELIVER|BOUNCE|COMPLAINT|UNSUB|OPEN|CLICK",
 *    "eventTimestamp":"..","address":"to@y","url":".."}      (also "x-job", "event-type", "event-timestamp")
 * </pre>
 *
 * Mapping: SES mail.messageId / Kenscio xJob -&gt; message_id, event type -&gt; provider_status, recipient address
 * -&gt; mobile, event time -&gt; dlr_received_at, SES mail.source -&gt; sender, provider (SES / KENSCIO) -&gt; service.
 * Normalized: Send -&gt; SENT, Delivery / DELIVER -&gt; DELIVERED, Open / Click -&gt; READ, Bounce -&gt; FAILED,
 * Reject -&gt; REJECTED; Complaint and Unsubscribe stay UNKNOWN (kept as events, they do not change the state
 * of a delivered message).
 */
@Component
@Order(260)
public class EmailDlrAdapter implements DlrProviderAdapter {

    public static final String SOURCE = "EMAIL";

    public enum Provider { SES, KENSCIO }

    private final StatusNormalizer statusNormalizer;
    private final ObjectMapper mapper;
    private final ZoneId zone;

    public EmailDlrAdapter(StatusNormalizer statusNormalizer, ObjectMapper mapper, DlrProperties properties) {
        this.statusNormalizer = statusNormalizer;
        this.mapper = mapper;
        this.zone = ZoneId.of(properties.getTimezone());
    }

    @Override
    public String source() {
        return SOURCE;
    }

    /** True for an SNS envelope, an SES event, or a Kenscio event / list of events. */
    public boolean isEmailPayload(JsonNode json) {
        if (json == null) {
            return false;
        }
        if (json.isArray()) {
            return !json.isEmpty() && provider(json.get(0)) == Provider.KENSCIO;
        }
        return isSnsEnvelope(json) || provider(json) != null;
    }

    private static boolean isSnsEnvelope(JsonNode json) {
        return json.isObject() && json.path("Type").isTextual()
                && (json.has("Message") || json.has("SubscribeURL") || json.has("TopicArn"));
    }

    /** Which provider one event comes from, or null. */
    public Provider provider(JsonNode event) {
        if (event == null || !event.isObject()) {
            return null;
        }
        if (event.path("mail").isObject() && (event.has("eventType") || event.has("notificationType"))) {
            return Provider.SES;
        }
        if (event.has("xJob") || event.has("x-job")) {
            return Provider.KENSCIO;
        }
        return null;
    }

    /** The single events inside a callback: the SES event of an SNS notification, or each Kenscio event. */
    public List<JsonNode> events(JsonNode json) {
        List<JsonNode> out = new ArrayList<>();
        if (json.isArray()) {
            json.forEach(out::add);
        } else if (isSnsEnvelope(json)) {
            if ("Notification".equals(json.path("Type").asText()) && json.path("Message").isTextual()) {
                try {
                    out.add(mapper.readTree(json.path("Message").asText()));
                } catch (Exception e) {
                    out.add(json);      // normalize() rejects it with a clear reason
                }
            }
        } else {
            out.add(json);
        }
        return out;
    }

    /** Ack note for a callback that carries no event (SNS subscription handshake). */
    public String nonEventNote(JsonNode json) {
        String type = json.path("Type").asText("");
        if ("SubscriptionConfirmation".equals(type)) {
            return "SNS SubscriptionConfirmation: open the SubscribeURL once to confirm the subscription ("
                    + json.path("SubscribeURL").asText("") + ")";
        }
        return "no email event in callback" + (type.isEmpty() ? "" : " (SNS " + type + ")");
    }

    @Override
    public boolean supports(String source, JsonNode payload) {
        if (source != null) {
            return SOURCE.equalsIgnoreCase(source);
        }
        return provider(payload) != null;
    }

    @Override
    public String peekMessageId(JsonNode payload) {
        Provider p = provider(payload);
        if (p == Provider.SES) {
            return text(payload.path("mail"), "messageId");
        }
        if (p == Provider.KENSCIO) {
            return first(text(payload, "xJob"), text(payload, "x-job"));
        }
        return null;
    }

    /** One SES event or one Kenscio event. */
    @Override
    public NormalizedDlr normalize(JsonNode e) {
        Provider p = provider(e);
        if (p == null) {
            throw new DlrValidationException(SOURCE, "payload is not an Amazon SES or Kenscio email event");
        }
        String messageId, status, recipient, time, sender = null, code = null, reason = null;
        if (p == Provider.SES) {
            JsonNode mail = e.path("mail");
            messageId = text(mail, "messageId");
            status = first(text(e, "eventType"), text(e, "notificationType"));
            sender = text(mail, "source");
            String key = status == null ? "" : status.toLowerCase(Locale.ROOT);       // "delivery", "bounce", ...
            JsonNode detail = e.path(key.equals("deliverydelay") ? "deliveryDelay" : key);
            time = first(text(detail, "timestamp"), text(mail, "timestamp"));
            recipient = first(text(detail.path("bouncedRecipients").path(0), "emailAddress"),
                    text(detail.path("complainedRecipients").path(0), "emailAddress"),
                    detail.path("recipients").path(0).isTextual() ? detail.path("recipients").path(0).asText() : null,
                    mail.path("destination").path(0).isTextual() ? mail.path("destination").path(0).asText() : null);
            if ("bounce".equals(key)) {
                JsonNode r = detail.path("bouncedRecipients").path(0);
                code = first(text(r, "status"), join(text(detail, "bounceType"), text(detail, "bounceSubType"), "/"));
                reason = first(text(r, "diagnosticCode"),
                        join(text(detail, "bounceType"), text(detail, "bounceSubType"), " - "));
            } else if ("reject".equals(key)) {
                reason = text(detail, "reason");
            } else if ("complaint".equals(key)) {
                reason = text(detail, "complaintFeedbackType");
            }
        } else {
            messageId = first(text(e, "xJob"), text(e, "x-job"));
            status = first(text(e, "eventType"), text(e, "event-type"));
            time = first(text(e, "eventTimestamp"), text(e, "event-timestamp"));
            recipient = text(e, "address");
            if (status != null && status.equalsIgnoreCase("BOUNCE")) {
                code = first(text(e, "bounceType"), text(e, "bounce-type"), text(e, "code"));
                reason = first(text(e, "reason"), text(e, "diagnosticCode"), text(e, "message"));
            }
        }
        List<String> missing = new ArrayList<>();
        if (messageId == null) {
            missing.add(p == Provider.SES ? "mail.messageId is missing" : "xJob is missing");
        }
        if (status == null) {
            missing.add("eventType is missing");
        }
        if (!missing.isEmpty()) {
            throw new DlrValidationException(SOURCE, p.name().toLowerCase(Locale.ROOT) + ": " + String.join("; ", missing));
        }
        NormalizedStatus normalized = statusNormalizer.normalize(SOURCE, status);
        return NormalizedDlr.of(SOURCE)
                .messageId(messageId)
                .mobile(recipient)
                .providerStatus(status)
                .normalizedStatus(normalized)
                .statusCode(code)
                .errorCode(code)
                .errorReason(reason)
                .dlrReceivedAt(parseTimestamp(time, zone).orElse(null))
                .sender(sender)
                .service(p.name());
    }

    private static String join(String a, String b, String sep) {
        return a == null ? b : b == null ? a : a + sep + b;
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || v.isContainerNode() ? null : blankToNull(v.asText());
    }
}
