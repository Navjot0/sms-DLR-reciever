package com.smsframework.dlr.billing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.exception.DlrValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static com.smsframework.dlr.util.DlrValues.blankToNull;

/**
 * Billing DLR sent by the SMS gateway next to the status DLR:
 * <pre>
 * {"event_type":"billing","events":[{"transaction_type":"debit","message_id":"9b1b0309-...:1",
 *   "product":"SMS Transactional","units":1,"sale_price":1,"currency":"INR","surcharge":0,"total_amount":1}]}
 * </pre>
 */
@Component
public class DefaultBillingDlrAdapter implements BillingDlrAdapter {

    private final ObjectMapper mapper;
    private final Validator validator;
    private final DlrProperties.Billing config;

    public DefaultBillingDlrAdapter(ObjectMapper mapper, Validator validator, DlrProperties properties) {
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.validator = validator;
        this.config = properties.getBilling();
    }

    @Override
    public boolean isBillingPayload(JsonNode payload) {
        if (!config.isEnabled() || payload == null || !payload.isObject()) {
            return false;
        }
        JsonNode type = payload.get("event_type");
        return type != null && type.isTextual() && type.asText().trim().equalsIgnoreCase(config.getEventType());
    }

    @Override
    public boolean acceptsSource(String source) {
        return source != null && config.getSources().stream().anyMatch(source::equalsIgnoreCase);
    }

    @Override
    public List<NormalizedBillingEvent> normalize(JsonNode payload) {
        JsonNode events = payload.get("events");
        if (events == null || events.isNull()) {
            throw new DlrValidationException("BILLING", "events is missing");
        }
        if (!events.isArray()) {
            throw new DlrValidationException("BILLING", "events must be an array");
        }
        if (events.isEmpty()) {
            throw new DlrValidationException("BILLING", "events is empty");
        }
        if (events.size() > config.getMaxEventsPerCallback()) {
            throw new DlrValidationException("BILLING", "events exceeds " + config.getMaxEventsPerCallback() + " entries");
        }
        List<NormalizedBillingEvent> out = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            out.add(normalizeEvent(i, events.get(i)));
        }
        return out;
    }

    NormalizedBillingEvent normalizeEvent(int index, JsonNode node) {
        String raw = node.toString();
        String peekedId = node.isObject() && node.path("message_id").isValueNode()
                ? truncate(blankToNull(node.path("message_id").asText()), 255) : null;
        if (!node.isObject()) {
            return NormalizedBillingEvent.rejected(index, raw, null, "event must be a JSON object");
        }

        BillingEventRequest r;
        try {
            r = mapper.treeToValue(node, BillingEventRequest.class);
        } catch (MismatchedInputException e) {
            String path = e.getPath().stream().map(p -> p.getFieldName() == null ? "[" + p.getIndex() + "]" : p.getFieldName())
                    .collect(Collectors.joining("."));
            return NormalizedBillingEvent.rejected(index, raw, peekedId, "invalid value for field '" + path + "'");
        } catch (Exception e) {
            return NormalizedBillingEvent.rejected(index, raw, peekedId, "event could not be parsed");
        }
        Set<ConstraintViolation<BillingEventRequest>> violations = validator.validate(r);
        if (!violations.isEmpty()) {
            String reason = violations.stream().map(ConstraintViolation::getMessage).sorted().collect(Collectors.joining("; "));
            return NormalizedBillingEvent.rejected(index, raw, peekedId, reason);
        }

        List<String> errors = new ArrayList<>();
        BigDecimal unitsDecimal = decimal(r.getUnits(), "units", errors);
        BigDecimal salePrice = decimal(r.getSalePrice(), "sale_price", errors);
        BigDecimal surcharge = decimal(r.getSurcharge(), "surcharge", errors);
        BigDecimal totalAmount = decimal(r.getTotalAmount(), "total_amount", errors);
        Integer units = null;
        if (unitsDecimal != null) {
            if (unitsDecimal.signum() < 0) {
                errors.add("units must not be negative");
            } else {
                try {
                    units = unitsDecimal.intValueExact();
                } catch (ArithmeticException e) {
                    errors.add("units must be a whole number");
                }
            }
        }
        if (!errors.isEmpty()) {
            return NormalizedBillingEvent.rejected(index, raw, peekedId, String.join("; ", errors));
        }

        String billingMessageId = r.getMessageId().trim();
        String messageId = billingMessageId;
        Integer part = null;
        String sep = config.getMessageIdPartSeparator();
        if (sep != null && !sep.isEmpty()) {
            int at = billingMessageId.lastIndexOf(sep);
            if (at > 0 && at + sep.length() < billingMessageId.length()) {
                String suffix = billingMessageId.substring(at + sep.length());
                if (suffix.chars().allMatch(Character::isDigit) && suffix.length() <= 6) {
                    messageId = billingMessageId.substring(0, at);
                    part = Integer.parseInt(suffix);
                }
            }
        }

        return new NormalizedBillingEvent(index, raw, messageId, billingMessageId, part,
                r.getTransactionType().trim().toLowerCase(Locale.ROOT), blankToNull(r.getProduct()), units, salePrice,
                upper(blankToNull(r.getCurrency())), surcharge, totalAmount, null);
    }

    private static BigDecimal decimal(String v, String field, List<String> errors) {
        String t = blankToNull(v);
        if (t == null) {
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(t);
            if (d.abs().compareTo(new BigDecimal("1000000000000")) >= 0 || d.scale() > 6) {
                errors.add(field + " is out of range");
                return null;
            }
            return d;
        } catch (NumberFormatException e) {
            errors.add(field + " must be a number");
            return null;
        }
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
