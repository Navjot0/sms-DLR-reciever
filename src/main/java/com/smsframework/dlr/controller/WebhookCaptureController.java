package com.smsframework.dlr.controller;

import com.smsframework.dlr.capture.WebhookCaptureRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Raw webhook captures (every request that reached /api/v1/dlr/receive, whatever its format).
 *
 * <p>A <b>capture_id</b> identifies one HTTP request received by this service. It is not a message id: the
 * message id (if any) is what the DLR adapters or the generic extractor found inside the body
 * ({@code extracted_message_id}, {@code message_ids}). Use capture ids to inspect what was sent; keep using
 * message ids with /api/v1/dlr/{message_id} and /verify for DLR state.</p>
 *
 * Protected like the DLR query API when dlr.security.protect-query-api is on.
 */
@RestController
@RequestMapping(value = "/api/v1/webhooks/requests", produces = MediaType.APPLICATION_JSON_VALUE)
public class WebhookCaptureController {

    private static final int MAX_LIMIT = 200;

    private final WebhookCaptureRepository repository;

    public WebhookCaptureController(WebhookCaptureRepository repository) {
        this.repository = repository;
    }

    /**
     * Newest first. Live polling: after_id=&lt;cursor&gt; returns only newer captures; older pages: before_id=&lt;id
     * of the last row&gt;. status = INTERPRETED | PARTIAL | INVALID_DLR | NO_DLR | UNRECOGNIZED | MALFORMED_JSON |
     * NOT_JSON | EMPTY | TOO_LARGE | ERROR, or the groups interpreted / unrecognized / errors.
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(name = "after_id", required = false) Long afterId,
                                    @RequestParam(name = "before_id", required = false) Long beforeId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(name = "message_id", required = false) String messageId,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "50") int limit) {
        int n = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Map<String, Object>> items = repository.list(afterId, beforeId, status, source, messageId, q, n);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        // cursor for the next poll: newest id seen (or the current one when nothing new)
        long newest = items.isEmpty() ? (afterId != null ? afterId : repository.maxId()) : (Long) items.get(0).get("id");
        body.put("cursor", newest);
        body.put("next_before_id", items.size() == n ? items.get(items.size() - 1).get("id") : null);
        return body;
    }

    /** Same as the list, for search forms: q matches capture id, message id, body text, headers or query string. */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(required = false) String q,
                                      @RequestParam(name = "message_id", required = false) String messageId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String source,
                                      @RequestParam(name = "before_id", required = false) Long beforeId,
                                      @RequestParam(defaultValue = "50") int limit) {
        return list(null, beforeId, status, source, messageId, q, limit);
    }

    /** The newest capture (optionally for one message id): full detail. 404 when there is none. */
    @GetMapping("/latest")
    public ResponseEntity<?> latest(@RequestParam(name = "message_id", required = false) String messageId,
                                    @RequestParam(required = false) String status) {
        List<Map<String, Object>> items = repository.list(null, null, status, null, messageId, null, 1);
        if (items.isEmpty()) {
            return notFound("no webhook request captured" + (messageId == null ? "" : " for message_id " + messageId));
        }
        return detail(items.get(0).get("capture_id").toString());
    }

    /** One capture: request line, query parameters, headers (secrets masked), body, interpretation. */
    @GetMapping("/{captureId}")
    public ResponseEntity<?> detail(@PathVariable String captureId) {
        UUID id = parse(captureId);
        if (id == null) {
            return notFound("capture_id must be a UUID");
        }
        return repository.detail(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound("no capture " + captureId));
    }

    /** The body exactly as received (same bytes), served with the original Content-Type. */
    @GetMapping(value = "/{captureId}/raw", produces = MediaType.ALL_VALUE)
    public ResponseEntity<?> raw(@PathVariable String captureId) {
        UUID id = parse(captureId);
        if (id == null) {
            return notFound("capture_id must be a UUID");
        }
        WebhookCaptureRepository.RawBody raw = repository.rawBody(id).orElse(null);
        if (raw == null) {
            return notFound("no capture " + captureId);
        }
        if (raw.truncated()) {
            return ResponseEntity.status(HttpStatus.GONE).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("capture_id", captureId, "message", "body exceeded the size limit and was not stored"));
        }
        MediaType ct;
        try {
            ct = raw.contentType() == null || raw.contentType().isBlank() ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(raw.contentType());
        } catch (RuntimeException e) {
            ct = MediaType.APPLICATION_OCTET_STREAM;
        }
        byte[] bytes = raw.bytes() == null ? new byte[0] : raw.bytes();
        return ResponseEntity.ok().contentType(ct).contentLength(bytes.length)
                .header("X-Capture-Id", captureId)
                .header("X-Content-Type-Options", "nosniff")
                // shown as data, never rendered as a page from this origin
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header("Content-Disposition", "inline; filename=\"" + captureId + ".body\"")
                .body(bytes);
    }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> notFound(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", 404);
        m.put("error", "NOT_FOUND");
        m.put("message", message);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(m);
    }
}
