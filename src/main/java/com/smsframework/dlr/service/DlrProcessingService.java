package com.smsframework.dlr.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smsframework.dlr.adapter.DlrAdapterRegistry;
import com.smsframework.dlr.adapter.DlrProviderAdapter;
import com.smsframework.dlr.billing.BillingDlrAdapter;
import com.smsframework.dlr.billing.BillingProcessingService;
import com.smsframework.dlr.billing.BillingResult;
import com.smsframework.dlr.billing.NormalizedBillingEvent;
import com.smsframework.dlr.click.ClickProcessingService;
import com.smsframework.dlr.click.ClickResult;
import com.smsframework.dlr.click.NormalizedClickEvent;
import com.smsframework.dlr.click.ShortLinkClickAdapter;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.domain.NormalizedStatus;
import com.smsframework.dlr.domain.ProcessingStatus;
import com.smsframework.dlr.dto.NormalizedDlr;
import com.smsframework.dlr.entity.DlrEvent;
import com.smsframework.dlr.entity.DlrMessageStatus;
import com.smsframework.dlr.exception.DlrPersistenceException;
import com.smsframework.dlr.exception.DlrValidationException;
import com.smsframework.dlr.mapper.DlrMapper;
import com.smsframework.dlr.email.EmailDlrAdapter;
import com.smsframework.dlr.meta.MetaWebhookResult;
import com.smsframework.dlr.meta.MetaWhatsAppDlrAdapter;
import com.smsframework.dlr.repository.DlrEventRepository;
import com.smsframework.dlr.repository.DlrMessageStatusRepository;
import com.smsframework.dlr.util.MessageIdParts;
import com.smsframework.dlr.util.MobileMasker;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Core, provider-independent DLR pipeline:
 * <pre>
 * raw body -> parse -> resolve adapter -> validate + normalize -> idempotency (DB) -> state machine (DB row lock) -> persist
 * </pre>
 * Every callback ends up in dlr_events (APPLIED / IGNORED / DUPLICATE / REJECTED). Nothing is kept in memory,
 * so any number of instances can run behind a load balancer.
 */
