package com.smsframework.dlr.service;

import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.dto.NormalizedDlr;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds the idempotency key for a DLR from configured fields
 * (default: source, message_id, provider_status, status_code, provider_event_id, dlr_received_at).
 *
 * The key is stored in dlr_events.dedup_key which has a partial UNIQUE index, so the same
 * callback delivered to two receiver instances at the same time is recorded exactly once
 * as the "real" event; the other copy is stored as DUPLICATE.
 *
 * Note: two DLRs for the same message with DIFFERENT statuses (SENT then DELIVERED) always have
 * different keys, so legitimate status progressions are never treated as duplicates.
 */
@Component
public class DedupKeyGenerator {

    static final Set<String> ALLOWED_FIELDS = Set.of("source", "message_id", "provider_status", "normalized_status",
            "status_code", "error_code", "provider_event_id", "dlr_received_at", "submit_at", "mobile");

    /** Fields compared case-insensitively (provider statuses often vary in case). Ids stay case-sensitive. */
    private static final Set<String> CASE_INSENSITIVE = Set.of("source", "provider_status", "normalized_status");

    private final List<Function<NormalizedDlr, Object>> extractors;
    private final List<String> fields;

    public DedupKeyGenerator(DlrProperties properties) {
        this.fields = properties.getIdempotency().getKeyFields().stream()
                .map(f -> f.trim().toLowerCase(Locale.ROOT).replace('-', '_'))
                .toList();
        for (String f : fields) {
            if (!ALLOWED_FIELDS.contains(f)) {
                throw new IllegalStateException("Unsupported dlr.idempotency.key-fields entry: " + f
                        + " (allowed: " + ALLOWED_FIELDS + ")");
            }
        }
        if (!fields.contains("message_id")) {
            throw new IllegalStateException("dlr.idempotency.key-fields must contain message_id");
        }
        this.extractors = fields.stream().map(DedupKeyGenerator::extractor).toList();
    }

    public String generate(NormalizedDlr dlr) {
        StringBuilder material = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            Object v = extractors.get(i).apply(dlr);
            String s = v == null ? "<null>" : v.toString().trim();
            if (CASE_INSENSITIVE.contains(fields.get(i))) {
                s = s.toUpperCase(Locale.ROOT);
            }
            // length-prefix each value so values containing the separator can never collide
            material.append(fields.get(i)).append('=').append(s.length()).append(':').append(s).append('|');
        }
        return sha256(material.toString());
    }

    public List<String> fields() {
        return fields;
    }

    private static Function<NormalizedDlr, Object> extractor(String field) {
        return switch (field) {
            case "source" -> NormalizedDlr::getSource;
            // id exactly as received, so part 1 and part 2 of a multipart SMS are different events
            case "message_id" -> d -> d.getProviderMessageId() != null ? d.getProviderMessageId() : d.getMessageId();
            case "provider_status" -> NormalizedDlr::getProviderStatus;
            case "normalized_status" -> NormalizedDlr::getNormalizedStatus;
            case "status_code" -> NormalizedDlr::getStatusCode;
            case "error_code" -> NormalizedDlr::getErrorCode;
            case "provider_event_id" -> NormalizedDlr::getProviderEventId;
            case "dlr_received_at" -> NormalizedDlr::getDlrReceivedAt;
            case "submit_at" -> NormalizedDlr::getSubmitAt;
            case "mobile" -> NormalizedDlr::getMobile;
            default -> throw new IllegalStateException("Unsupported key field " + field);
        };
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
