package com.smsframework.dlr.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsframework.dlr.adapter.DlrAdapterRegistry;
import com.smsframework.dlr.domain.ProcessingStatus;
import com.smsframework.dlr.dto.ReprocessResponse;
import com.smsframework.dlr.entity.DlrEvent;
import com.smsframework.dlr.repository.DlrEventRepository;
import com.smsframework.dlr.service.DlrProcessingService.ProcessingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Re-runs stored REJECTED callbacks through the normal pipeline, e.g. after a new payload format or
 * provider adapter has been deployed. The raw payload stored in dlr_events is used exactly as received.
 *
 * The original row stays REJECTED (audit trail) and gets processing_note "reprocessed -> ...".
 * Reprocessing is idempotent: running it twice produces DUPLICATE events, never a second APPLIED one.
 */
@Service
public class DlrReprocessService {

    private static final Logger log = LoggerFactory.getLogger(DlrReprocessService.class);
    private static final List<String> NOT_REPROCESSABLE_MARKERS = List.of("_unparseable_body", "_payload_too_large",
            "_empty_body");

    private final DlrEventRepository events;
    private final DlrProcessingService processingService;
    private final DlrAdapterRegistry adapters;
    private final SourceResolver sourceResolver;
    private final ObjectMapper objectMapper;

    public DlrReprocessService(DlrEventRepository events, DlrProcessingService processingService,
                               DlrAdapterRegistry adapters, SourceResolver sourceResolver, ObjectMapper objectMapper) {
        this.events = events;
        this.processingService = processingService;
        this.adapters = adapters;
        this.sourceResolver = sourceResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * @param sourceOverride source to use instead of the stored one (e.g. DEFAULT_SMS); null = stored source if
     *                       known, otherwise payload structure detection
     */
    public ReprocessResponse.Item reprocess(long eventId, String sourceOverride) {
        DlrEvent original = events.findById(eventId)
                .orElseThrow(() -> new NoSuchElementException("dlr_events row " + eventId + " not found"));
        if (original.getProcessingStatus() != ProcessingStatus.REJECTED) {
            throw new IllegalArgumentException("event " + eventId + " is " + original.getProcessingStatus()
                    + "; only REJECTED events can be reprocessed");
        }
        return doReprocess(original, sourceOverride);
    }

    public ReprocessResponse reprocessRejected(int limit, String sourceOverride) {
        List<ReprocessResponse.Item> items = new ArrayList<>();
        for (DlrEvent e : events.findRejectedNotReprocessed(Math.max(1, Math.min(limit, 1000)))) {
            items.add(doReprocess(e, sourceOverride));
        }
        return ReprocessResponse.of(items);
    }

    private ReprocessResponse.Item doReprocess(DlrEvent original, String sourceOverride) {
        String raw = original.getRawPayload();
        if (!isReprocessable(raw)) {
            events.updateNote(original.getId(), "reprocess skipped: raw payload not reprocessable");
            return new ReprocessResponse.Item(original.getId(), null, original.getMessageId(), "SKIPPED", null,
                    "raw payload was not valid JSON (or was too large/empty) when received; nothing to reprocess");
        }
        String source = sourceOverride != null && !sourceOverride.isBlank()
                ? sourceResolver.canonicalise(sourceOverride)
                : (adapters.isKnownSource(original.getSource()) ? original.getSource() : null);

        ProcessingResult r = processingService.process(source, raw);

        String outcome = r.isBilling() ? r.billing().processingStatus() : r.processingStatus().name();
        events.updateNote(original.getId(), "reprocessed -> " + outcome
                + (r.eventId() != null ? " (event " + r.eventId() + ")" : ""));
        if (r.processingStatus() == ProcessingStatus.REJECTED && r.eventId() != null) {
            // The retry itself was rejected again: mark it so bulk reprocessing does not pick it up in a loop.
            events.updateNote(r.eventId(), "reprocess of event " + original.getId() + " rejected again");
        }
        log.info("DLR reprocessed original_event_id={} new_event_id={} source={} message_id={} processing_status={}",
                original.getId(), r.eventId(), r.source(), r.messageId(), outcome);
        return new ReprocessResponse.Item(original.getId(), r.eventId(), r.messageId(), outcome,
                r.normalizedStatus() == null ? null : r.normalizedStatus().name(), r.rejectionReason());
    }

    private boolean isReprocessable(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node != null && !(node.isObject() && NOT_REPROCESSABLE_MARKERS.stream().anyMatch(node::has));
        } catch (Exception e) {
            return false;
        }
    }
}
