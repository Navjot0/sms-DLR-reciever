package com.smsframework.dlr.controller;

import com.smsframework.dlr.billing.BillingDetailsResponse;
import com.smsframework.dlr.billing.BillingEventView;
import com.smsframework.dlr.click.ClickDetailsResponse;
import com.smsframework.dlr.domain.ProcessingStatus;
import com.smsframework.dlr.dto.DlrEventView;
import com.smsframework.dlr.dto.DlrStatusResponse;
import com.smsframework.dlr.dto.DlrVerificationRequest;
import com.smsframework.dlr.dto.DlrVerificationResponse;
import com.smsframework.dlr.service.DlrQueryService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Verification APIs for automation. Everything is read from PostgreSQL.
 */
@RestController
@RequestMapping(value = "/api/v1/dlr", produces = MediaType.APPLICATION_JSON_VALUE)
public class DlrQueryController {

    private final DlrQueryService queryService;

    public DlrQueryController(DlrQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Only the DLR, exactly as CPaaS / the provider sent it: the original text, byte for byte (same key order,
     * spacing and escaping). 404 while no DLR has been received.
     * status=submitted|sent|delivered|read|failed|rejected|... returns the DLR sent for that one status.
     * all=true returns a JSON array of every DLR received for the id, oldest first ([] when none).
     * Header X-DLR-Processing-Status says ACCEPTED, or REJECTED for a DLR that arrived but could not be processed,
     * or UNRECOGNIZED for a captured webhook whose format no DLR adapter knows (body = the exact request body,
     * with its original Content-Type; X-Capture-Id names the capture).
     */
    @GetMapping(value = "/{messageId}/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public org.springframework.http.ResponseEntity<?> dlrJson(@PathVariable String messageId,
                                                              @RequestParam(defaultValue = "false") boolean all,
                                                              @RequestParam(required = false) String status) {
        if (all) {
            return org.springframework.http.ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body("[" + String.join(",", queryService.dlrTextAll(messageId)) + "]");
        }
        com.smsframework.dlr.entity.DlrEvent e;
        String notFound;
        if (status != null && !status.isBlank()) {
            e = queryService.dlrEventWithStatus(messageId, status);
            notFound = "no DLR with status '" + status + "' received yet for this message_id";
        } else {
            e = queryService.dlrEvent(messageId);
            if (e == null) {
                // received but not accepted (e.g. a format the receiver did not recognise yet)
                e = queryService.rejectedDlrEvent(messageId);
            }
            notFound = "no DLR received yet for this message_id";
        }
        if (e == null && (status == null || status.isBlank())) {
            // not a DLR the receiver understood, but captured: return the request body exactly as received
            var capture = queryService.latestUninterpretedCapture(messageId);
            if (capture.isPresent()) {
                java.util.UUID cid = (java.util.UUID) capture.get().get("capture_id");
                var raw = queryService.captureRawBody(cid).orElse(null);
                if (raw != null && raw.bytes() != null) {
                    MediaType ct;
                    try {
                        ct = raw.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(raw.contentType());
                    } catch (RuntimeException ex) {
                        ct = MediaType.APPLICATION_OCTET_STREAM;
                    }
                    return org.springframework.http.ResponseEntity.ok().contentType(ct)
                            .header("X-DLR-Processing-Status", "UNRECOGNIZED")
                            .header("X-Interpretation-Status", String.valueOf(capture.get().get("interpretation_status")))
                            .header("X-Capture-Id", cid.toString())
                            .body(raw.bytes());
                }
            }
        }
        if (e == null) {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("message_id", messageId);
            if (status != null && !status.isBlank()) {
                body.put("status", status);
            }
            body.put("received", false);
            body.put("message", notFound);
            return org.springframework.http.ResponseEntity.status(404).body(body);
        }
        boolean rejected = e.getProcessingStatus() == com.smsframework.dlr.domain.ProcessingStatus.REJECTED;
        org.springframework.http.ResponseEntity.BodyBuilder ok = org.springframework.http.ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-DLR-Processing-Status", rejected ? "REJECTED" : "ACCEPTED");
        if (rejected && e.getRejectionReason() != null) {
            ok.header("X-DLR-Rejection-Reason", e.getRejectionReason().replaceAll("[\\r\\n]", " "));
        }
        return ok.body(queryService.rawDlrText(e));
    }

    /**
     * DLR text for many ids in one call: body {"message_ids":[...]}, response a JSON array in the same order,
     * each element exactly as received, with null for an id that has no DLR yet.
     */
    @PostMapping(value = "/json", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public org.springframework.http.ResponseEntity<String> dlrJsonBulk(@RequestBody java.util.Map<String, List<String>> body) {
        List<String> texts = queryService.dlrTextBulk(body == null ? null : body.get("message_ids"));
        return org.springframework.http.ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body("[" + texts.stream().map(t -> t == null ? "null" : t).collect(java.util.stream.Collectors.joining(",")) + "]");
    }

    /** Current DLR state for one message. received=false / status=PENDING when nothing valid arrived yet. */
    @GetMapping("/{messageId}")
    public DlrStatusResponse get(@PathVariable String messageId,
                                 @RequestParam(name = "include_events", defaultValue = "false") boolean includeEvents) {
        return queryService.getStatus(messageId, includeEvents);
    }

    /** Full callback history (APPLIED / IGNORED / DUPLICATE / REJECTED) with raw payloads. */
    @GetMapping("/{messageId}/events")
    public List<DlrEventView> events(@PathVariable String messageId) {
        return queryService.eventViews(messageId);
    }

    /** Billing DLRs received for a message, with the billing summary (units / amount). */
    @GetMapping("/{messageId}/billing")
    public BillingDetailsResponse billing(@PathVariable String messageId) {
        return queryService.billingDetails(messageId);
    }

    /** Short-link clicks recorded for a message, with the click summary. */
    @GetMapping("/{messageId}/clicks")
    public ClickDetailsResponse clicks(@PathVariable String messageId) {
        return queryService.clickDetails(messageId);
    }

    /** Bulk verification for automation. */
    @PostMapping(value = "/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DlrVerificationResponse verify(@Valid @RequestBody DlrVerificationRequest request) {
        return queryService.verify(request);
    }

    /** Lookup by secondary correlation keys. */
    @GetMapping("/search")
    public List<DlrStatusResponse> search(@RequestParam(name = "correlation_id", required = false) String correlationId,
                                          @RequestParam(name = "external_message_id", required = false) String externalMessageId) {
        if (correlationId != null && !correlationId.isBlank()) {
            return queryService.byCorrelationId(correlationId);
        }
        if (externalMessageId != null && !externalMessageId.isBlank()) {
            return queryService.byExternalMessageId(externalMessageId);
        }
        throw new IllegalArgumentException("Provide correlation_id or external_message_id");
    }

    /** Most recent rejected callbacks (debugging malformed / unexpected payloads). */
    @GetMapping("/events/rejected")
    public List<DlrEventView> rejected(@RequestParam(defaultValue = "50") int limit) {
        return queryService.recentByProcessingStatus(ProcessingStatus.REJECTED, limit);
    }

    /** Most recent rejected billing events (invalid entries inside billing callbacks). */
    @GetMapping("/events/billing/rejected")
    public List<BillingEventView> rejectedBilling(@RequestParam(defaultValue = "50") int limit) {
        return queryService.recentBillingByProcessingStatus("REJECTED", limit);
    }
}
