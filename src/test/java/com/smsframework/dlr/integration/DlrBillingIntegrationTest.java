package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing DLRs arrive on the same endpoint as status DLRs and are correlated by message_id
 * (billing "message_id":"<id>:<part>" -> status message_id "<id>").
 */
class DlrBillingIntegrationTest extends AbstractIntegrationTest {

    private static final Map<String, String> DEFAULT_SMS = Map.of("X-DLR-Source", "DEFAULT_SMS");
    private static final String MSG = "9b1b0309-4d49-48be-b2c1-0283892ded9e";

    @Test
    void statusAndBillingDlrAreCorrelatedByMessageId() throws Exception {
        postDlr(TestPayloads.defaultSms(MSG, "DELIVRD"), DEFAULT_SMS);
        HttpResponse<String> post = postDlr(TestPayloads.BILLING_EXAMPLE, DEFAULT_SMS);

        assertThat(post.statusCode()).isEqualTo(200);
        JsonNode ack = readJson(post.body());
        assertThat(ack.get("event_type").asText()).isEqualTo("billing");
        assertThat(ack.get("processing_status").asText()).isEqualTo("APPLIED");
        assertThat(ack.get("applied").asInt()).isEqualTo(1);
        assertThat(ack.at("/events/0/message_id").asText()).isEqualTo(MSG);
        assertThat(ack.at("/events/0/billing_message_id").asText()).isEqualTo(MSG + ":1");

        // exact persisted row
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM dlr_billing_events");
        assertThat(row.get("source")).isEqualTo("DEFAULT_SMS");
        assertThat(row.get("message_id")).isEqualTo(MSG);
        assertThat(row.get("billing_message_id")).isEqualTo(MSG + ":1");
        assertThat(row.get("part_number")).isEqualTo(1);
        assertThat(row.get("transaction_type")).isEqualTo("debit");
        assertThat(row.get("product")).isEqualTo("SMS Transactional");
        assertThat(row.get("units")).isEqualTo(1);
        assertThat((java.math.BigDecimal) row.get("sale_price")).isEqualByComparingTo("1");
        assertThat(row.get("currency")).isEqualTo("INR");
        assertThat((java.math.BigDecimal) row.get("surcharge")).isEqualByComparingTo("0");
        assertThat((java.math.BigDecimal) row.get("total_amount")).isEqualByComparingTo("1");
        assertThat(row.get("processing_status")).isEqualTo("APPLIED");
        assertThat(JSON.readTree(row.get("raw_payload").toString())).isEqualTo(JSON.readTree(TestPayloads.BILLING_EXAMPLE));
        assertThat(JSON.readTree(row.get("raw_event").toString()))
                .isEqualTo(JSON.readTree(TestPayloads.BILLING_EXAMPLE).get("events").get(0));

        // billing never touches the status DLR state
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events WHERE message_id = ?", Integer.class, MSG)).isEqualTo(1);

        JsonNode r = getJson("/api/v1/dlr/" + MSG);
        assertThat(r.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(r.at("/billing/billed").asBoolean()).isTrue();
        assertThat(r.at("/billing/events").asInt()).isEqualTo(1);
        assertThat(r.at("/billing/parts").asInt()).isEqualTo(1);
        assertThat(r.at("/billing/units").asInt()).isEqualTo(1);
        assertThat(r.at("/billing/total_amount").decimalValue()).isEqualByComparingTo("1");
        assertThat(r.at("/billing/currency").asText()).isEqualTo("INR");

        JsonNode details = getJson("/api/v1/dlr/" + MSG + "/billing");
        assertThat(details.get("events")).hasSize(1);
        assertThat(details.at("/events/0/raw_event/product").asText()).isEqualTo("SMS Transactional");
    }

    @Test
    void statusWithoutBillingShowsNotBilled() {
        postDlr(TestPayloads.defaultSms("no-bill", "DELIVRD"), DEFAULT_SMS);
        JsonNode r = getJson("/api/v1/dlr/no-bill");
        assertThat(r.at("/billing/billed").asBoolean()).isFalse();
        assertThat(r.at("/billing/events").asInt()).isZero();
    }

    @Test
    void billingMayArriveBeforeTheStatusDlr() {
        postDlr(TestPayloads.billing("early-1", 1, "debit", "0.25"), Map.of()); // no source header
        JsonNode before = getJson("/api/v1/dlr/early-1");
        assertThat(before.get("received").asBoolean()).isFalse();
        assertThat(before.get("status").asText()).isEqualTo("PENDING");
        assertThat(before.at("/billing/billed").asBoolean()).isTrue();

        postDlr(TestPayloads.defaultSms("early-1", "DELIVRD"), DEFAULT_SMS);
        JsonNode after = getJson("/api/v1/dlr/early-1");
        assertThat(after.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(after.at("/billing/total_amount").decimalValue()).isEqualByComparingTo("0.25");
    }

    @Test
    void multipartBillingIsSummedPerMessage() {
        postDlr(TestPayloads.billing("multi-1", 3, "debit", "0.20"), DEFAULT_SMS);
        JsonNode b = getJson("/api/v1/dlr/multi-1/billing").get("billing");
        assertThat(b.get("events").asInt()).isEqualTo(3);
        assertThat(b.get("parts").asInt()).isEqualTo(3);
        assertThat(b.get("units").asInt()).isEqualTo(3);
        assertThat(b.get("total_amount").decimalValue()).isEqualByComparingTo("0.60");
    }

    @Test
    void refundIsSubtractedFromTheNetAmount() {
        postDlr(TestPayloads.billing("refund-1", 1, "debit", "1"), DEFAULT_SMS);
        postDlr(TestPayloads.billing("refund-1", 1, "refund", "1"), DEFAULT_SMS);
        JsonNode b = getJson("/api/v1/dlr/refund-1/billing").get("billing");
        assertThat(b.get("events").asInt()).isEqualTo(2);
        assertThat(b.get("units").asInt()).isZero();
        assertThat(b.get("debit_amount").decimalValue()).isEqualByComparingTo("1");
        assertThat(b.get("credit_amount").decimalValue()).isEqualByComparingTo("1");
        assertThat(b.get("total_amount").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void duplicateBillingIsNotDoubleCounted() {
        for (int i = 0; i < 3; i++) {
            assertThat(postDlr(TestPayloads.BILLING_EXAMPLE, DEFAULT_SMS).statusCode()).isEqualTo(200);
        }
        assertThat(jdbc.queryForList("SELECT processing_status FROM dlr_billing_events ORDER BY id", String.class))
                .containsExactly("APPLIED", "DUPLICATE", "DUPLICATE");
        assertThat(getJson("/api/v1/dlr/" + MSG + "/billing").at("/billing/total_amount").decimalValue())
                .isEqualByComparingTo("1");
        // 1 and 1.0 are the same amount -> still a duplicate
        String sameAmountDifferentFormat = TestPayloads.BILLING_EXAMPLE.replace("\"total_amount\": 1", "\"total_amount\": 1.0");
        assertThat(readJson(postDlr(sameAmountDifferentFormat, DEFAULT_SMS).body()).get("processing_status").asText())
                .isEqualTo("DUPLICATE");
    }

    @Test
    void concurrentIdenticalBillingCallbacksAreAppliedOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            futures.add(pool.submit(() -> postDlr(TestPayloads.BILLING_EXAMPLE, DEFAULT_SMS).statusCode()));
        }
        for (Future<Integer> f : futures) {
            assertThat(f.get()).isEqualTo(200);
        }
        pool.shutdown();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_billing_events WHERE processing_status='APPLIED'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_billing_events WHERE processing_status='DUPLICATE'",
                Integer.class)).isEqualTo(29);
    }

