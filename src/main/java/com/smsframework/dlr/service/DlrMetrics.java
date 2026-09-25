package com.smsframework.dlr.service;

import com.smsframework.dlr.adapter.DlrAdapterRegistry;
import com.smsframework.dlr.domain.NormalizedStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Micrometer metrics. With the Prometheus registry these are exposed at /actuator/prometheus as:
 * dlr_received_total, dlr_delivered_total, dlr_failed_total, dlr_expired_total, dlr_rejected_total,
 * dlr_duplicate_total, dlr_unknown_total, dlr_processing_error_total, dlr_sent_total, dlr_ignored_total,
 * dlr_auth_failed_total and dlr_processing_latency_seconds (histogram). All tagged with "source".
 */
@Component
public class DlrMetrics {

    private final MeterRegistry registry;

    public DlrMetrics(MeterRegistry registry, DlrAdapterRegistry adapters) {
        this.registry = registry;
        // Pre-register so dashboards/alerts see zeros instead of missing series.
        for (String source : adapters.sources()) {
            received(source, 0);
            for (String name : new String[]{"delivered", "failed", "expired", "sent", "unknown", "rejected",
                    "duplicate", "processing.error"}) {
                counter(name, source).increment(0);
            }
        }
        counter("rejected", "UNKNOWN").increment(0);
    }

    public void received(String source) {
        received(source, 1);
    }

    private void received(String source, double amount) {
        counter("received", source).increment(amount);
    }

    /** Counts a valid (non-duplicate) DLR by normalized status: dlr_delivered_total, dlr_failed_total, ... */
    public void status(String source, NormalizedStatus status) {
        counter(status.name().toLowerCase(Locale.ROOT), source).increment();
    }

    public void rejected(String source) {
        counter("rejected", source).increment();
    }

    public void duplicate(String source) {
        counter("duplicate", source).increment();
    }

    public void ignored(String source, String reason) {
        Counter.builder("dlr.ignored").tag("source", source).tag("reason", reason)
                .description("Valid DLRs not applied by the state machine").register(registry).increment();
    }

    public void processingError(String source) {
        counter("processing.error", source).increment();
    }

    public void authFailed(String mechanism) {
        Counter.builder("dlr.auth.failed").tag("mechanism", mechanism)
                .description("Callbacks refused by authentication").register(registry).increment();
    }

    public Timer latencyTimer(String source) {
        return Timer.builder("dlr.processing.latency")
                .description("Time to validate, normalize and persist one DLR callback")
                .tag("source", source)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(registry);
    }

    public MeterRegistry registry() {
        return registry;
    }

    private Counter counter(String name, String source) {
        return Counter.builder("dlr." + name).tag("source", source).register(registry);
    }
}
