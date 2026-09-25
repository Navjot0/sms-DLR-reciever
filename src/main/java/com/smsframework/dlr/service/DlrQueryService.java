package com.smsframework.dlr.service;

import com.smsframework.dlr.billing.BillingDetailsResponse;
import com.smsframework.dlr.billing.BillingEventView;
import com.smsframework.dlr.billing.BillingProcessingService;
import com.smsframework.dlr.billing.BillingSummary;
import com.smsframework.dlr.billing.DlrBillingRepository;
import com.smsframework.dlr.click.ClickDetailsResponse;
import com.smsframework.dlr.click.ClickSummary;
import com.smsframework.dlr.click.DlrClickRepository;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.domain.NormalizedStatus;
import com.smsframework.dlr.domain.ProcessingStatus;
import com.smsframework.dlr.dto.DlrEventView;
import com.smsframework.dlr.dto.DlrStatusResponse;
import com.smsframework.dlr.dto.DlrVerificationRequest;
import com.smsframework.dlr.dto.DlrVerificationResponse;
import com.smsframework.dlr.entity.DlrEvent;
import com.smsframework.dlr.entity.DlrMessageStatus;
import com.smsframework.dlr.mapper.DlrMapper;
import com.smsframework.dlr.util.MessageIdParts;
import com.smsframework.dlr.repository.DlrEventRepository;
import com.smsframework.dlr.repository.DlrMessageStatusRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read side used by automation: always answers from PostgreSQL, never from memory. */
@Service
@Transactional(readOnly = true)
public class DlrQueryService {

    private final DlrMessageStatusRepository statuses;
    private final DlrEventRepository events;
    private final DlrMapper mapper;
    private final DlrProperties properties;
    private final DlrBillingRepository billing;
    private final BillingProcessingService billingService;
    private final DlrClickRepository clicks;

