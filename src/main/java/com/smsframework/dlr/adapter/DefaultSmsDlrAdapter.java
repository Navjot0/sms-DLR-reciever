package com.smsframework.dlr.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.domain.NormalizedStatus;
import com.smsframework.dlr.dto.DefaultSmsDlrRequest;
import com.smsframework.dlr.dto.NormalizedDlr;
import com.smsframework.dlr.service.StatusNormalizer;
import jakarta.validation.Validator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static com.smsframework.dlr.util.DlrValues.blankToNull;
import static com.smsframework.dlr.util.DlrValues.parseInteger;
import static com.smsframework.dlr.util.DlrValues.parseTimestamp;

/**
 * Default SMS gateway DLR, wrapped: {"payload": {"message_id", "mobile", "status", "code", ...}}
 * or flat: {"message_id", "mobile", "status", "code", ...}.
 */
@Component
@Order(100)
public class DefaultSmsDlrAdapter extends AbstractJsonDlrAdapter {

    public static final String SOURCE = "DEFAULT_SMS";

    public DefaultSmsDlrAdapter(ObjectMapper mapper, Validator validator, StatusNormalizer statusNormalizer,
                                DlrProperties properties) {
        super(mapper, validator, statusNormalizer, properties);
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    protected boolean matchesStructure(JsonNode payload) {
        JsonNode inner = payload.get("payload");
        if (inner != null && inner.isObject()) {
            return inner.has("message_id") || inner.has("status");
        }
        return isFlat(payload);
    }

    /**
     * The gateway sends the same fields either wrapped ({"payload": {...}}) or flat at the root
     * ({"message_id": ..., "mobile": ..., "status": ..., "code": ...}). Both are accepted.
     */
    static boolean isFlat(JsonNode payload) {
        return payload != null && payload.isObject() && !payload.has("payload") && payload.has("message_id")
                && (payload.has("status") || payload.has("mobile"))
                // the platform's RCS webhook also has message_id + status, but is an event with a message object
                && !com.smsframework.dlr.rcs.RcsDlrAdapter.isPlatformEvent(payload);
    }

    @Override
    public String peekMessageId(JsonNode payload) {
        if (payload == null) {
            return null;
        }
        JsonNode v = payload.path("payload").path("message_id");
        if (!v.isValueNode()) {
            v = payload.path("message_id");
        }
        return v.isValueNode() ? blankToNull(v.asText()) : null;
    }

    @Override
    public NormalizedDlr normalize(JsonNode payload) {
        JsonNode envelope = payload;
        if (isFlat(payload)) {
            // Flat form: treat the root object as the "payload" object.
            envelope = mapper.createObjectNode().set("payload", payload);
        }
        DefaultSmsDlrRequest.Payload p = bindAndValidate(envelope, DefaultSmsDlrRequest.class).getPayload();

        String providerStatus = p.getStatus().trim();
        NormalizedStatus normalized = statusNormalizer.normalize(SOURCE, providerStatus);
        String code = blankToNull(p.getCode());
        String errorCode = blankToNull(p.getErrorCode());
        if (errorCode == null && isFailure(normalized)) {
            errorCode = code;
        }

        return NormalizedDlr.of(SOURCE)
                .messageId(p.getMessageId().trim())
                .externalMessageId(blankToNull(p.getExternalMessageId()))
                .correlationId(blankToNull(p.getCorrelationId()))
                .campaignId(blankToNull(p.getCampaignId()))
                .requestId(blankToNull(p.getRequestId()))
                .providerEventId(blankToNull(p.getEventId()))
                .mobile(p.getMobile().trim())
                .sender(blankToNull(p.getSender()))
                .service(blankToNull(p.getService()))
                .providerStatus(providerStatus)
                .normalizedStatus(normalized)
                .statusCode(code)
                .errorCode(errorCode)
                .errorReason(blankToNull(p.getErrorReason()))
                .submitAt(parseTimestamp(p.getSubmitAt(), zone).orElse(null))
                .dlrReceivedAt(parseTimestamp(p.getDlrReceivedAt(), zone).orElse(null))
                .entityId(blankToNull(p.getEntityId()))
                .templateId(blankToNull(p.getTemplateId()))
                .units(parseInteger(p.getUnits()));
    }

    static boolean isFailure(NormalizedStatus s) {
        return s == NormalizedStatus.FAILED || s == NormalizedStatus.EXPIRED || s == NormalizedStatus.REJECTED;
    }
}
