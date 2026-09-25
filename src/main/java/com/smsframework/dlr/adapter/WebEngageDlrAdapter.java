package com.smsframework.dlr.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.domain.NormalizedStatus;
import com.smsframework.dlr.dto.NormalizedDlr;
import com.smsframework.dlr.dto.WebEngageDlrRequest;
import com.smsframework.dlr.service.StatusNormalizer;
import jakarta.validation.Validator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static com.smsframework.dlr.util.DlrValues.blankToNull;
import static com.smsframework.dlr.util.DlrValues.parseInteger;
import static com.smsframework.dlr.util.DlrValues.parseTimestamp;

/**
 * WebEngage SMS DLR: {"version", "messageId", "toNumber", "status", "statusCode", "smsCount"}.
 * messageId -> message_id (primary automation lookup key), toNumber -> mobile, smsCount -> units.
 * "sms_sent" is normalized to SENT, never DELIVERED.
 */
@Component
@Order(200)
public class WebEngageDlrAdapter extends AbstractJsonDlrAdapter {

    public static final String SOURCE = "WEBENGAGE";

    public WebEngageDlrAdapter(ObjectMapper mapper, Validator validator, StatusNormalizer statusNormalizer,
                               DlrProperties properties) {
        super(mapper, validator, statusNormalizer, properties);
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    protected boolean matchesStructure(JsonNode payload) {
        return payload.has("messageId") && (payload.has("toNumber") || payload.has("status"));
    }

    @Override
    public String peekMessageId(JsonNode payload) {
        JsonNode v = payload == null ? null : payload.path("messageId");
        return v != null && v.isValueNode() ? blankToNull(v.asText()) : null;
    }

    @Override
    public NormalizedDlr normalize(JsonNode payload) {
        WebEngageDlrRequest r = bindAndValidate(payload, WebEngageDlrRequest.class);

        String providerStatus = r.getStatus().trim();
        NormalizedStatus normalized = statusNormalizer.normalize(SOURCE, providerStatus);
        String statusCode = blankToNull(r.getStatusCode());
        boolean failure = DefaultSmsDlrAdapter.isFailure(normalized);

        return NormalizedDlr.of(SOURCE)
                .messageId(r.getMessageId().trim())
                .mobile(r.getToNumber().trim())
                .providerStatus(providerStatus)
                .normalizedStatus(normalized)
                .statusCode(statusCode)
                .errorCode(failure ? statusCode : null)
                .errorReason(failure ? blankToNull(r.getMessage()) : null)
                .dlrReceivedAt(parseTimestamp(r.getTimestamp(), zone).orElse(null))
                .units(parseInteger(r.getSmsCount()));
    }
}
