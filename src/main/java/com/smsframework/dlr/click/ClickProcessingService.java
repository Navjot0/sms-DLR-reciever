package com.smsframework.dlr.click;

import com.smsframework.dlr.service.DlrMetrics;
import com.smsframework.dlr.util.MobileMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Persists short-link click events.
 *
 * Every click is its own event (visited_count 2, then 3, ... are all stored). Idempotency key =
 * SHA-256(source, message id as received, url_key, clicked_at, visited_count, ip_address): the SAME callback
 * delivered twice (or to two instances) is stored once as APPLIED and then as DUPLICATE.
 */
@Service
public class ClickProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ClickProcessingService.class);

    private final DlrClickRepository repository;
    private final TransactionTemplate tx;
    private final DlrMetrics metrics;

    public ClickProcessingService(DlrClickRepository repository, TransactionTemplate tx, DlrMetrics metrics) {
        this.repository = repository;
        this.tx = tx;
        this.metrics = metrics;
    }

    public ClickResult process(String source, NormalizedClickEvent e, String raw, String instance) {
        String key = dedupKey(source, e);
        ClickResult result = tx.execute(status -> {
            Optional<Long> id = repository.insertIfAbsent(e, source, raw, key, instance);
            if (id.isPresent()) {
                return new ClickResult(e.eventType(), source, id.get(), e.messageId(), e.providerMessageId(),
                        e.urlKey(), e.visitedCount(), "APPLIED", null);
            }
            Long primary = repository.findPrimaryIdByDedupKey(key).orElse(null);
            long dupId = repository.insertDuplicate(e, source, raw, key, instance, primary);
            return new ClickResult(e.eventType(), source, dupId, e.messageId(), e.providerMessageId(), e.urlKey(),
                    e.visitedCount(), "DUPLICATE", "duplicate of click event " + primary);
        });
        metrics.clickEvent(source, result.processingStatus());
        log.info("Click event received source={} message_id={} url_key={} visited_count={} contact={} device={} "
                        + "processing_status={} event_id={}",
                source, MobileMasker.abbreviate(e.messageId(), 12), e.urlKey(), e.visitedCount(),
                MobileMasker.mask(e.contact()), e.deviceType(), result.processingStatus(), result.eventId());
        return result;
    }

    static String dedupKey(String source, NormalizedClickEvent e) {
        StringBuilder sb = new StringBuilder();
        for (Object v : new Object[]{source, e.providerMessageId(), e.urlKey(), e.clickedAt(), e.visitedCount(),
                e.ipAddress()}) {
            String s = v == null ? "<null>" : v.toString();
            sb.append(s.length()).append(':').append(s).append('|');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
