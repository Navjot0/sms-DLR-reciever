package com.smsframework.dlr.service;

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

    public DlrQueryService(DlrMessageStatusRepository statuses, DlrEventRepository events, DlrMapper mapper,
                           DlrProperties properties) {
        this.statuses = statuses;
        this.events = events;
        this.mapper = mapper;
        this.properties = properties;
    }

    public DlrStatusResponse getStatus(String messageId, boolean includeEvents) {
        return statuses.findByMessageId(messageId)
                .map(s -> {
                    DlrStatusResponse r = mapper.toStatusResponse(s);
                    return includeEvents ? r.withEvents(eventViews(messageId)) : r;
                })
                .orElseGet(() -> notReceived(messageId, includeEvents));
    }

    private DlrStatusResponse notReceived(String messageId, boolean includeEvents) {
        List<DlrEvent> all = events.findByMessageId(messageId, properties.getApi().getMaxEventsPageSize());
        List<DlrEvent> rejected = all.stream().filter(e -> e.getProcessingStatus() == ProcessingStatus.REJECTED).toList();
        DlrStatusResponse r = rejected.isEmpty()
                ? DlrStatusResponse.notReceived(messageId)
                : DlrStatusResponse.notReceived(messageId, rejected.size(), rejected.get(rejected.size() - 1).getRejectionReason());
        return includeEvents && !all.isEmpty() ? r.withEvents(all.stream().map(mapper::toEventView).toList()) : r;
    }

    public List<DlrEventView> eventViews(String messageId) {
        return events.findByMessageId(messageId, properties.getApi().getMaxEventsPageSize())
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

        List<String> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        Map<String, DlrMessageStatus> found = statuses.findByMessageIds(unique).stream()
                .collect(Collectors.toMap(DlrMessageStatus::getMessageId, Function.identity()));

        int received = 0, delivered = 0, failed = 0, expired = 0, rejected = 0, sent = 0, unknown = 0, missing = 0, matched = 0;
        List<DlrVerificationResponse.Result> results = new ArrayList<>(ids.size());
        for (String id : ids) {
            DlrMessageStatus s = found.get(id);
            if (s == null) {
                missing++;
                results.add(new DlrVerificationResponse.Result(id, false, "PENDING", null, null, null, null,
                        expected == null ? null : Boolean.FALSE));
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
            Boolean isMatch = expected == null ? null : st == expected;
            if (Boolean.TRUE.equals(isMatch)) {
                matched++;
            }
            results.add(new DlrVerificationResponse.Result(id, true, st.name(), s.getProviderStatus(),
                    s.getStatusCode(), s.getSource(), s.getCorrelationId(), isMatch));
        }
        return new DlrVerificationResponse(ids.size(), received, delivered, failed, expired, rejected, sent, unknown,
                missing, expected == null ? null : expected.name(), expected == null ? null : matched,
                expected == null ? null : matched == ids.size(), results);
    }
}
