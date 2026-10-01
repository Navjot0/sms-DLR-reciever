package com.smsframework.dlr.unit;

import com.smsframework.dlr.adapter.DlrAdapterRegistry;
import com.smsframework.dlr.billing.BillingProcessingService;
import com.smsframework.dlr.billing.DefaultBillingDlrAdapter;
import com.smsframework.dlr.billing.DlrBillingEvent;
import com.smsframework.dlr.billing.DlrBillingRepository;
import com.smsframework.dlr.click.ClickProcessingService;
import com.smsframework.dlr.click.DlrClickRepository;
import com.smsframework.dlr.click.ShortLinkClickAdapter;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.domain.ProcessingStatus;
import com.smsframework.dlr.entity.DlrEvent;
import com.smsframework.dlr.entity.DlrMessageStatus;
import com.smsframework.dlr.mapper.DlrMapper;
import com.smsframework.dlr.repository.DlrEventRepository;
import com.smsframework.dlr.repository.DlrMessageStatusRepository;
import com.smsframework.dlr.service.DedupKeyGenerator;
import com.smsframework.dlr.service.DlrMetrics;
import com.smsframework.dlr.service.DlrProcessingService;
import com.smsframework.dlr.service.DlrProcessingService.ProcessingResult;
import com.smsframework.dlr.service.DlrStateMachine;
import com.smsframework.dlr.service.SourceResolver;
import com.smsframework.dlr.support.TestPayloads;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pipeline logic with the database mocked out: validation/rejection, idempotency and state transitions.
 * The same scenarios are exercised against real PostgreSQL in the integration tests.
 */
class DlrProcessingServiceTest {

    private DlrEventRepository events;
    private DlrMessageStatusRepository statuses;
    private SimpleMeterRegistry meters;
    private DlrProcessingService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        DlrProperties props = new DlrProperties();
        events = mock(DlrEventRepository.class);
        statuses = mock(DlrMessageStatusRepository.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        when(events.insert(any())).thenReturn(99L);

        DlrAdapterRegistry registry = new DlrAdapterRegistry(List.of(
                AdapterTestSupport.defaultAdapter(), AdapterTestSupport.webEngageAdapter()));
        meters = new SimpleMeterRegistry();
        DlrMetrics metrics = new DlrMetrics(meters, registry);
        billingRepository = mock(DlrBillingRepository.class);
        when(billingRepository.insert(any())).thenReturn(500L);
        BillingProcessingService billing = new BillingProcessingService(billingRepository, tx, metrics, props);
        service = new DlrProcessingService(registry, new SourceResolver(props), new DedupKeyGenerator(props),
                new DlrStateMachine(props), events, statuses, new DlrMapper(AdapterTestSupport.MAPPER, props),
                metrics, tx, AdapterTestSupport.MAPPER, props,
                List.of(new DefaultBillingDlrAdapter(AdapterTestSupport.MAPPER, AdapterTestSupport.VALIDATOR, props)),
                billing, new ShortLinkClickAdapter(AdapterTestSupport.MAPPER, AdapterTestSupport.VALIDATOR, props),
                new ClickProcessingService(clickRepository = mock(DlrClickRepository.class), tx, metrics),
                AdapterTestSupport.metaAdapter());
    }

    private DlrBillingRepository billingRepository;
    private DlrClickRepository clickRepository;