    public DlrQueryService(DlrMessageStatusRepository statuses, DlrEventRepository events, DlrMapper mapper,
                           DlrProperties properties, DlrBillingRepository billing,
                           BillingProcessingService billingService, DlrClickRepository clicks) {
        this.clicks = clicks;
        this.billing = billing;
        this.billingService = billingService;
        this.statuses = statuses;
        this.events = events;
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * Current DLR for a message. "c9b2...:1" (a part of a multipart SMS, as some DLRs carry it) and "c9b2..."
     * resolve to the same message.
     */
    public DlrStatusResponse getStatus(String requestedId, boolean includeEvents) {
        String messageId = normalizeId(requestedId);
        String requested = messageId.equals(requestedId) ? null : requestedId;
        BillingSummary summary = billingSummary(messageId);
        ClickSummary clickSummary = clickSummary(messageId);
        List<DlrStatusResponse.PartStatus> parts = partStatuses(messageId);
        DlrStatusResponse response = statuses.findByMessageId(messageId)
                .map(s -> {
                    DlrStatusResponse r = mapper.toStatusResponse(s).withBilling(summary).withClicks(clickSummary);
                    return includeEvents ? r.withEvents(eventViews(messageId)) : r;
                })
                .orElseGet(() -> {
                    DlrStatusResponse r = notReceived(messageId, includeEvents);
                    r = summary.billed() ? r.withBilling(summary) : r;
                    return clickSummary.clicked() ? r.withClicks(clickSummary) : r;
                });
        return parts.isEmpty() && requested == null ? response : response.withParts(parts.isEmpty() ? null : parts, requested);
    }

    public String normalizeId(String id) {
        return MessageIdParts.base(id, properties.getMessageIdPartSeparator());
    }

    private List<DlrStatusResponse.PartStatus> partStatuses(String messageId) {
        return events.findLatestPerPart(messageId).stream()
                .map(e -> new DlrStatusResponse.PartStatus(e.getPartNumber(), e.getProviderMessageId(),
                        e.getProviderStatus(), e.getNormalizedStatus(), e.getStatusCode()))
                .toList();
    }

    public BillingSummary billingSummary(String messageId) {
        return billing.summarize(List.of(messageId), billingService.debitTypes(), billingService.creditTypes())
                .getOrDefault(messageId, BillingSummary.NOT_BILLED);
    }

    public ClickSummary clickSummary(String messageId) {
        return clicks.summarize(List.of(messageId)).getOrDefault(messageId, ClickSummary.NOT_CLICKED);
    }

    public ClickDetailsResponse clickDetails(String requestedId) {
        String messageId = normalizeId(requestedId);
        return new ClickDetailsResponse(messageId, clickSummary(messageId),
                clicks.findByMessageId(messageId, properties.getApi().getMaxEventsPageSize()));
    }

    public BillingDetailsResponse billingDetails(String requestedId) {
        String messageId = normalizeId(requestedId);
        List<BillingEventView> list = billing.findByMessageId(messageId, properties.getApi().getMaxEventsPageSize())
                .stream().map(mapper::toBillingView).toList();
        return new BillingDetailsResponse(messageId, billingSummary(messageId), list);
    }

    public List<BillingEventView> recentBillingByProcessingStatus(String status, int limit) {
        int capped = Math.max(1, Math.min(limit, properties.getApi().getMaxEventsPageSize()));
        return billing.findByProcessingStatus(status, capped).stream().map(mapper::toBillingView).toList();
    }

    private DlrStatusResponse notReceived(String messageId, boolean includeEvents) {
        List<DlrEvent> all = events.findByMessageId(messageId, properties.getApi().getMaxEventsPageSize());
        List<DlrEvent> rejected = all.stream().filter(e -> e.getProcessingStatus() == ProcessingStatus.REJECTED).toList();
        DlrStatusResponse r = rejected.isEmpty()
                ? DlrStatusResponse.notReceived(messageId)
                : DlrStatusResponse.notReceived(messageId, rejected.size(), rejected.get(rejected.size() - 1).getRejectionReason());
        return includeEvents && !all.isEmpty() ? r.withEvents(all.stream().map(mapper::toEventView).toList()) : r;
    }

    public List<DlrEventView> eventViews(String requestedId) {
        return events.findByMessageId(normalizeId(requestedId), properties.getApi().getMaxEventsPageSize())
                .stream().map(mapper::toEventView).toList();
    }

    public List<DlrEventView> recentByProcessingStatus(ProcessingStatus status, int limit) {
        int capped = Math.max(1, Math.min(limit, properties.getApi().getMaxEventsPageSize()));
        return events.findByProcessingStatus(status, capped).stream().map(mapper::toEventView).toList();
    }

    public List<DlrStatusResponse> byCorrelationId(String correlationId) {
        return statuses.findByCorrelationId(correlationId, properties.getApi().getMaxEventsPageSize())
                .stream().map(mapper::toStatusResponse).toList();
    }

    public List<DlrStatusResponse> byExternalMessageId(String externalMessageId) {
        return statuses.findByExternalMessageId(externalMessageId, properties.getApi().getMaxEventsPageSize())
                .stream().map(mapper::toStatusResponse).toList();
    }

    public DlrVerificationResponse verify(DlrVerificationRequest request) {
        List<String> ids = request.messageIds();
        if (ids.size() > properties.getApi().getMaxVerifyIds()) {
            throw new IllegalArgumentException("message_ids exceeds the maximum of " + properties.getApi().getMaxVerifyIds());
        }
        if (ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("message_ids must not contain blank values");
        }
        NormalizedStatus expected = null;
        if (request.expectedStatus() != null && !request.expectedStatus().isBlank()) {
            expected = NormalizedStatus.parse(request.expectedStatus());
            if (expected == NormalizedStatus.UNKNOWN && !"UNKNOWN".equalsIgnoreCase(request.expectedStatus().trim())) {
                throw new IllegalArgumentException("expected_status must be one of " + List.of(NormalizedStatus.values()));
            }
        }

        List<String> unique = new ArrayList<>(new LinkedHashSet<>(ids.stream().map(this::normalizeId).toList()));
        Map<String, DlrMessageStatus> found = statuses.findByMessageIds(unique).stream()
                .collect(Collectors.toMap(DlrMessageStatus::getMessageId, Function.identity()));
        Map<String, BillingSummary> billed = billing.summarize(unique, billingService.debitTypes(), billingService.creditTypes());
        Map<String, ClickSummary> clickMap = clicks.summarize(unique);
        boolean requireBilling = request.billingRequired();
        boolean requireClick = request.clickRequired();
        boolean matching = expected != null || requireBilling || requireClick;

        int received = 0, delivered = 0, failed = 0, expired = 0, rejected = 0, sent = 0, unknown = 0, missing = 0, matched = 0;
        int billedCount = 0, clickedCount = 0;
        List<DlrVerificationResponse.Result> results = new ArrayList<>(ids.size());
        for (String id : ids) {
            DlrMessageStatus s = found.get(normalizeId(id));
            BillingSummary b = billed.get(normalizeId(id));
            ClickSummary c = clickMap.get(normalizeId(id));
            if (b != null) {
                billedCount++;
            }
            if (c != null) {
                clickedCount++;
            }
            if (s == null) {
                missing++;
                results.add(new DlrVerificationResponse.Result(id, false, "PENDING", null, null, null, null,
                        b != null, b == null ? null : b.units(), b == null ? null : b.totalAmount(),
                        b == null ? null : b.currency(), c != null, c == null ? null : c.clicks(),
                        matching ? Boolean.FALSE : null));
                continue;
            }
            received++;
            NormalizedStatus st = NormalizedStatus.parse(s.getNormalizedStatus());
            switch (st) {
                case DELIVERED -> delivered++;
                case FAILED -> failed++;
                case EXPIRED -> expired++;
                case REJECTED -> rejected++;
                case SENT -> sent++;
                case UNKNOWN -> unknown++;
            }
            Boolean isMatch = matching ? (expected == null || st == expected) && (!requireBilling || b != null)
                    && (!requireClick || c != null) : null;
            if (Boolean.TRUE.equals(isMatch)) {
                matched++;
            }
            results.add(new DlrVerificationResponse.Result(id, true, st.name(), s.getProviderStatus(),
                    s.getStatusCode(), s.getSource(), s.getCorrelationId(), b != null,
                    b == null ? null : b.units(), b == null ? null : b.totalAmount(), b == null ? null : b.currency(),
                    c != null, c == null ? null : c.clicks(), isMatch));
        }
        return new DlrVerificationResponse(ids.size(), received, delivered, failed, expired, rejected, sent, unknown,
                missing, billedCount, ids.size() - billedCount, clickedCount, ids.size() - clickedCount,
                expected == null ? null : expected.name(), requireBilling ? Boolean.TRUE : null,
                requireClick ? Boolean.TRUE : null, matching ? matched : null,
                matching ? matched == ids.size() : null, results);
    }
}