    @Test
    void invalidEventsInsideABatchAreRejectedIndividually() {
        String body = """
                {"event_type":"billing","events":[
                  {"transaction_type":"debit","message_id":"part-ok:1","units":1,"total_amount":1,"currency":"INR"},
                  {"transaction_type":"debit","units":1,"total_amount":1}
                ]}""";
        HttpResponse<String> r = postDlr(body, DEFAULT_SMS);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode ack = readJson(r.body());
        assertThat(ack.get("processing_status").asText()).isEqualTo("PARTIAL");
        assertThat(ack.get("applied").asInt()).isEqualTo(1);
        assertThat(ack.get("rejected").asInt()).isEqualTo(1);
        assertThat(ack.at("/events/1/rejection_reason").asText()).isEqualTo("message_id is missing");

        JsonNode rejected = getJson("/api/v1/dlr/events/billing/rejected");
        assertThat(rejected).hasSize(1);
        assertThat(rejected.at("/0/raw_event/units").asInt()).isEqualTo(1);
    }

    @Test
    void invalidBillingCallbacksAreStoredAsRejected() {
        assertThat(postDlr("{\"event_type\":\"billing\"}", DEFAULT_SMS).statusCode()).isEqualTo(400);
        assertThat(postDlr("{\"event_type\":\"billing\",\"events\":[{\"units\":1}]}", DEFAULT_SMS).statusCode()).isEqualTo(400);
        assertThat(postDlr(TestPayloads.BILLING_EXAMPLE, Map.of("X-DLR-Source", "WEBENGAGE")).statusCode()).isEqualTo(400);

        assertThat(jdbc.queryForList("SELECT rejection_reason FROM dlr_events ORDER BY id", String.class))
                .containsExactly("billing: events is missing",
                        "billing DLRs are not accepted from source WEBENGAGE (allowed: [DEFAULT_SMS])");
        assertThat(jdbc.queryForObject("SELECT rejection_reason FROM dlr_billing_events", String.class))
                .isEqualTo("message_id is missing; transaction_type is missing");
    }