    @Test
    void shortLinkClickIsRoutedToClicksAndCorrelatedWithoutPartSuffix() {
        when(clickRepository.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(Optional.of(11L));

        ProcessingResult r = service.process(null, TestPayloads.CLICK_EXAMPLE);

        assertThat(r.isClick()).isTrue();
        assertThat(r.click().processingStatus()).isEqualTo("APPLIED");
        assertThat(r.click().messageId()).isEqualTo("68c3d2ef-9ced-46b8-aa1c-13be73ad9321");
        assertThat(r.click().providerMessageId()).isEqualTo("68c3d2ef-9ced-46b8-aa1c-13be73ad9321:1");
        assertThat(r.click().visitedCount()).isEqualTo(2);
        verify(events, never()).insertIfAbsent(any());
        assertThat(count("dlr.click.applied")).isEqualTo(1);
    }

    @Test
    void clickWithoutMessageIdIsRejected() {
        ProcessingResult r = service.process(null, "{\"event\":\"short_link\",\"data\":{\"url_key\":\"X\"}}");
        assertThat(r.processingStatus()).isEqualTo(ProcessingStatus.REJECTED);
        assertThat(r.rejectionReason()).isEqualTo("short_link: data.message_id is missing");
        assertThat(count("dlr.click.rejected")).isEqualTo(1);
    }

    @Test
    void billingCallbackIsRoutedToBillingAndNotToStatusAdapters() {
        when(billingRepository.insertIfAbsent(any())).thenReturn(Optional.of(7L));

        ProcessingResult r = service.process("DEFAULT_SMS", TestPayloads.BILLING_EXAMPLE);

        assertThat(r.isBilling()).isTrue();
        assertThat(r.billing().processingStatus()).isEqualTo("APPLIED");
        assertThat(r.billing().events().get(0).messageId()).isEqualTo("9b1b0309-4d49-48be-b2c1-0283892ded9e");
        assertThat(r.billing().events().get(0).billingMessageId()).isEqualTo("9b1b0309-4d49-48be-b2c1-0283892ded9e:1");
        verify(events, never()).insertIfAbsent(any());
        assertThat(count("dlr.billing.applied")).isEqualTo(1);
        assertThat(count("dlr.billing.units")).isEqualTo(1);
    }

    @Test
    void billingWithoutSourceHeaderUsesDefaultSource() {
        when(billingRepository.insertIfAbsent(any())).thenReturn(Optional.of(7L));
        assertThat(service.process(null, TestPayloads.BILLING_EXAMPLE).billing().source()).isEqualTo("DEFAULT_SMS");
    }

    @Test
    void duplicateBillingEventIsStoredAsDuplicate() {
        when(billingRepository.insertIfAbsent(any())).thenReturn(Optional.empty());
        when(billingRepository.findPrimaryIdByDedupKey(any())).thenReturn(Optional.of(7L));

        ProcessingResult r = service.process("DEFAULT_SMS", TestPayloads.BILLING_EXAMPLE);

        assertThat(r.billing().processingStatus()).isEqualTo("DUPLICATE");
        ArgumentCaptor<DlrBillingEvent> captor = ArgumentCaptor.forClass(DlrBillingEvent.class);
        verify(billingRepository).insert(captor.capture());
        assertThat(captor.getValue().getProcessingStatus()).isEqualTo("DUPLICATE");
        assertThat(captor.getValue().getDuplicateOf()).isEqualTo(7L);
    }

    @Test
    void billingWithoutEventsIsRejectedAsACallback() {
        ProcessingResult r = service.process("DEFAULT_SMS", "{\"event_type\":\"billing\"}");
        assertThat(r.isBilling()).isFalse();
        assertThat(r.processingStatus()).isEqualTo(ProcessingStatus.REJECTED);
        assertThat(r.rejectionReason()).isEqualTo("billing: events is missing");
    }

    @Test
    void billingFromANonBillingSourceIsRejected() {
        ProcessingResult r = service.process("WEBENGAGE", TestPayloads.BILLING_EXAMPLE);
        assertThat(r.processingStatus()).isEqualTo(ProcessingStatus.REJECTED);
        assertThat(r.rejectionReason()).startsWith("billing DLRs are not accepted from source WEBENGAGE");
    }

    private DlrMessageStatus state(String status) {
        DlrMessageStatus s = new DlrMessageStatus();
        s.setMessageId("m1");
        s.setNormalizedStatus(status);
        return s;
    }

    private double count(String name) {
        return meters.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    void firstDlrIsApplied() {
        when(events.insertIfAbsent(any())).thenReturn(Optional.of(1L));
        when(statuses.findForUpdate("m1")).thenReturn(Optional.empty());
        when(statuses.insertIfAbsent(any(), eq(1L))).thenReturn(true);

        ProcessingResult r = service.process("DEFAULT_SMS", TestPayloads.defaultSms("m1", "DELIVRD"));

        assertThat(r.processingStatus()).isEqualTo(ProcessingStatus.APPLIED);
        assertThat(r.currentStatus()).isEqualTo("DELIVERED");
        assertThat(count("dlr.received")).isEqualTo(1);
        assertThat(count("dlr.delivered")).isEqualTo(1);
    }

    @Test
    void sentThenDeliveredIsAValidProgression() {
        when(events.insertIfAbsent(any())).thenReturn(Optional.of(2L));
        when(statuses.findForUpdate("m1")).thenReturn(Optional.of(state("SENT")));

        ProcessingResult r = service.process("DEFAULT_SMS", TestPayloads.defaultSms("m1", "DELIVRD"));

        assertThat(r.processingStatus()).isEqualTo(ProcessingStatus.APPLIED);
        verify(statuses).applyTransition(any(), eq(2L));
    }

    @Test
    void outOfOrderSentAfterDeliveredIsIgnored() {
        when(events.insertIfAbsent(any())).thenReturn(Optional.of(3L));
        when(statuses.findForUpdate("m1")).thenReturn(Optional.of(state("DELIVERED")));

        ProcessingResult r = service.process("DEFAULT_SMS", TestPayloads.defaultSms("m1", "SUBMITTED"));

        assertThat(r.processingStatus()).isEqualTo(ProcessingStatus.IGNORED);
        assertThat(r.currentStatus()).isEqualTo("DELIVERED");
        verify(statuses, never()).applyTransition(any(), anyLong());
        verify(events).updateProcessing(eq(3L), eq(ProcessingStatus.IGNORED), any());
        assertThat(count("dlr.sent")).as("ignored events do not count as state changes").isZero();
        assertThat(count("dlr.ignored")).isEqualTo(1);
    }

    @Test
    void exactDuplicateIsStoredAsDuplicateAndDoesNotTouchState() {
        when(events.insertIfAbsent(any())).thenReturn(Optional.empty());
        when(events.findPrimaryIdByDedupKey(any())).thenReturn(Optional.of(1L));
        when(statuses.findByMessageId("m1")).thenReturn(Optional.of(state("DELIVERED")));

        ProcessingResult r = service.process("DEFAULT_SMS", TestPayloads.defaultSms("m1", "DELIVRD"));

        assertThat(r.processingStatus()).isEqualTo(ProcessingStatus.DUPLICATE);
        ArgumentCaptor<DlrEvent> captor = ArgumentCaptor.forClass(DlrEvent.class);
        verify(events).insert(captor.capture());
        assertThat(captor.getValue().getProcessingStatus()).isEqualTo(ProcessingStatus.DUPLICATE);
        assertThat(captor.getValue().getDuplicateOf()).isEqualTo(1L);
        verify(statuses, never()).applyTransition(any(), anyLong());
        verify(statuses).incrementDuplicate("m1");
        assertThat(count("dlr.duplicate")).isEqualTo(1);
        assertThat(count("dlr.delivered")).isZero();
    }

    @Test
    void missingMessageIdIsPersistedAsRejected() {
        ProcessingResult r = service.process("DEFAULT_SMS", "{\"payload\":{\"mobile\":\"91\",\"status\":\"DELIVRD\"}}");

        assertThat(r.processingStatus()).isEqualTo(ProcessingStatus.REJECTED);
        assertThat(r.rejectionReason()).isEqualTo("message_id is missing");
        ArgumentCaptor<DlrEvent> captor = ArgumentCaptor.forClass(DlrEvent.class);
        verify(events).insert(captor.capture());
        assertThat(captor.getValue().getRawPayload()).contains("\"mobile\":\"91\"");
        assertThat(captor.getValue().getRejectionReason()).isEqualTo("message_id is missing");
        assertThat(count("dlr.rejected")).isEqualTo(1);
    }

    @Test
    void malformedJsonIsPersistedAsRejectedWithRawBody() {
        ProcessingResult r = service.process(null, "{not json");

        assertThat(r.processingStatus()).isEqualTo(ProcessingStatus.REJECTED);
        assertThat(r.rejectionReason()).isEqualTo("malformed JSON payload");
        ArgumentCaptor<DlrEvent> captor = ArgumentCaptor.forClass(DlrEvent.class);
        verify(events).insert(captor.capture());
        assertThat(captor.getValue().getRawPayload()).contains("_unparseable_body").contains("{not json");
    }

    @Test
    void unknownSourceAndUndetectablePayloadAreRejected() {
        assertThat(service.process("ACME", TestPayloads.WEBENGAGE_EXAMPLE).rejectionReason())
                .startsWith("unsupported DLR source: ACME");
        assertThat(service.process(null, "{\"foo\":1}").rejectionReason())
                .startsWith("unable to determine DLR source");
        assertThat(service.process(null, "").rejectionReason()).isEqualTo("request body is empty");
    }

    @Test
    void payloadDetectionWorksWithoutHeader() {
        when(events.insertIfAbsent(any())).thenReturn(Optional.of(5L));
        when(statuses.findForUpdate(any())).thenReturn(Optional.empty());
        when(statuses.insertIfAbsent(any(), anyLong())).thenReturn(true);

        assertThat(service.process(null, TestPayloads.WEBENGAGE_EXAMPLE).source()).isEqualTo("WEBENGAGE");
        assertThat(service.process(null, TestPayloads.DEFAULT_SMS_EXAMPLE).source()).isEqualTo("DEFAULT_SMS");
    }

    @Test
    void nulCharactersAreSanitisedSoThePayloadIsStorable() {
        service.process("DEFAULT_SMS", "{\"payload\":{\"message_id\":\"a\\u0000b\"}}");
        ArgumentCaptor<DlrEvent> captor = ArgumentCaptor.forClass(DlrEvent.class);
        verify(events).insert(captor.capture());
        assertThat(captor.getValue().getRawPayload()).doesNotContain("\\u0000");
    }
}
