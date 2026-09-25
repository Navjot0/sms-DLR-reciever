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