    @Test
    void verifyCanRequireBilling() {
        postDlr(TestPayloads.defaultSms("v-1", "DELIVRD"), DEFAULT_SMS);
        postDlr(TestPayloads.billing("v-1", 2, "debit", "0.5"), DEFAULT_SMS);
        postDlr(TestPayloads.defaultSms("v-2", "DELIVRD"), DEFAULT_SMS); // delivered, never billed

        JsonNode v = readJson(postDlr("/api/v1/dlr/verify",
                "{\"message_ids\":[\"v-1\",\"v-2\"],\"expected_status\":\"DELIVERED\",\"require_billing\":true}",
                Map.of()).body());
        assertThat(v.get("delivered").asInt()).isEqualTo(2);
        assertThat(v.get("billed").asInt()).isEqualTo(1);
        assertThat(v.get("billing_missing").asInt()).isEqualTo(1);
        assertThat(v.get("matched").asInt()).isEqualTo(1);
        assertThat(v.get("all_matched").asBoolean()).isFalse();
        assertThat(v.at("/results/0/billed").asBoolean()).isTrue();
        assertThat(v.at("/results/0/billed_units").asInt()).isEqualTo(2);
        assertThat(v.at("/results/0/billed_amount").decimalValue()).isEqualByComparingTo("1");
        assertThat(v.at("/results/0/currency").asText()).isEqualTo("INR");
        assertThat(v.at("/results/1/billed").asBoolean()).isFalse();
        assertThat(v.at("/results/1/matched").asBoolean()).isFalse();

        JsonNode withoutBilling = readJson(postDlr("/api/v1/dlr/verify",
                "{\"message_ids\":[\"v-1\",\"v-2\"],\"expected_status\":\"DELIVERED\"}", Map.of()).body());
        assertThat(withoutBilling.get("all_matched").asBoolean()).isTrue();
    }

    @Test
    void billingMetricsAreExposed() {
        postDlr(TestPayloads.BILLING_EXAMPLE, DEFAULT_SMS);
        for (String m : List.of("dlr.billing.received", "dlr.billing.applied", "dlr.billing.duplicate",
                "dlr.billing.rejected", "dlr.billing.units")) {
            assertThat(get("/actuator/metrics/" + m).statusCode()).as(m).isEqualTo(200);
        }
    }
}