@Service
public class DlrProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DlrProcessingService.class);
    static final String UNKNOWN_SOURCE = "UNKNOWN";
    private static final List<String> MDC_KEYS = List.of("source", "message_id", "correlation_id", "mobile",
            "provider_status", "normalized_status", "processing_status");

    /** Outcome of one callback, returned to the controller (and to the provider as an ack). */
    public record ProcessingResult(Long eventId, String source, String messageId, ProcessingStatus processingStatus,
                                   NormalizedStatus normalizedStatus, String currentStatus, String rejectionReason,
                                   String note, boolean payloadTooLarge, BillingResult billing, ClickResult click,
                                   MetaWebhookResult meta) {

        /** True when this callback was a Meta (WhatsApp) webhook (see {@link #meta()}). */
        public boolean isMeta() {
            return meta != null;
        }

        /** True when this callback was a short-link click event (see {@link #click()}). */
        public boolean isClick() {
            return click != null;
        }

        /** True when this callback was a billing DLR (see {@link #billing()}). */
        public boolean isBilling() {
            return billing != null;
        }
    }

    private final DlrAdapterRegistry adapters;
    private final SourceResolver sourceResolver;
    private final DedupKeyGenerator dedupKeyGenerator;
    private final DlrStateMachine stateMachine;
    private final DlrEventRepository events;
    private final DlrMessageStatusRepository statuses;
    private final DlrMapper mapper;
    private final DlrMetrics metrics;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final DlrProperties properties;
    private final String instanceId;
    private final List<BillingDlrAdapter> billingAdapters;
    private final BillingProcessingService billingService;
    private final ShortLinkClickAdapter clickAdapter;
    private final ClickProcessingService clickService;
    private final MetaWhatsAppDlrAdapter metaAdapter;
    private final EmailDlrAdapter emailAdapter;

    public DlrProcessingService(DlrAdapterRegistry adapters, SourceResolver sourceResolver,
                                DedupKeyGenerator dedupKeyGenerator, DlrStateMachine stateMachine,
                                DlrEventRepository events, DlrMessageStatusRepository statuses, DlrMapper mapper,
                                DlrMetrics metrics, TransactionTemplate tx, ObjectMapper objectMapper,
                                DlrProperties properties, List<BillingDlrAdapter> billingAdapters,
                                BillingProcessingService billingService, ShortLinkClickAdapter clickAdapter,
                                ClickProcessingService clickService, MetaWhatsAppDlrAdapter metaAdapter,
                                EmailDlrAdapter emailAdapter) {
        this.metaAdapter = metaAdapter;
        this.emailAdapter = emailAdapter;
        this.clickAdapter = clickAdapter;
        this.clickService = clickService;
        this.billingAdapters = List.copyOf(billingAdapters);
        this.billingService = billingService;
        this.adapters = adapters;
        this.sourceResolver = sourceResolver;
        this.dedupKeyGenerator = dedupKeyGenerator;
        this.stateMachine = stateMachine;
        this.events = events;
        this.statuses = statuses;
        this.mapper = mapper;
        this.metrics = metrics;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.instanceId = resolveInstanceId(properties.getInstanceId());
    }

    /**
     * @param explicitSource source from header/query parameter (canonicalised) or null
     * @param body           raw request body exactly as received (may be null/empty/malformed)
     */
    /** The callback body being processed on this thread, stored verbatim on every event it produces. */
    private static final ThreadLocal<String> CURRENT_BODY = new ThreadLocal<>();

    public ProcessingResult process(String explicitSource, String body) {
        long start = System.nanoTime();
        CURRENT_BODY.set(body == null || body.getBytes(StandardCharsets.UTF_8).length > properties.getApi().getMaxPayloadBytes()
                ? null : sanitize(body));
        String sourceLabel = explicitSource != null && adapters.isKnownSource(explicitSource) ? explicitSource : UNKNOWN_SOURCE;
        try {
            ProcessingResult result = doProcess(explicitSource, body);
            sourceLabel = result.source();
            return result;
        } catch (DataAccessException e) {
            metrics.processingError(sourceLabel);
            throw new DlrPersistenceException("Failed to persist DLR", e);
        } catch (RuntimeException e) {
            metrics.processingError(sourceLabel);
            throw e;
        } finally {
            metrics.latencyTimer(adapters.isKnownSource(sourceLabel) ? sourceLabel : UNKNOWN_SOURCE)
                    .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
            MDC_KEYS.forEach(MDC::remove);
            CURRENT_BODY.remove();
        }
    }

    private ProcessingResult doProcess(String explicitSource, String body) {
        String hintedSource = explicitSource != null ? explicitSource : UNKNOWN_SOURCE;

        // 1. Size guard ------------------------------------------------------------------------------
        int maxBytes = properties.getApi().getMaxPayloadBytes();
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            ObjectNode wrapped = objectMapper.createObjectNode();
            wrapped.put("_payload_too_large", true);
            wrapped.put("_size_bytes", body.getBytes(StandardCharsets.UTF_8).length);
            wrapped.put("_prefix", sanitize(body.substring(0, Math.min(body.length(), 4096))));
            return reject(hintedSource, null, wrapped.toString(), "payload exceeds " + maxBytes + " bytes", true);
        }

        // 2. Parse JSON ------------------------------------------------------------------------------
        if (body == null || body.isBlank()) {
            return reject(hintedSource, null, "{\"_empty_body\": true}", "request body is empty", false);
        }
        String rawForStorage = sanitize(body);
        JsonNode json;
        try {
            json = objectMapper.readTree(rawForStorage);
            if (json == null || json.isMissingNode()) {
                throw new IllegalArgumentException("no JSON content");
            }
        } catch (Exception e) {
            ObjectNode wrapped = objectMapper.createObjectNode();
            wrapped.put("_unparseable_body", rawForStorage);
            return reject(hintedSource, null, wrapped.toString(), "malformed JSON payload", false);
        }

        // 3. Resolve provider adapter -----------------------------------------------------------------
        if (explicitSource != null && !adapters.isKnownSource(explicitSource)) {
            return reject(explicitSource, null, rawForStorage, "unsupported DLR source: " + explicitSource
                    + " (supported: " + adapters.sources() + ")", false);
        }

        // 3a. Short-link click ({"event":"short_link","data":{...}}) ----------------------------------
        if (clickAdapter.isClickPayload(json)) {
            return processClick(explicitSource, json, rawForStorage);
        }

        // 3a'. Meta (WhatsApp Cloud API) webhook: one callback, N statuses --------------------------------
        if ((explicitSource == null || MetaWhatsAppDlrAdapter.SOURCE.equalsIgnoreCase(explicitSource))
                && metaAdapter.isWebhook(json)) {
            return processMetaWebhook(json);
        }

        // 3a". Email (Amazon SES / SNS, Kenscio): one callback may carry several events ------------------
        if ((explicitSource == null || EmailDlrAdapter.SOURCE.equalsIgnoreCase(explicitSource))
                && emailAdapter.isEmailPayload(json)) {
            List<JsonNode> emailEvents = emailAdapter.events(json);
            if (emailEvents.isEmpty()) {
                String note = emailAdapter.nonEventNote(json);
                log.info("Email callback without events acknowledged: {}", note);
                return metaResult(new MetaWebhookResult(EmailDlrAdapter.SOURCE, 0, 0, 0, 0, 0, note, List.of()));
            }
            return processBatch(emailAdapter, emailEvents, "email: ");
        }

        // 3b. Billing DLR ({"event_type":"billing","events":[...]}) -----------------------------------
        Optional<BillingDlrAdapter> billingAdapter = billingAdapters.stream().filter(b -> b.isBillingPayload(json)).findFirst();
        if (billingAdapter.isPresent()) {
            return processBilling(billingAdapter.get(), explicitSource, json, rawForStorage);
        }

        if (explicitSource == null && !sourceResolver.detectFromPayload()) {
            return reject(UNKNOWN_SOURCE, null, rawForStorage,
                    "DLR source not specified and payload detection is disabled", false);
        }
        Optional<DlrProviderAdapter> adapterOpt = adapters.find(explicitSource, json);
        if (adapterOpt.isEmpty()) {
            // Keep whatever message id we can find so automation can still see the rejection for that message.
            return reject(UNKNOWN_SOURCE, adapters.peekMessageId(json), rawForStorage,
                    "unable to determine DLR source from headers, query parameters or payload structure", false);
        }
        DlrProviderAdapter adapter = adapterOpt.get();

        // 4. Validate + normalize ---------------------------------------------------------------------
        NormalizedDlr dlr;
        try {
            dlr = adapter.normalize(json);
        } catch (DlrValidationException e) {
            String peeked = safePeek(adapter, json);
            return reject(adapter.source(), peeked, rawForStorage, e.getReason(), false);
        }

        // 4a. Multipart ids: "<message_id>:<part>" -> message_id + part_number (correlates with billing DLRs)
        MessageIdParts.Parsed ids = MessageIdParts.parse(dlr.getMessageId(), properties.getMessageIdPartSeparator());
        if (ids.hasPart()) {
            dlr.providerMessageId(ids.original()).partNumber(ids.part()).messageId(ids.messageId());
        }

        // 5. Idempotency + state machine (single DB transaction) --------------------------------------
        metrics.received(dlr.getSource());
        ProcessingResult result = tx.execute(status -> persist(dlr, rawForStorage));
        recordMetricsAndLog(dlr, result);
        return result;
    }

    private ProcessingResult persist(NormalizedDlr dlr, String raw) {
        String dedupKey = dedupKeyGenerator.generate(dlr);
        DlrEvent event = mapper.toEvent(dlr, raw, dedupKey, instanceId);
        event.setRawBody(CURRENT_BODY.get());

        Optional<Long> inserted = events.insertIfAbsent(event);
        if (inserted.isEmpty()) {
            // Same callback already recorded (possibly just now by another instance).
            Long primaryId = events.findPrimaryIdByDedupKey(dedupKey).orElse(null);
            Long duplicateEventId = null;
            if (properties.getIdempotency().isStoreDuplicates()) {
                event.setProcessingStatus(ProcessingStatus.DUPLICATE);
                event.setDuplicateOf(primaryId);
                event.setProcessingNote("duplicate of event " + primaryId);
                duplicateEventId = events.insert(event);
            }
            statuses.incrementDuplicate(dlr.getMessageId());
            String current = statuses.findByMessageId(dlr.getMessageId()).map(DlrMessageStatus::getNormalizedStatus).orElse(null);
            return result(duplicateEventId, dlr, ProcessingStatus.DUPLICATE, current, "duplicate of event " + primaryId);
        }
        long eventId = inserted.get();

        Optional<DlrMessageStatus> current = statuses.findForUpdate(dlr.getMessageId());
        if (current.isEmpty()) {
            if (statuses.insertIfAbsent(dlr, eventId)) {
                String note = "first DLR for message";
                events.updateProcessing(eventId, ProcessingStatus.APPLIED, note);
                return result(eventId, dlr, ProcessingStatus.APPLIED, dlr.getNormalizedStatus().name(), note);
            }
            // Lost the race to create the row: it exists now, lock it and evaluate normally.
            current = statuses.findForUpdate(dlr.getMessageId());
        }
        DlrMessageStatus state = current.orElseThrow(() -> new IllegalStateException(
                "dlr_message_status row missing for " + dlr.getMessageId()));

        NormalizedStatus currentStatus = NormalizedStatus.parse(state.getNormalizedStatus());
        DlrStateMachine.Decision decision = stateMachine.evaluate(currentStatus, dlr.getNormalizedStatus());
        switch (decision.outcome()) {
            case APPLY -> {
                statuses.applyTransition(dlr, eventId);
                events.updateProcessing(eventId, ProcessingStatus.APPLIED, decision.reason());
                return result(eventId, dlr, ProcessingStatus.APPLIED, dlr.getNormalizedStatus().name(), decision.reason());
            }
            case SAME_STATE, REJECT_TRANSITION -> {
                statuses.recordIgnored(dlr);
                events.updateProcessing(eventId, ProcessingStatus.IGNORED, decision.reason());
                return result(eventId, dlr, ProcessingStatus.IGNORED, currentStatus.name(), decision.reason());
            }
            default -> throw new IllegalStateException("Unhandled outcome " + decision.outcome());
        }
    }

    /** Each status of a Meta webhook is validated, de-duplicated and run through the state machine on its own. */
    private ProcessingResult processMetaWebhook(JsonNode webhook) {
        String source = MetaWhatsAppDlrAdapter.SOURCE;
        List<ObjectNode> envelopes = metaAdapter.statuses(webhook);
        if (envelopes.isEmpty()) {
            // inbound messages, template/account updates ...: not a delivery report, nothing to store
            String note = "no statuses in webhook (" + metaAdapter.nonStatusContent(webhook) + ")";
            log.info("Meta webhook without statuses acknowledged: {}", note);
            return metaResult(new MetaWebhookResult(source, 0, 0, 0, 0, 0, note, List.of()));
        }
        return processBatch(metaAdapter, envelopes, "meta: ");
    }

    /**
     * Several DLRs in one callback (Meta statuses, email events): each is validated, de-duplicated and run
     * through the state machine on its own, and stored with its own raw JSON. Answered with 200 as a whole.
     */
    private ProcessingResult processBatch(DlrProviderAdapter adapter, List<? extends JsonNode> envelopes,
                                          String rejectPrefix) {
        String source = adapter.source();
        int applied = 0, ignored = 0, duplicates = 0, rejected = 0;
        List<MetaWebhookResult.Item> items = new java.util.ArrayList<>(envelopes.size());
        for (JsonNode env : envelopes) {
            String raw = env.toString();
            ProcessingResult r;
            NormalizedDlr dlr = null;
            try {
                dlr = adapter.normalize(env);
            } catch (DlrValidationException e) {
                r = reject(source, safePeek(adapter, env), raw, rejectPrefix + e.getReason(), false);
                rejected++;
                items.add(new MetaWebhookResult.Item(r.eventId(), r.messageId(), null, null,
                        ProcessingStatus.REJECTED.name(), null, e.getReason()));
                continue;
            }
            NormalizedDlr d = dlr;
            metrics.received(source);
            r = tx.execute(status -> persist(d, raw));
            recordMetricsAndLog(d, r);
            switch (r.processingStatus()) {
                case APPLIED -> applied++;
                case IGNORED -> ignored++;
                case DUPLICATE -> duplicates++;
                default -> rejected++;
            }
            items.add(new MetaWebhookResult.Item(r.eventId(), d.getMessageId(), d.getProviderStatus(),
                    d.getNormalizedStatus().name(), r.processingStatus().name(), r.currentStatus(), r.note()));
            MDC_KEYS.forEach(MDC::remove);
        }
        return metaResult(new MetaWebhookResult(source, envelopes.size(), applied, ignored, duplicates, rejected,
                null, items));
    }

    private static ProcessingResult metaResult(MetaWebhookResult m) {
        ProcessingStatus overall = m.statuses() == 0 ? ProcessingStatus.IGNORED
                : m.rejected() == m.statuses() ? ProcessingStatus.REJECTED
                : m.duplicates() == m.statuses() ? ProcessingStatus.DUPLICATE : ProcessingStatus.APPLIED;
        return new ProcessingResult(null, m.source(), null, overall, null, null, null, m.note(), false, null, null, m);
    }

    private ProcessingResult processClick(String explicitSource, JsonNode json, String raw) {
        String source = explicitSource != null ? explicitSource : properties.getClicks().getDefaultSource();
        if (!clickAdapter.acceptsSource(source)) {
            return reject(source, clickAdapter.peekMessageId(json), raw, "click events are not accepted from source "
                    + source + " (allowed: " + properties.getClicks().getSources() + ")", false);
        }
        NormalizedClickEvent click;
        try {
            click = clickAdapter.normalize(json);
        } catch (DlrValidationException e) {
            metrics.clickRejected(source);
            return reject(source, clickAdapter.peekMessageId(json), raw, "short_link: " + e.getReason(), false);
        }
        ClickResult cr = clickService.process(source, click, raw, instanceId);
        ProcessingStatus ps = "DUPLICATE".equals(cr.processingStatus()) ? ProcessingStatus.DUPLICATE : ProcessingStatus.APPLIED;
        return new ProcessingResult(cr.eventId(), source, click.messageId(), ps, null, null, null, cr.note(), false,
                null, cr, null);
    }

    private ProcessingResult processBilling(BillingDlrAdapter adapter, String explicitSource, JsonNode json, String raw) {
        String source = explicitSource != null ? explicitSource : properties.getBilling().getDefaultSource();
        if (!adapter.acceptsSource(source)) {
            return reject(source, null, raw, "billing DLRs are not accepted from source " + source
                    + " (allowed: " + properties.getBilling().getSources() + ")", false);
        }
        List<NormalizedBillingEvent> billingEvents;
        try {
            billingEvents = adapter.normalize(json);
        } catch (DlrValidationException e) {
            return reject(source, null, raw, "billing: " + e.getReason(), false);
        }
        BillingResult br = billingService.process(source, billingEvents, raw, instanceId);
        ProcessingStatus overall = switch (br.processingStatus()) {
            case "REJECTED" -> ProcessingStatus.REJECTED;
            case "DUPLICATE" -> ProcessingStatus.DUPLICATE;
            default -> ProcessingStatus.APPLIED;
        };
        return new ProcessingResult(null, source, null, overall, null, null, null, null, false, br, null, null);
    }

    private ProcessingResult reject(String source, String messageId, String raw, String reason, boolean tooLarge) {
        String src = source == null ? UNKNOWN_SOURCE : source;
        messageId = MessageIdParts.base(messageId, properties.getMessageIdPartSeparator());
        DlrEvent event = mapper.rejectedEvent(src, messageId, raw, reason, instanceId);
        event.setRawBody(CURRENT_BODY.get());
        long id = events.insert(event);
        String label = adapters.isKnownSource(src) ? src : UNKNOWN_SOURCE;
        metrics.received(label);
        metrics.rejected(label);
        MDC.put("source", src);
        MDC.put("processing_status", ProcessingStatus.REJECTED.name());
        if (messageId != null) {
            MDC.put("message_id", messageId);
        }
        log.warn("DLR rejected source={} message_id={} processing_status=REJECTED event_id={} reason=\"{}\"",
                src, messageId, id, reason);
        return new ProcessingResult(id, event.getSource(), messageId, ProcessingStatus.REJECTED, null, null, reason,
                null, tooLarge, null, null, null);
    }

    private ProcessingResult result(Long eventId, NormalizedDlr dlr, ProcessingStatus ps, String currentStatus, String note) {
        return new ProcessingResult(eventId, dlr.getSource(), dlr.getMessageId(), ps, dlr.getNormalizedStatus(),
                currentStatus, null, note, false, null, null, null);
    }

    private void recordMetricsAndLog(NormalizedDlr dlr, ProcessingResult r) {
        switch (r.processingStatus()) {
            case DUPLICATE -> metrics.duplicate(dlr.getSource());
            case IGNORED -> {
                // Not counted in dlr_<status>_total: those count state changes only, so a repeated
                // DELIVERED never inflates dlr_delivered_total.
                metrics.ignored(dlr.getSource(),
                        r.note() != null && r.note().startsWith("message already") ? "same_state" : "transition_not_allowed");
            }
            default -> metrics.status(dlr.getSource(), dlr.getNormalizedStatus());
        }

        String maskedMobile = MobileMasker.mask(dlr.getMobile());
        MDC.put("source", dlr.getSource());
        MDC.put("message_id", dlr.getMessageId());
        if (dlr.getCorrelationId() != null) {
            MDC.put("correlation_id", dlr.getCorrelationId());
        }
        if (maskedMobile != null) {
            MDC.put("mobile", maskedMobile);
        }
        MDC.put("provider_status", dlr.getProviderStatus());
        MDC.put("normalized_status", dlr.getNormalizedStatus().name());
        MDC.put("processing_status", r.processingStatus().name());
        log.info("DLR received source={} message_id={} correlation_id={} mobile={} provider_status={} "
                        + "normalized_status={} processing_status={} current_status={} event_id={} note=\"{}\"",
                dlr.getSource(), MobileMasker.abbreviate(dlr.getMessageId(), 12),
                MobileMasker.abbreviate(dlr.getCorrelationId(), 16), maskedMobile, dlr.getProviderStatus(),
                dlr.getNormalizedStatus(), r.processingStatus(), r.currentStatus(), r.eventId(), r.note());
    }

    private static String safePeek(DlrProviderAdapter adapter, JsonNode json) {
        try {
            return adapter.peekMessageId(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** PostgreSQL text/jsonb cannot hold NUL characters; replace them so the raw payload is always storable. */
    static String sanitize(String body) {
        return body.replace("\u0000", "�").replace("\\u0000", "\\uFFFD");
    }

    private static String resolveInstanceId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String host = System.getenv("HOSTNAME");
        if (host != null && !host.isBlank()) {
            return host;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public String instanceId() {
        return instanceId;
    }
}
