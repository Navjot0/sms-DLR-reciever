package com.smsframework.dlr.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsframework.dlr.billing.BillingResult;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.domain.ProcessingStatus;
import com.smsframework.dlr.exception.DlrPersistenceException;
import com.smsframework.dlr.meta.MetaWebhookResult;
import com.smsframework.dlr.service.DlrProcessingService;
import com.smsframework.dlr.service.DlrProcessingService.ProcessingResult;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Universal webhook capture, then best-effort DLR interpretation.
 *
 * <pre>
 * request -> capture() : stored in webhook_requests and committed (any format; failure -> 5xx, provider retries)
 *         -> interpret(): existing DLR pipeline + generic field extraction; outcome written next to the capture.
 *                         A payload nobody recognises is a successful capture, not a rejected DLR.
 * </pre>
 */
@Service
public class WebhookCaptureService {

    private static final Logger log = LoggerFactory.getLogger(WebhookCaptureService.class);

    private final WebhookCaptureRepository repository;
    private final DlrProcessingService processing;
    private final GenericFieldExtractor extractor;
    private final ObjectMapper mapper;
    private final DlrProperties properties;
    private final MeterRegistry meters;
    private final Set<String> maskedHeaders;

    public WebhookCaptureService(WebhookCaptureRepository repository, DlrProcessingService processing,
                                 GenericFieldExtractor extractor, ObjectMapper mapper, DlrProperties properties,
                                 MeterRegistry meters) {
        this.repository = repository;
        this.processing = processing;
        this.extractor = extractor;
        this.mapper = mapper;
        this.properties = properties;
        this.meters = meters;
        this.maskedHeaders = new java.util.HashSet<>();
        properties.getCapture().getMaskedHeaders().forEach(h -> maskedHeaders.add(h.toLowerCase(Locale.ROOT)));
        String apiKeyHeader = properties.getSecurity().getApiKey().getHeaderName();
        if (apiKeyHeader != null) {
            maskedHeaders.add(apiKeyHeader.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Stores the request. Returns once the row is committed; a database problem surfaces as an exception
     * (mapped to 503 so the sender retries).
     *
     * @param body      the bytes read, or null when the body exceeded the limit
     * @param bodySize  size of the body in bytes
     */
    public CapturedRequest capture(HttpServletRequest request, byte[] body, long bodySize) {
        boolean truncated = body == null;
        String contentType = request.getContentType();
        Charset charset = charset(contentType);
        String text = null;
        String encoding = null;
        if (!truncated) {
            text = decode(body, charset);
            encoding = text != null ? charset.name().toLowerCase(Locale.ROOT) : "binary";
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        CapturedRequest c = new CapturedRequest(0, UUID.randomUUID(), null, request.getMethod(), path,
                request.getQueryString(), parseQuery(request.getQueryString()), headers(request), contentType,
                body, bodySize, truncated, encoding, text, remoteAddress(request));
        try {
            CapturedRequest stored = repository.insert(c);
            meters.counter("webhook.captured").increment();
            return stored;
        } catch (DataAccessException e) {
            meters.counter("webhook.capture.failed").increment();
            throw new DlrPersistenceException("Failed to capture webhook request", e);
        }
    }

    /** Records the outcome for a body that was not stored (larger than the limit). */
    public void markTooLarge(CapturedRequest c, long limit) {
        repository.updateInterpretation(c.captureId(), new Interpretation(InterpretationStatus.TOO_LARGE,
                "body of " + c.bodySize() + " bytes exceeds the limit of " + limit + " bytes; body not stored",
                null, null, null, null, null, null, null, null));
    }

    /**
     * Runs the DLR pipeline over the captured body and records what it found. Never throws for payload
     * problems; a database failure is rethrown so the sender gets a retryable 5xx (the capture stays stored).
     *
     * @return the pipeline result, or null when the body was not handed to it (binary) or it failed
     */
    public Outcome interpret(CapturedRequest c, String explicitSource) {
        GenericFieldExtractor.Extracted ex = extractor.extract(c.bodyText(), c.contentType());
        if (c.bodyText() == null) {
            // binary body: nothing to interpret
            Interpretation i = new Interpretation(InterpretationStatus.NOT_JSON, "binary body (not valid "
                    + charset(c.contentType()).name() + " text)", explicitSource, null, null, null, null, null, null, null);
            save(c, i);
            return new Outcome(null, i);
        }
        ProcessingResult r;
        try {
            r = processing.process(explicitSource, c.bodyText());
        } catch (DlrPersistenceException | DataAccessException e) {
            safeSave(c, new Interpretation(InterpretationStatus.ERROR, "database error during interpretation: "
                    + e.getMessage(), explicitSource, ex.messageId(), null, ex.recipient(), ex.status(), null, null, null));
            throw e;
        } catch (RuntimeException e) {
            log.error("Interpretation failed capture_id={}", c.captureId(), e);
            Interpretation i = new Interpretation(InterpretationStatus.ERROR, e.getClass().getSimpleName() + ": "
                    + e.getMessage(), explicitSource, ex.messageId(), null, ex.recipient(), ex.status(), null, null, null);
            safeSave(c, i);
            return new Outcome(null, i);
        }
        Interpretation i = classify(c, r, ex);
        save(c, i);
        return new Outcome(r, i);
    }

    /** Result of interpret(): the pipeline result (may be null) and what was recorded. */
    public record Outcome(ProcessingResult result, Interpretation interpretation) {
    }

    private Interpretation classify(CapturedRequest c, ProcessingResult r, GenericFieldExtractor.Extracted ex) {
        String resultJson = json(r.isMeta() ? r.meta() : r.isBilling() ? r.billing() : r.isClick() ? r.click() : r);
        List<String> ids = new ArrayList<>();
        InterpretationStatus status;
        String error = null;
        if (r.isMeta()) {
            MetaWebhookResult m = r.meta();
            m.results().forEach(it -> { if (it.messageId() != null) ids.add(it.messageId()); });
            status = m.statuses() == 0 ? InterpretationStatus.NO_DLR
                    : m.rejected() == m.statuses() ? InterpretationStatus.INVALID_DLR
                    : m.rejected() > 0 ? InterpretationStatus.PARTIAL : InterpretationStatus.INTERPRETED;
            error = m.statuses() == 0 ? m.note() : null;
        } else if (r.isBilling()) {
            BillingResult b = r.billing();
            b.events().forEach(o -> { if (o.messageId() != null) ids.add(o.messageId()); });
            status = switch (b.processingStatus()) {
                case "REJECTED" -> InterpretationStatus.INVALID_DLR;
                case "PARTIAL" -> InterpretationStatus.PARTIAL;
                default -> InterpretationStatus.INTERPRETED;
            };
        } else if (r.isClick()) {
            if (r.click().messageId() != null) {
                ids.add(r.click().messageId());
            }
            status = InterpretationStatus.INTERPRETED;
        } else if (r.processingStatus() == ProcessingStatus.UNRECOGNIZED) {
            String kind = r.note() == null ? "" : r.note();
            status = switch (kind) {
                case DlrProcessingService.UNRECOGNIZED_EMPTY -> InterpretationStatus.EMPTY;
                case DlrProcessingService.UNRECOGNIZED_MALFORMED ->
                        looksLikeJson(c) ? InterpretationStatus.MALFORMED_JSON : InterpretationStatus.NOT_JSON;
                default -> InterpretationStatus.UNRECOGNIZED;
            };
            error = r.rejectionReason();
            String mid = ex.messageId() != null ? ex.messageId() : r.messageId();
            if (mid != null) {
                ids.add(mid);
            }
            if (r.messageId() != null) {
                ids.add(r.messageId());
            }
            // never invent a normalized status for something that was not understood
            return new Interpretation(status, error, r.source(), mid, withBaseIds(ids), ex.recipient(), ex.status(),
                    null, null, resultJson);
        } else if (r.processingStatus() == ProcessingStatus.REJECTED) {
            status = InterpretationStatus.INVALID_DLR;
            error = r.rejectionReason();
            if (r.messageId() != null) {
                ids.add(r.messageId());
            }
        } else {
            status = InterpretationStatus.INTERPRETED;
            if (r.messageId() != null) {
                ids.add(r.messageId());
            }
        }
        List<String> unique = withBaseIds(ids);
        String mid = unique.isEmpty() ? ex.messageId() : unique.get(0);
        if (unique.isEmpty() && mid != null) {
            unique = withBaseIds(List.of(mid));
        }
        String normalized = status == InterpretationStatus.INTERPRETED && r.normalizedStatus() != null
                ? r.normalizedStatus().name() : null;
        return new Interpretation(status, error, r.source(), mid, unique, ex.recipient(), ex.status(), normalized,
                r.eventId(), resultJson);
    }

    /** Ids as given plus their "&lt;id&gt;" form without the ":&lt;part&gt;" suffix, no duplicates. */
    private List<String> withBaseIds(List<String> ids) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        String sep = properties.getMessageIdPartSeparator();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            all.add(id);
            String base = com.smsframework.dlr.util.MessageIdParts.base(id, sep);
            if (base != null) {
                all.add(base);
            }
        }
        return new ArrayList<>(all);
    }

    private void save(CapturedRequest c, Interpretation i) {
        repository.updateInterpretation(c.captureId(), i);
        meters.counter("webhook.interpretation", "status", i.status().name()).increment();
    }

    private void safeSave(CapturedRequest c, Interpretation i) {
        try {
            save(c, i);
        } catch (RuntimeException e) {
            log.warn("Could not record interpretation for capture_id={}: {}", c.captureId(), e.getMessage());
        }
    }

    private static boolean looksLikeJson(CapturedRequest c) {
        String ct = c.contentType() == null ? "" : c.contentType().toLowerCase(Locale.ROOT);
        String t = c.bodyText() == null ? "" : c.bodyText().stripLeading();
        return ct.contains("json") || t.startsWith("{") || t.startsWith("[");
    }

    /** charset parameter of the content type; UTF-8 when absent or unknown. */
    static Charset charset(String contentType) {
        if (contentType != null) {
            try {
                MediaType mt = MediaType.parseMediaType(contentType);
                if (mt.getCharset() != null) {
                    return mt.getCharset();
                }
            } catch (RuntimeException ignored) {
                // malformed content type: default
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** Strict decode: null when the bytes are not valid text in that charset (binary bodies). */
    static String decode(byte[] body, Charset charset) {
        if (body.length == 0) {
            return "";
        }
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Query string as {name: [values]} in the order sent (URL-decoded). */
    public static Map<String, List<String>> parseQuery(String qs) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        if (qs == null || qs.isEmpty()) {
            return m;
        }
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = urlDecode(eq < 0 ? pair : pair.substring(0, eq));
            String v = eq < 0 ? "" : urlDecode(pair.substring(eq + 1));
            m.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return m;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    /** All headers, names lower-cased, credentials masked. */
    private Map<String, List<String>> headers(HttpServletRequest request) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String key = name.toLowerCase(Locale.ROOT);
            List<String> values = Collections.list(request.getHeaders(name));
            if (maskedHeaders.contains(key)) {
                values = values.stream().map(v -> "***").toList();
            }
            m.computeIfAbsent(key, k -> new ArrayList<>()).addAll(values);
        }
        return m;
    }

    /** Peer address; X-Forwarded-For (first hop) only when the receiver is configured behind a trusted proxy. */
    private String remoteAddress(HttpServletRequest request) {
        if (properties.getSecurity().getIpAllowlist().isTrustForwardedFor()) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String json(Object o) {
        try {
            return o == null ? null : mapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
