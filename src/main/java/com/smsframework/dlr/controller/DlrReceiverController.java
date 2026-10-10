package com.smsframework.dlr.controller;

import com.smsframework.dlr.capture.CapturedRequest;
import com.smsframework.dlr.capture.Interpretation;
import com.smsframework.dlr.capture.WebhookCaptureService;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.dto.DlrReceiveResponse;
import com.smsframework.dlr.service.DlrProcessingService.ProcessingResult;
import com.smsframework.dlr.service.SourceResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Universal callback endpoint: {@code /api/v1/dlr/receive} accepts any method, content type and body.
 *
 * <ol>
 *   <li><b>Capture</b> - the request (method, path, query, headers, content type, exact body bytes, sender) is
 *       written to webhook_requests and committed. Nothing about the payload can make this step fail.</li>
 *   <li><b>Interpret</b> - the existing DLR pipeline (SMS, WebEngage, Meta, RCS, email, billing, clicks) runs over
 *       the body. A payload it does not recognise stays a successful capture (interpretation UNRECOGNIZED /
 *       MALFORMED_JSON / NOT_JSON / EMPTY); it is not a rejected DLR.</li>
 * </ol>
 *
 * Response codes (for the SENDER, not for automation):
 * <ul>
 *   <li>dlr.capture.ack-status (default 200) - captured (whatever the interpretation); header X-Capture-Id</li>
 *   <li>413 - body larger than dlr.api.max-payload-bytes (metadata recorded, body not stored)</li>
 *   <li>401/403 - authentication failed (nothing stored)</li>
 *   <li>503 - database unavailable: nothing acknowledged, the sender should retry</li>
 * </ul>
 * Meta's callback-URL check (GET ?hub.mode=subscribe&amp;hub.verify_token=..&amp;hub.challenge=..) is answered
 * with the challenge and not captured.
 */
@RestController
@RequestMapping("/api/v1/dlr")
public class DlrReceiverController {

    public static final String CAPTURE_ID_HEADER = "X-Capture-Id";
    public static final String INTERPRETATION_HEADER = "X-Interpretation-Status";

    private final WebhookCaptureService captureService;
    private final SourceResolver sourceResolver;
    private final DlrProperties properties;

    public DlrReceiverController(WebhookCaptureService captureService, SourceResolver sourceResolver,
                                 DlrProperties properties) {
        this.captureService = captureService;
        this.sourceResolver = sourceResolver;
        this.properties = properties;
    }

    @RequestMapping(value = "/receive", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE}, produces = MediaType.ALL_VALUE)
    public ResponseEntity<?> receive(HttpServletRequest request) throws IOException {
        Map<String, List<String>> query = WebhookCaptureService.parseQuery(request.getQueryString());
        if ("GET".equals(request.getMethod()) && query.containsKey("hub.mode")) {
            return verifyMetaWebhook(first(query, "hub.mode"), first(query, "hub.verify_token"),
                    first(query, "hub.challenge"));
        }

        long limit = properties.getApi().getMaxPayloadBytes();
        byte[] body = readBounded(request.getInputStream(), limit + 1);
        if (body.length > limit) {
            long declared = request.getContentLengthLong();
            CapturedRequest c = captureService.capture(request, null, declared > 0 ? declared : body.length);
            captureService.markTooLarge(c, limit);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", 413);
            err.put("error", "PAYLOAD_TOO_LARGE");
            err.put("message", "body exceeds " + limit + " bytes; request metadata recorded, body not stored");
            err.put("captured", false);
            err.put("capture_id", c.captureId().toString());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).header(CAPTURE_ID_HEADER, c.captureId().toString())
                    .contentType(MediaType.APPLICATION_JSON).body(err);
        }

        // 1. capture (committed before anything else happens; DB failure -> 503)
        CapturedRequest c = captureService.capture(request, body, body.length);
        // 2. interpret (DB failure -> 503 so the sender retries; the capture itself is already stored)
        WebhookCaptureService.Outcome outcome = captureService.interpret(c, sourceResolver.resolveExplicit(request));
        Interpretation i = outcome.interpretation();
        ProcessingResult r = outcome.result();

        HttpStatus ack = HttpStatus.valueOf(properties.getCapture().getAckStatus());
        ResponseEntity.BodyBuilder reply = ResponseEntity.status(ack).contentType(MediaType.APPLICATION_JSON)
                .header(CAPTURE_ID_HEADER, c.captureId().toString())
                .header(INTERPRETATION_HEADER, i.status().name());
        if (r != null && r.isMeta()) {
            return reply.body(r.meta());
        }
        if (r != null && r.isClick()) {
            return reply.body(r.click());
        }
        if (r != null && r.isBilling()) {
            return reply.body(r.billing());
        }
        if (r == null) {
            return reply.body(new DlrReceiveResponse(null, i.source(), i.messageId(), null, null, null, null,
                    i.error(), true, c.captureId().toString(), i.status().name(), i.messageId()));
        }
        // UNRECOGNIZED is not a rejection: the reason is only a note
        boolean unrecognized = r.processingStatus() == com.smsframework.dlr.domain.ProcessingStatus.UNRECOGNIZED;
        return reply.body(new DlrReceiveResponse(r.eventId(), r.source(), r.messageId(), r.processingStatus(),
                r.normalizedStatus() == null ? null : r.normalizedStatus().name(), r.currentStatus(),
                unrecognized ? null : r.rejectionReason(), unrecognized ? r.rejectionReason() : r.note(), true,
                c.captureId().toString(), i.status().name(), i.messageId()));
    }

    /**
     * Meta webhook verification: Meta calls GET {callback}?hub.mode=subscribe&hub.verify_token=..&hub.challenge=..
     * when the callback URL is saved. Answer with the challenge when the token matches dlr.meta.verify-token.
     */
    private ResponseEntity<String> verifyMetaWebhook(String mode, String token, String challenge) {
        String expected = properties.getMeta().getVerifyToken();
        if (!"subscribe".equals(mode) || challenge == null) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("expected hub.mode=subscribe and hub.challenge");
        }
        if (expected == null || expected.isBlank() || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), String.valueOf(token).getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_PLAIN).body("verify token mismatch");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
    }

    private static String first(Map<String, List<String>> query, String name) {
        List<String> v = query.get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    private static byte[] readBounded(InputStream in, long limit) throws IOException {
        return in.readNBytes((int) Math.min(limit, Integer.MAX_VALUE - 8));
    }
}
