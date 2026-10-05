package com.smsframework.dlr.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static com.smsframework.dlr.util.DlrValues.blankToNull;
import static com.smsframework.dlr.util.DlrValues.parseTimestamp;

/**
 * Meta (WhatsApp Cloud API) status webhooks.
 *
 * <pre>
 * {"object":"whatsapp_business_account","entry":[{"id":"&lt;WABA&gt;","changes":[{"field":"messages","value":{
 *   "messaging_product":"whatsapp","metadata":{"display_phone_number":"...","phone_number_id":"..."},
 *   "statuses":[{"id":"wamid.HBgM...","status":"sent|delivered|read|failed","timestamp":"1727780000",
 *                "recipient_id":"9198...","biz_opaque_callback_data":"...","conversation":{...},"pricing":{...},
 *                "errors":[{"code":131026,"title":"...","message":"...","error_data":{"details":"..."}}]}]}}]}]}
 * </pre>
 *
 * One webhook can carry several statuses (and several entries/changes); each becomes its own DLR event.
 * Mapping: status.message_id (the platform's own id, when present) -&gt; message_id with the wamid kept as
 * external_message_id; otherwise id (wamid) -&gt; message_id. Then status -&gt; provider_status (sent/delivered/read/failed -&gt;
 * SENT/DELIVERED/READ/FAILED), recipient_id -&gt; mobile, timestamp (epoch s) -&gt; dlr_received_at,
 * errors[0].code -&gt; status_code/error_code, biz_opaque_callback_data -&gt; correlation_id,
 * conversation.id -&gt; request_id, pricing.category -&gt; service,
 * metadata.display_phone_number -&gt; sender.
 */
@Component
@Order(300)
public class MetaWhatsAppDlrAdapter implements DlrProviderAdapter {

    public static final String SOURCE = "META";
    public static final String OBJECT = "whatsapp_business_account";

    private final StatusNormalizer statusNormalizer;
    private final ObjectMapper mapper;
    private final ZoneId zone;
    private final boolean requireMessageId;

    public MetaWhatsAppDlrAdapter(StatusNormalizer statusNormalizer, ObjectMapper mapper, DlrProperties properties) {
        this.statusNormalizer = statusNormalizer;
        this.mapper = mapper;
        this.zone = ZoneId.of(properties.getTimezone());
        this.requireMessageId = properties.getMeta().isRequireMessageId();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    /** A full Meta webhook ({"object":"whatsapp_business_account","entry":[...]}). */
    public boolean isWebhook(JsonNode json) {
        return json != null && json.isObject()
                && (OBJECT.equals(json.path("object").asText()) || (json.path("entry").isArray()
                && json.path("entry").path(0).path("changes").isArray()));
    }

    /**
     * Every status of the webhook as its own envelope: {"object","entry_id","field","metadata","status"}.
     * Inbound messages / template updates (no "statuses") give an empty list.
     */
    public List<ObjectNode> statuses(JsonNode webhook) {
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode entry : webhook.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                for (JsonNode status : value.path("statuses")) {
                    ObjectNode env = mapper.createObjectNode();
                    env.put("object", webhook.path("object").asText(OBJECT));
                    if (entry.hasNonNull("id")) {
                        env.put("entry_id", entry.path("id").asText());
                    }
                    if (change.hasNonNull("field")) {
                        env.put("field", change.path("field").asText());
                    }
                    if (value.has("messaging_product")) {
                        env.set("messaging_product", value.get("messaging_product"));
                    }
                    if (value.has("metadata")) {
                        env.set("metadata", value.get("metadata"));
                    }
                    env.set("status", status);
                    out.add(env);
                }
            }
        }
        return out;
    }

    /** What the webhook carries instead of statuses (e.g. "messages"), for the ack note. */
    public String nonStatusContent(JsonNode webhook) {
        List<String> kinds = new ArrayList<>();
        for (JsonNode entry : webhook.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                change.path("value").fieldNames().forEachRemaining(f -> {
                    if (!List.of("messaging_product", "metadata").contains(f) && !kinds.contains(f)) {
                        kinds.add(f);
                    }
                });
                if (change.hasNonNull("field") && !kinds.contains("field=" + change.path("field").asText())) {
                    kinds.add("field=" + change.path("field").asText());
                }
            }
        }
        return String.join(", ", kinds);
    }

    @Override
    public boolean supports(String source, JsonNode payload) {
        if (source != null) {
            return SOURCE.equalsIgnoreCase(source);
        }
        return payload != null && payload.isObject() && status(payload).has("recipient_id")
                && status(payload).has("id") && status(payload).has("status");
    }

    @Override
    public String peekMessageId(JsonNode payload) {
        if (payload == null) {
            return null;
        }
        String own = text(status(payload), "message_id");
        return own != null || requireMessageId ? own : text(status(payload), "id");
    }

    /** Accepts one envelope from {@link #statuses} or a bare status object. */
    @Override
    public NormalizedDlr normalize(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new DlrValidationException(SOURCE, "payload must be a JSON object");
        }
        JsonNode s = status(payload);
        // The platform adds its own message id next to Meta's wamid: {"id":"wamid...","message_id":"<uuid>"}.
        // That id is what the send API returned, so it is the lookup key; the wamid is kept as external_message_id.
        String wamid = text(s, "id");
        String own = text(s, "message_id");
        if (own == null && requireMessageId) {
            throw new DlrValidationException(SOURCE, "message_id is missing (only Meta's wamid was sent)");
        }
        String id = own != null ? own : wamid;
        String providerStatus = text(s, "status");
        String recipient = text(s, "recipient_id");
        List<String> missing = new ArrayList<>();
        if (id == null) {
            missing.add("message_id / id (wamid) is missing");
        }
        if (providerStatus == null) {
            missing.add("status is missing");
        }
        if (!missing.isEmpty()) {
            throw new DlrValidationException(SOURCE, String.join("; ", missing));
        }
        NormalizedStatus normalized = statusNormalizer.normalize(SOURCE, providerStatus);

        JsonNode error = s.path("errors").path(0);
        String errorCode = error.hasNonNull("code") ? error.path("code").asText() : null;
        String errorReason = null;
        if (!error.isMissingNode()) {
            List<String> parts = new ArrayList<>();
            for (String f : List.of("title", "message")) {
                String t = text(error, f);
                if (t != null && !parts.contains(t)) {
                    parts.add(t);
                }
            }
            String details = text(error.path("error_data"), "details");
            if (details != null && !parts.contains(details)) {
                parts.add(details);
            }
            errorReason = parts.isEmpty() ? null : String.join(" - ", parts);
        }

        JsonNode metadata = payload.path("metadata");
        return NormalizedDlr.of(SOURCE)
                .messageId(id)
                .mobile(recipient)
                .providerStatus(providerStatus)
                .normalizedStatus(normalized)
                .statusCode(errorCode)
                .errorCode(errorCode)
                .errorReason(errorReason)
                .dlrReceivedAt(parseTimestamp(text(s, "timestamp"), zone).orElse(null))
                .correlationId(text(s, "biz_opaque_callback_data"))
                .externalMessageId(own != null ? wamid : null)
                .requestId(text(s.path("conversation"), "id"))
                .service(text(s.path("pricing"), "category"))
                .sender(text(metadata, "display_phone_number"));
    }

    private static JsonNode status(JsonNode payload) {
        JsonNode s = payload.get("status");
        return s != null && s.isObject() ? s : payload;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || v.isContainerNode() ? null : blankToNull(v.asText());
    }
}
