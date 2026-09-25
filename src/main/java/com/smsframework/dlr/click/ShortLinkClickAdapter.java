package com.smsframework.dlr.click;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.exception.DlrValidationException;
import com.smsframework.dlr.util.DlrValues;
import com.smsframework.dlr.util.MessageIdParts;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.smsframework.dlr.util.DlrValues.blankToNull;

/** Recognises and validates short-link click callbacks ({"event":"short_link","data":{...}}). */
@Component
public class ShortLinkClickAdapter {

    private final ObjectMapper mapper;
    private final Validator validator;
    private final DlrProperties properties;
    private final ZoneId zone;

    public ShortLinkClickAdapter(ObjectMapper mapper, Validator validator, DlrProperties properties) {
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.validator = validator;
        this.properties = properties;
        this.zone = ZoneId.of(properties.getTimezone());
    }

    public boolean isClickPayload(JsonNode payload) {
        DlrProperties.Clicks cfg = properties.getClicks();
        if (!cfg.isEnabled() || payload == null || !payload.isObject()) {
            return false;
        }
        JsonNode event = payload.get("event");
        return event != null && event.isTextual()
                && cfg.getEventTypes().stream().anyMatch(t -> t.equalsIgnoreCase(event.asText().trim()));
    }

    public boolean acceptsSource(String source) {
        return source != null && properties.getClicks().getSources().stream().anyMatch(source::equalsIgnoreCase);
    }

    /** Best-effort message id of an invalid click payload, for diagnostics. */
    public String peekMessageId(JsonNode payload) {
        JsonNode v = payload == null ? null : payload.path("data").path("message_id");
        return v != null && v.isValueNode() ? blankToNull(v.asText()) : null;
    }

    public NormalizedClickEvent normalize(JsonNode payload) {
        ShortLinkClickRequest r;
        try {
            r = mapper.treeToValue(payload, ShortLinkClickRequest.class);
        } catch (MismatchedInputException e) {
            String path = e.getPath().stream()
                    .map(p -> p.getFieldName() == null ? "[" + p.getIndex() + "]" : p.getFieldName())
                    .collect(Collectors.joining("."));
            throw new DlrValidationException("CLICK", "invalid value for field '" + path + "'");
        } catch (Exception e) {
            throw new DlrValidationException("CLICK", "payload could not be parsed");
        }
        var violations = validator.validate(r);
        if (!violations.isEmpty()) {
            throw new DlrValidationException("CLICK", violations.stream().map(ConstraintViolation::getMessage)
                    .sorted().collect(Collectors.joining("; ")));
        }
        ShortLinkClickRequest.Data d = r.getData();
        MessageIdParts.Parsed ids = MessageIdParts.parse(d.getMessageId().trim(), properties.getMessageIdPartSeparator());
        return new NormalizedClickEvent(
                r.getEvent().trim().toLowerCase(Locale.ROOT),
                ids.messageId(),
                ids.original(),
                ids.part(),
                blankToNull(d.getCorrelationId()),
                blankToNull(d.getContact()),
                d.getUrlKey().trim(),
                blankToNull(d.getUrlType() != null ? d.getUrlType() : r.getUrlType()),
                blankToNull(d.getShortUrl()),
                blankToNull(d.getDestinationUrl()),
                blankToNull(d.getChannel()),
                DlrValues.parseInteger(d.getVisitedCount()),
                blankToNull(d.getIpAddress()),
                blankToNull(d.getOperatingSystem()),
                blankToNull(d.getOperatingSystemVersion()),
                blankToNull(d.getBrowser()),
                blankToNull(d.getBrowserVersion()),
                blankToNull(d.getDeviceType()),
                DlrValues.parseTimestamp(d.getClickedAt(), zone).orElse(null),
                DlrValues.parseTimestamp(r.getReceivedAt(), zone).orElse(null));
    }
}
