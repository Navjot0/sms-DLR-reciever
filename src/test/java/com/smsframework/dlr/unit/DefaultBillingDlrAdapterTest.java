package com.smsframework.dlr.unit;

import com.smsframework.dlr.billing.DefaultBillingDlrAdapter;
import com.smsframework.dlr.billing.NormalizedBillingEvent;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.exception.DlrValidationException;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.smsframework.dlr.unit.AdapterTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultBillingDlrAdapterTest {

    private final DefaultBillingDlrAdapter adapter =
            new DefaultBillingDlrAdapter(AdapterTestSupport.MAPPER, AdapterTestSupport.VALIDATOR, new DlrProperties());

    @Test
    void normalizesTheReferenceBillingPayloadExactly() {
        List<NormalizedBillingEvent> events = adapter.normalize(json(TestPayloads.BILLING_EXAMPLE));

        assertThat(events).hasSize(1);
        NormalizedBillingEvent e = events.get(0);
        assertThat(e.valid()).isTrue();
        assertThat(e.billingMessageId()).isEqualTo("9b1b0309-4d49-48be-b2c1-0283892ded9e:1");
        assertThat(e.messageId()).as("correlation key without part suffix").isEqualTo("9b1b0309-4d49-48be-b2c1-0283892ded9e");
        assertThat(e.partNumber()).isEqualTo(1);
        assertThat(e.transactionType()).isEqualTo("debit");
        assertThat(e.product()).isEqualTo("SMS Transactional");
        assertThat(e.units()).isEqualTo(1);
        assertThat(e.salePrice()).isEqualByComparingTo("1");
        assertThat(e.currency()).isEqualTo("INR");
        assertThat(e.surcharge()).isEqualByComparingTo("0");
        assertThat(e.totalAmount()).isEqualByComparingTo("1");
        assertThat(json(e.rawEvent())).isEqualTo(json(TestPayloads.BILLING_EXAMPLE).get("events").get(0));
    }

    @Test
    void detectsBillingPayloadsOnly() {
        assertThat(adapter.isBillingPayload(json(TestPayloads.BILLING_EXAMPLE))).isTrue();
        assertThat(adapter.isBillingPayload(json("{\"event_type\":\"BILLING\",\"events\":[]}"))).isTrue();
        assertThat(adapter.isBillingPayload(json(TestPayloads.DEFAULT_SMS_EXAMPLE))).isFalse();
        assertThat(adapter.isBillingPayload(json(TestPayloads.WEBENGAGE_EXAMPLE))).isFalse();
        assertThat(adapter.isBillingPayload(json("{\"event_type\":\"delivery\"}"))).isFalse();
        assertThat(adapter.acceptsSource("DEFAULT_SMS")).isTrue();
        assertThat(adapter.acceptsSource("WEBENGAGE")).isFalse();
    }

    @Test
    void messageIdWithoutPartSuffixIsKeptAsIs() {
        NormalizedBillingEvent e = adapter.normalize(json("""
                {"event_type":"billing","events":[{"transaction_type":"debit","message_id":"abc-123","units":1}]}""")).get(0);
        assertThat(e.messageId()).isEqualTo("abc-123");
        assertThat(e.partNumber()).isNull();

        NormalizedBillingEvent nonNumeric = adapter.normalize(json("""
                {"event_type":"billing","events":[{"transaction_type":"debit","message_id":"urn:msg:abc"}]}""")).get(0);
        assertThat(nonNumeric.messageId()).as("non-numeric suffix is not a part number").isEqualTo("urn:msg:abc");
    }

    @Test
    void numbersMayBeStringsOrDecimals() {
        NormalizedBillingEvent e = adapter.normalize(json("""
                {"event_type":"billing","events":[{"transaction_type":"DEBIT","message_id":"m:2","units":"2",
                "sale_price":"0.125","total_amount":0.25,"currency":"inr"}]}""")).get(0);
        assertThat(e.units()).isEqualTo(2);
        assertThat(e.partNumber()).isEqualTo(2);
        assertThat(e.salePrice()).isEqualTo(new BigDecimal("0.125"));
        assertThat(e.totalAmount()).isEqualByComparingTo("0.25");
        assertThat(e.transactionType()).isEqualTo("debit");
        assertThat(e.currency()).isEqualTo("INR");
    }

    @Test
    void invalidEventsAreRejectedIndividually() {
        List<NormalizedBillingEvent> events = adapter.normalize(json("""
                {"event_type":"billing","events":[
                  {"transaction_type":"debit","message_id":"ok:1","units":1,"total_amount":1},
                  {"transaction_type":"debit","units":1},
                  {"message_id":"m3:1"},
                  {"transaction_type":"debit","message_id":"m4:1","total_amount":"abc"},
                  {"transaction_type":"debit","message_id":"m5:1","units":-1},
                  {"transaction_type":"debit","message_id":"m6:1","units":1.5},
                  "not-an-object"
                ]}"""));

        assertThat(events).extracting(NormalizedBillingEvent::rejectionReason).containsExactly(
                null,
                "message_id is missing",
                "transaction_type is missing",
                "total_amount must be a number",
                "units must not be negative",
                "units must be a whole number",
                "event must be a JSON object");
        assertThat(events.get(3).messageId()).as("peeked id kept for debugging").isEqualTo("m4:1");
    }

    @Test
    void invalidBatchesAreRejectedAsAWhole() {
        assertThatThrownBy(() -> adapter.normalize(json("{\"event_type\":\"billing\"}")))
                .isInstanceOf(DlrValidationException.class).hasMessage("events is missing");
        assertThatThrownBy(() -> adapter.normalize(json("{\"event_type\":\"billing\",\"events\":[]}")))
                .hasMessage("events is empty");
        assertThatThrownBy(() -> adapter.normalize(json("{\"event_type\":\"billing\",\"events\":{}}")))
                .hasMessage("events must be an array");
    }
}
