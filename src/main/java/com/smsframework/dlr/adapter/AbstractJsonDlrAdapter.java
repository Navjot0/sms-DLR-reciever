package com.smsframework.dlr.adapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.exception.DlrValidationException;
import com.smsframework.dlr.service.StatusNormalizer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.time.ZoneId;
import java.util.Set;
import java.util.stream.Collectors;

/** Shared plumbing: JSON -> typed request DTO + Bean Validation, producing readable rejection reasons. */
public abstract class AbstractJsonDlrAdapter implements DlrProviderAdapter {

    protected final ObjectMapper mapper;
    protected final Validator validator;
    protected final StatusNormalizer statusNormalizer;
    protected final ZoneId zone;

    protected AbstractJsonDlrAdapter(ObjectMapper mapper, Validator validator, StatusNormalizer statusNormalizer,
                                     DlrProperties properties) {
        // Provider DTOs use explicit @JsonProperty names; make sure unknown fields never fail parsing.
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.validator = validator;
        this.statusNormalizer = statusNormalizer;
        this.zone = ZoneId.of(properties.getTimezone());
    }

    @Override
    public boolean supports(String source, JsonNode payload) {
        if (source != null) {
            return source().equalsIgnoreCase(source);
        }
        return payload != null && payload.isObject() && matchesStructure(payload);
    }

    /** Payload structure detection used when no explicit source is given. */
    protected abstract boolean matchesStructure(JsonNode payload);

    protected <T> T bindAndValidate(JsonNode payload, Class<T> type) {
        if (payload == null || !payload.isObject()) {
            throw new DlrValidationException(source(), "payload must be a JSON object");
        }
        T request;
        try {
            request = mapper.treeToValue(payload, type);
        } catch (MismatchedInputException e) {
            String path = e.getPath().stream()
                    .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                    .collect(Collectors.joining("."));
            throw new DlrValidationException(source(), "invalid value for field '" + path + "'");
        } catch (Exception e) {
            throw new DlrValidationException(source(), "payload could not be parsed: " + e.getClass().getSimpleName());
        }
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String reason = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new DlrValidationException(source(), reason);
        }
        return request;
    }

    protected static boolean hasText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() && !v.asText().isBlank();
    }
}
