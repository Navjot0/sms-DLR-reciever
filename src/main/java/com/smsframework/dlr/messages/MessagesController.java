package com.smsframework.dlr.messages;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GET /api/v1/dlr/messages -- Individual Messages: one row per DLR event, single callbacks and events split out of
 * bulk pushes alike. Server-side filters and paging (offset / limit, newest first).
 *
 * <ul>
 *   <li>message_id: exact (the id as sent, e.g. "&lt;id&gt;:886", or the base id for all its recipients)</li>
 *   <li>message_id_like: partial match; mobile: partial; status: provider (DELIVRD) or normalized (DELIVERED)</li>
 *   <li>batch_id, source_type (SINGLE | BULK), from / to (ISO date-time), capture_id, processing_status</li>
 * </ul>
 * Each row has {@code status} (the provider's delivery status, exactly as sent) and {@code processing_status} (what the
 * receiver did: APPLIED, IGNORED, DUPLICATE, REJECTED, UNRECOGNIZED, MALFORMED, ERROR ...) -- two different things.
 */
@RestController
public class MessagesController {

    private static final int MAX_LIMIT = 200;

    private final MessageRepository repository;

    public MessagesController(MessageRepository repository) {
        this.repository = repository;
    }

    @GetMapping(value = "/api/v1/dlr/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> messages(
            @RequestParam(name = "message_id", required = false) String messageId,
            @RequestParam(name = "message_id_like", required = false) String messageIdLike,
            @RequestParam(required = false) String mobile,
            @RequestParam(required = false) String status,
            @RequestParam(name = "batch_id", required = false) String batchId,
            @RequestParam(name = "source_type", required = false) String sourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(name = "capture_id", required = false) UUID captureId,
            @RequestParam(name = "processing_status", required = false) String processingStatus,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        int n = Math.max(1, Math.min(limit, MAX_LIMIT));
        int from0 = Math.max(0, offset);
        List<Map<String, Object>> rows = repository.search(new MessageRepository.Filter(messageId, messageIdLike, mobile,
                status, batchId, sourceType, from, to, captureId, processingStatus), from0, n);
        boolean more = rows.size() > n;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offset", from0);
        body.put("limit", n);
        body.put("has_more", more);
        body.put("items", more ? rows.subList(0, n) : rows);
        return body;
    }
}
