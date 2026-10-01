package com.smsframework.dlr.billing;

import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.service.DlrMetrics;
import com.smsframework.dlr.util.MobileMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists the events of one billing callback in a single transaction.
 *
 * Idempotency: dedup key = SHA-256(source, billing message_id (incl. part suffix), transaction_type, units,
 * currency, total_amount). The same billing event delivered twice (or to two instances) is stored once as
 * APPLIED and afterwards as DUPLICATE. A debit and a later refund for the same message are different events.
 */
@Service
public class BillingProcessingService {

    private static final Logger log = LoggerFactory.getLogger(BillingProcessingService.class);

    private final DlrBillingRepository repository;
    private final TransactionTemplate tx;
    private final DlrMetrics metrics;
    private final DlrProperties properties;

    public BillingProcessingService(DlrBillingRepository repository, TransactionTemplate tx, DlrMetrics metrics,
                                    DlrProperties properties) {
        this.repository = repository;
        this.tx = tx;
        this.metrics = metrics;
        this.properties = properties;
    }

    /** Above this many events per callback, per-event lines are logged at DEBUG (a summary line is always logged). */
    private static final int PER_EVENT_LOG_LIMIT = 20;

    public BillingResult process(String source, List<NormalizedBillingEvent> events, String rawPayload, String instance) {
        UUID batchId = UUID.randomUUID();
        List<BillingResult.EventOutcome> outcomes = tx.execute(status -> {
            List<BillingResult.EventOutcome> list = new ArrayList<>(events.size());
            // The complete callback is stored once (on the first event); every row keeps its own raw_event.
            // Storing it on every row would write N x the callback size for a bulk campaign.
            for (int i = 0; i < events.size(); i++) {
                list.add(persist(events.get(i), source, batchId, i == 0 ? rawPayload : null, instance));
            }
            return list;
        });

        int applied = 0, duplicates = 0, rejected = 0;
        for (int i = 0; i < outcomes.size(); i++) {
            BillingResult.EventOutcome o = outcomes.get(i);
            NormalizedBillingEvent e = events.get(i);
            metrics.billingEvent(source, o.processingStatus(), e.valid() ? e.units() : null,
                    e.valid() && isDebit(e.transactionType()));
            switch (o.processingStatus()) {
                case "APPLIED" -> applied++;
                case "DUPLICATE" -> duplicates++;
                default -> rejected++;
            }
            if (e.valid() && events.size() > PER_EVENT_LOG_LIMIT) {
                log.debug("Billing DLR received source={} billing_message_id={} processing_status={} event_id={}",
                        source, e.billingMessageId(), o.processingStatus(), o.eventId());
            } else if (e.valid()) {
                log.info("Billing DLR received source={} message_id={} billing_message_id={} transaction_type={} units={} "
                                + "total_amount={} currency={} processing_status={} event_id={}",
                        source, MobileMasker.abbreviate(e.messageId(), 12), e.billingMessageId(), e.transactionType(),
                        e.units(), e.totalAmount(), e.currency(), o.processingStatus(), o.eventId());
            } else {
                log.warn("Billing DLR event rejected source={} message_id={} index={} reason=\"{}\" event_id={}",
                        source, e.messageId(), e.batchIndex(), e.rejectionReason(), o.eventId());
            }
        }
        log.info("Billing callback processed source={} batch_id={} events={} applied={} duplicates={} rejected={}",
                source, batchId, events.size(), applied, duplicates, rejected);
        String overall = applied == events.size() ? "APPLIED"
                : duplicates == events.size() ? "DUPLICATE"
                : rejected == events.size() ? "REJECTED"
                : "PARTIAL";
        return new BillingResult(properties.getBilling().getEventType(), source, batchId, overall, events.size(),
                applied, duplicates, rejected, outcomes);
    }

    private BillingResult.EventOutcome persist(NormalizedBillingEvent e, String source, UUID batchId, String raw,
                                                String instance) {
        DlrBillingEvent row = DlrBillingEvent.from(e, source, batchId, raw, instance);
        if (!e.valid()) {
            long id = repository.insert(row);
            return new BillingResult.EventOutcome(e.batchIndex(), id, e.messageId(), e.billingMessageId(), "REJECTED",
                    e.rejectionReason(), null);
        }
        String key = dedupKey(source, e);
        row.setDedupKey(key);
        Optional<Long> inserted = repository.insertIfAbsent(row);
        if (inserted.isPresent()) {
            return new BillingResult.EventOutcome(e.batchIndex(), inserted.get(), e.messageId(), e.billingMessageId(),
                    "APPLIED", null, null);
        }
        Long primary = repository.findPrimaryIdByDedupKey(key).orElse(null);
        String note = "duplicate of billing event " + primary;
        Long id = null;
        if (properties.getIdempotency().isStoreDuplicates()) {
            row.setProcessingStatus("DUPLICATE");
            row.setDuplicateOf(primary);
            row.setProcessingNote(note);
            id = repository.insert(row);
        }
        return new BillingResult.EventOutcome(e.batchIndex(), id, e.messageId(), e.billingMessageId(), "DUPLICATE",
                null, note);
    }

    boolean isDebit(String transactionType) {
        return transactionType != null && properties.getBilling().getDebitTypes().stream()
                .anyMatch(t -> t.equalsIgnoreCase(transactionType));
    }

    public List<String> debitTypes() {
        return properties.getBilling().getDebitTypes().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
    }

    public List<String> creditTypes() {
        return properties.getBilling().getCreditTypes().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
    }

    static String dedupKey(String source, NormalizedBillingEvent e) {
        StringBuilder sb = new StringBuilder();
        append(sb, "source", source == null ? null : source.toUpperCase(Locale.ROOT));
        append(sb, "billing_message_id", e.billingMessageId());
        append(sb, "transaction_type", e.transactionType());
        append(sb, "units", e.units());
        append(sb, "currency", e.currency());
        append(sb, "total_amount", normalize(e.totalAmount()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void append(StringBuilder sb, String field, Object value) {
        String s = value == null ? "<null>" : value.toString();
        sb.append(field).append('=').append(s.length()).append(':').append(s).append('|');
    }

    /** 1, 1.0 and 1.000000 must produce the same key. */
    private static String normalize(BigDecimal d) {
        return d == null ? null : d.stripTrailingZeros().toPlainString();
    }
}
