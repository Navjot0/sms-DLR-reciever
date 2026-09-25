package com.smsframework.dlr.controller;

import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.domain.ProcessingStatus;
import com.smsframework.dlr.dto.DlrReceiveResponse;
import com.smsframework.dlr.service.DlrProcessingService;
import com.smsframework.dlr.service.DlrProcessingService.ProcessingResult;
import com.smsframework.dlr.service.SourceResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single generic callback endpoint for every provider, for both status DLRs and billing DLRs
 * ({"event_type":"billing","events":[...]}).
 *
 * The body is taken as a raw String on purpose: malformed JSON must still be persisted (as REJECTED
 * with its raw payload) instead of failing inside Jackson before our code runs.
 *
 * Response codes (for the PROVIDER, not for automation):
 *  200 - persisted (APPLIED / IGNORED / DUPLICATE)
 *  400 - persisted as REJECTED (configurable: dlr.api.rejected-http-status)
 *  413 - payload too large (persisted as REJECTED with a prefix of the body)
 *  401/403 - authentication failed (not persisted)
 *  503 - database unavailable, provider should retry
 */
@RestController
@RequestMapping("/api/v1/dlr")
public class DlrReceiverController {

    private final DlrProcessingService processingService;
    private final SourceResolver sourceResolver;
    private final DlrProperties properties;

    public DlrReceiverController(DlrProcessingService processingService, SourceResolver sourceResolver,
                                 DlrProperties properties) {
        this.processingService = processingService;
        this.sourceResolver = sourceResolver;
        this.properties = properties;
    }

    @PostMapping(value = "/receive", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> receive(@RequestBody(required = false) String body, HttpServletRequest request) {
        String explicitSource = sourceResolver.resolveExplicit(request);
        ProcessingResult r = processingService.process(explicitSource, body);

        if (r.isClick()) {
            return ResponseEntity.ok(r.click());
        }
        if (r.isBilling()) {
            // Billing callback: per-event outcomes. 400 only when every event was rejected.
            HttpStatus status = "REJECTED".equals(r.billing().processingStatus())
                    ? HttpStatus.valueOf(properties.getApi().getRejectedHttpStatus()) : HttpStatus.OK;
            return ResponseEntity.status(status).body(r.billing());
        }

        DlrReceiveResponse response = new DlrReceiveResponse(r.eventId(), r.source(), r.messageId(),
                r.processingStatus(), r.normalizedStatus() == null ? null : r.normalizedStatus().name(),
                r.currentStatus(), r.rejectionReason(), r.note());

        HttpStatus status;
        if (r.processingStatus() == ProcessingStatus.REJECTED) {
            status = r.payloadTooLarge() ? HttpStatus.PAYLOAD_TOO_LARGE
                    : HttpStatus.valueOf(properties.getApi().getRejectedHttpStatus());
        } else {
            status = HttpStatus.OK;
        }
        return ResponseEntity.status(status).body(response);
    }
}
