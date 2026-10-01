package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST DLR -> PostgreSQL -> GET DLR, verifying the exact persisted result.
 */
class DlrReceiverIntegrationTest extends AbstractIntegrationTest {

    private static final Map<String, String> DEFAULT_SMS = Map.of("X-DLR-Source", "DEFAULT_SMS");
    private static final Map<String, String> WEBENGAGE = Map.of("X-DLR-Source", "WEBENGAGE");

    // ------------------------------------------------------------------ happy paths

    @Test
    void defaultSmsReferencePayloadIsPersistedAndQueryable() throws Exception {
        HttpResponse<String> post = postDlr(TestPayloads.DEFAULT_SMS_EXAMPLE, DEFAULT_SMS);
        assertThat(post.statusCode()).isEqualTo(200);
        assertThat(readJson(post.body()).get("processing_status").asText()).isEqualTo("APPLIED");

        // Exact persisted event row
        Map<String, Object> ev = jdbc.queryForMap("SELECT * FROM dlr_events WHERE message_id = ?",
                "2ee98174-eec2-46b1-9b3c-baa0853c9538");
        assertThat(ev.get("source")).isEqualTo("DEFAULT_SMS");
        assertThat(ev.get("provider_status")).isEqualTo("DELIVRD");
        assertThat(ev.get("normalized_status")).isEqualTo("DELIVERED");
        assertThat(ev.get("status_code")).isEqualTo("000");
        assertThat(ev.get("mobile")).isEqualTo("917973059161");
        assertThat(ev.get("sender")).isEqualTo("MSEFSL");
        assertThat(ev.get("service")).isEqualTo("T");
        assertThat(ev.get("units")).isEqualTo(2);
        assertThat(ev.get("template_id")).isEqualTo("1507165786055955979");
        assertThat(ev.get("entity_id")).isNull();
        assertThat(ev.get("correlation_id"))
                .isEqualTo("75892985798379875987198579175987912757589298579837987598719857917598791275");
        assertThat(((java.sql.Timestamp) ev.get("submit_at")).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 6, 22, 11, 47, 33));
        assertThat(ev.get("processing_status")).isEqualTo("APPLIED");
        assertThat(ev.get("dedup_key")).asString().hasSize(64);
        // raw_payload is the complete original DLR
        assertThat(JSON.readTree(ev.get("raw_payload").toString()))
                .isEqualTo(JSON.readTree(TestPayloads.DEFAULT_SMS_EXAMPLE));

        // Query API response (exact contract)
        JsonNode r = getJson("/api/v1/dlr/2ee98174-eec2-46b1-9b3c-baa0853c9538");
        assertThat(r.get("message_id").asText()).isEqualTo("2ee98174-eec2-46b1-9b3c-baa0853c9538");
        assertThat(r.get("source").asText()).isEqualTo("DEFAULT_SMS");
        assertThat(r.get("received").asBoolean()).isTrue();
        assertThat(r.get("provider_status").asText()).isEqualTo("DELIVRD");
        assertThat(r.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(r.get("status_code").asText()).isEqualTo("000");
        assertThat(r.get("mobile").asText()).isEqualTo("917973059161");
        assertThat(r.get("units").asInt()).isEqualTo(2);
        assertThat(r.get("received_at").asText()).isEqualTo("2026-06-22T11:47:33");
        assertThat(r.get("correlation_id").asText()).startsWith("7589298579");
        assertThat(r.get("event_count").asInt()).isEqualTo(1);
    }

    @Test
    void webEngageSentThenDelivered() {
        postDlr(TestPayloads.WEBENGAGE_EXAMPLE, WEBENGAGE);

        JsonNode sent = getJson("/api/v1/dlr/f1189190-3fab-4a74-9130-f932be1de679");
        assertThat(sent.get("received").asBoolean()).isTrue();
        assertThat(sent.get("source").asText()).isEqualTo("WEBENGAGE");
        assertThat(sent.get("provider_status").asText()).isEqualTo("sms_sent");
        assertThat(sent.get("status").asText()).as("sms_sent is SENT, not DELIVERED").isEqualTo("SENT");
        assertThat(sent.get("status_code").asText()).isEqualTo("0");
        assertThat(sent.get("mobile").asText()).isEqualTo("919014305913");
        assertThat(sent.get("units").asInt()).isEqualTo(3);
        assertThat(sent.get("received_at").isTextual()).as("receiver time used when provider sends none").isTrue();

        postDlr(TestPayloads.webEngage("f1189190-3fab-4a74-9130-f932be1de679", "sms_delivered", "0"), WEBENGAGE);

        JsonNode delivered = getJson("/api/v1/dlr/f1189190-3fab-4a74-9130-f932be1de679?include_events=true");
        assertThat(delivered.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(delivered.get("provider_status").asText()).isEqualTo("sms_delivered");
        assertThat(delivered.get("event_count").asInt()).isEqualTo(2);
        assertThat(delivered.get("events")).hasSize(2);
        assertThat(delivered.get("events").get(0).get("provider_status").asText()).isEqualTo("sms_sent");
        assertThat(delivered.get("events").get(1).get("processing_status").asText()).isEqualTo("APPLIED");
    }

    @Test
    void sourceCanComeFromProviderHeaderQueryParamAliasOrPayloadDetection() {
        assertThat(postDlr(TestPayloads.webEngage("w-hdr", "sms_sent", "0"), Map.of("X-DLR-Provider", "webengage"))
                .statusCode()).isEqualTo(200);
        assertThat(postDlr("/api/v1/dlr/receive?source=WE", TestPayloads.webEngage("w-q", "sms_sent", "0"), Map.of())
                .statusCode()).isEqualTo(200);
        assertThat(postDlr(TestPayloads.defaultSms("d-detect", "DELIVRD"), Map.of()).statusCode()).isEqualTo(200);
        assertThat(postDlr(TestPayloads.webEngage("w-detect", "sms_sent", "0"), Map.of()).statusCode()).isEqualTo(200);

        assertThat(getJson("/api/v1/dlr/w-hdr").get("source").asText()).isEqualTo("WEBENGAGE");
        assertThat(getJson("/api/v1/dlr/w-q").get("source").asText()).isEqualTo("WEBENGAGE");
        assertThat(getJson("/api/v1/dlr/d-detect").get("source").asText()).isEqualTo("DEFAULT_SMS");
        assertThat(getJson("/api/v1/dlr/w-detect").get("source").asText()).isEqualTo("WEBENGAGE");
    }

    @Test
    void unknownMessageReturnsPending() throws Exception {
        HttpResponse<String> r = get("/api/v1/dlr/unknown");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(r.body())).isEqualTo(JSON.readTree("""
                {"message_id":"unknown","received":false,"status":"PENDING"}"""));
    }

    // ------------------------------------------------------------------ state transitions / idempotency

    @Test
    void outOfOrderSentAfterDeliveredDoesNotDowngrade() {
        postDlr(TestPayloads.defaultSms("ooo-1", "917973059161", "DELIVRD", "000", "2026-06-22 11:48:00", "c"), DEFAULT_SMS);
        HttpResponse<String> late = postDlr(TestPayloads.defaultSms("ooo-1", "917973059161", "SUBMITTED", "000",
                "2026-06-22 11:47:40", "c"), DEFAULT_SMS);

        assertThat(late.statusCode()).isEqualTo(200);
        assertThat(readJson(late.body()).get("processing_status").asText()).isEqualTo("IGNORED");
        assertThat(readJson(late.body()).get("current_status").asText()).isEqualTo("DELIVERED");

        JsonNode r = getJson("/api/v1/dlr/ooo-1");
        assertThat(r.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(r.get("provider_status").asText()).isEqualTo("DELIVRD");
        assertThat(r.get("received_at").asText()).isEqualTo("2026-06-22T11:48:00");
        assertThat(jdbc.queryForList("SELECT processing_status FROM dlr_events WHERE message_id='ooo-1' ORDER BY id",
                String.class)).containsExactly("APPLIED", "IGNORED");
    }

    @Test
    void finalStateIsNotOverwrittenByALaterConflictingFinalState() {
        postDlr(TestPayloads.defaultSms("fin-1", "DELIVRD"), DEFAULT_SMS);
        postDlr(TestPayloads.defaultSms("fin-1", "917973059161", "UNDELIV", "034", "2026-06-22 11:59:00", "c"), DEFAULT_SMS);
        assertThat(getJson("/api/v1/dlr/fin-1").get("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void exactDuplicatesAreRecordedButDoNotChangeState() {
        String dlr = TestPayloads.defaultSms("dup-1", "DELIVRD");
        for (int i = 0; i < 3; i++) {
            assertThat(postDlr(dlr, DEFAULT_SMS).statusCode()).isEqualTo(200);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, processing_status, duplicate_of FROM dlr_events WHERE message_id='dup-1' ORDER BY id");
        assertThat(rows).extracting(m -> m.get("processing_status")).containsExactly("APPLIED", "DUPLICATE", "DUPLICATE");
        assertThat(rows.get(1).get("duplicate_of")).isEqualTo(rows.get(0).get("id"));

        JsonNode r = getJson("/api/v1/dlr/dup-1");
        assertThat(r.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(r.get("event_count").asInt()).isEqualTo(1);
        assertThat(r.get("duplicate_count").asInt()).isEqualTo(2);
    }

    @Test
    void sameStateWithDifferentTimestampIsIgnoredNotDuplicated() {
        postDlr(TestPayloads.defaultSms("same-1", "917973059161", "DELIVRD", "000", "2026-06-22 11:47:33", "c"), DEFAULT_SMS);
        HttpResponse<String> again = postDlr(TestPayloads.defaultSms("same-1", "917973059161", "DELIVRD", "000",
                "2026-06-22 11:49:00", "c"), DEFAULT_SMS);
        assertThat(readJson(again.body()).get("processing_status").asText()).isEqualTo("IGNORED");
        JsonNode r = getJson("/api/v1/dlr/same-1");
        assertThat(r.get("received_at").asText()).as("first DELIVERED wins").isEqualTo("2026-06-22T11:47:33");
    }

    @Test
    void concurrentIdenticalCallbacksAreRecordedOnceAcrossThreads() throws Exception {
        String dlr = TestPayloads.defaultSms("race-1", "DELIVRD");
        int n = 40;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return postDlr(dlr, DEFAULT_SMS).statusCode();
            }));
        }
        start.countDown();
        for (Future<Integer> f : futures) {
            assertThat(f.get()).isEqualTo(200);
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events WHERE message_id='race-1' AND processing_status='APPLIED'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events WHERE message_id='race-1' AND processing_status='DUPLICATE'",
                Integer.class)).isEqualTo(n - 1);
        assertThat(jdbc.queryForObject("SELECT duplicate_count FROM dlr_message_status WHERE message_id='race-1'",
                Integer.class)).isEqualTo(n - 1);
    }

    @Test
    void concurrentOutOfOrderStatusesAlwaysConvergeToFinalState() throws Exception {
        int messages = 60;
        List<String[]> calls = new ArrayList<>();
        for (int i = 0; i < messages; i++) {
            String id = "conv-" + i;
            calls.add(new String[]{id, "SUBMITTED"});
            calls.add(new String[]{id, i % 2 == 0 ? "DELIVRD" : "UNDELIV"});
            calls.add(new String[]{id, "SUBMITTED"});
        }
        Collections.shuffle(calls, new java.util.Random(42));
        ExecutorService pool = Executors.newFixedThreadPool(24);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String[] c : calls) {
            futures.add(pool.submit(() -> postDlr(TestPayloads.defaultSms(c[0], c[1]), DEFAULT_SMS).statusCode()));
        }
        for (Future<Integer> f : futures) {
            assertThat(f.get()).isEqualTo(200);
        }
        pool.shutdown();

        for (int i = 0; i < messages; i++) {
            String expected = i % 2 == 0 ? "DELIVERED" : "FAILED";
            assertThat(getJson("/api/v1/dlr/conv-" + i).get("status").asText()).as("conv-" + i).isEqualTo(expected);
        }
    }

    // ------------------------------------------------------------------ rejected / malformed

    @Test
    void missingMessageIdIsStoredAsRejectedWithRawPayload() throws Exception {
        String body = "{\"payload\":{\"mobile\":\"917973059161\",\"status\":\"DELIVRD\"}}";
        HttpResponse<String> r = postDlr(body, DEFAULT_SMS);

        assertThat(r.statusCode()).isEqualTo(400);
        JsonNode ack = readJson(r.body());
        assertThat(ack.get("processing_status").asText()).isEqualTo("REJECTED");
        assertThat(ack.get("rejection_reason").asText()).isEqualTo("message_id is missing");

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM dlr_events WHERE processing_status='REJECTED'");
        assertThat(row.get("source")).isEqualTo("DEFAULT_SMS");
        assertThat(row.get("message_id")).isNull();
        assertThat(row.get("rejection_reason")).isEqualTo("message_id is missing");
        assertThat(JSON.readTree(row.get("raw_payload").toString())).isEqualTo(JSON.readTree(body));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_message_status", Integer.class)).isZero();
    }

    @Test
    void invalidPayloadWithMessageIdIsVisibleToAutomationAsRejected() {
        postDlr("{\"payload\":{\"message_id\":\"bad-1\",\"status\":\"DELIVRD\"}}", DEFAULT_SMS); // mobile missing
        JsonNode r = getJson("/api/v1/dlr/bad-1");
        assertThat(r.get("received").asBoolean()).isFalse();
        assertThat(r.get("status").asText()).isEqualTo("PENDING");
        assertThat(r.get("rejected_events").asInt()).isEqualTo(1);
        assertThat(r.get("last_rejection_reason").asText()).isEqualTo("mobile is missing");
    }

    @Test
    void malformedJsonUnknownSourceAndEmptyBodyAreStoredNotDropped() {
        assertThat(postDlr("{broken json", DEFAULT_SMS).statusCode()).isEqualTo(400);
        assertThat(postDlr(TestPayloads.WEBENGAGE_EXAMPLE, Map.of("X-DLR-Source", "ACME")).statusCode()).isEqualTo(400);
        assertThat(postDlr("", Map.of()).statusCode()).isEqualTo(400);
        assertThat(postDlr("{\"hello\":\"world\"}", Map.of()).statusCode()).isEqualTo(400);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT source, rejection_reason, raw_payload::text AS raw FROM dlr_events ORDER BY id");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).get("rejection_reason")).isEqualTo("malformed JSON payload");
        assertThat(rows.get(0).get("raw").toString()).contains("{broken json");
        assertThat(rows.get(1).get("source")).isEqualTo("ACME");
        assertThat(rows.get(1).get("rejection_reason").toString()).startsWith("unsupported DLR source: ACME");
        assertThat(rows.get(2).get("rejection_reason")).isEqualTo("request body is empty");
        assertThat(rows.get(3).get("rejection_reason").toString()).startsWith("unable to determine DLR source");

        JsonNode rejected = getJson("/api/v1/dlr/events/rejected?limit=10");
        assertThat(rejected).hasSize(4);
    }

    @Test
    void oversizedPayloadIsRejectedWith413AndStored() {
        String huge = "{\"payload\":{\"message_id\":\"big\",\"mobile\":\"1\",\"status\":\"DELIVRD\",\"pad\":\""
                + "x".repeat(10 * 1024 * 1024 + 10) + "\"}}";
        assertThat(postDlr(huge, DEFAULT_SMS).statusCode()).isEqualTo(413);
        assertThat(jdbc.queryForObject("SELECT raw_payload->>'_payload_too_large' FROM dlr_events", String.class))
                .isEqualTo("true");
    }

    // ------------------------------------------------------------------ verification / lookup APIs

    @Test
    void bulkVerifyEndpoint() {
        postDlr(TestPayloads.defaultSms("MSG-001", "DELIVRD"), DEFAULT_SMS);
        postDlr(TestPayloads.defaultSms("MSG-002", "DELIVRD"), DEFAULT_SMS);
        postDlr(TestPayloads.defaultSms("MSG-003", "UNDELIV"), DEFAULT_SMS);

        HttpResponse<String> r = postDlr("/api/v1/dlr/verify",
                "{\"message_ids\":[\"MSG-001\",\"MSG-002\",\"MSG-003\",\"MSG-404\"]}", Map.of());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode v = readJson(r.body());
        assertThat(v.get("total").asInt()).isEqualTo(4);
        assertThat(v.get("received").asInt()).isEqualTo(3);
        assertThat(v.get("delivered").asInt()).isEqualTo(2);
        assertThat(v.get("failed").asInt()).isEqualTo(1);
        assertThat(v.get("missing").asInt()).isEqualTo(1);
        assertThat(v.get("results").get(0).get("message_id").asText()).isEqualTo("MSG-001");
        assertThat(v.get("results").get(0).get("status").asText()).isEqualTo("DELIVERED");
        assertThat(v.get("results").get(2).get("status").asText()).isEqualTo("FAILED");
        assertThat(v.get("results").get(3).get("received").asBoolean()).isFalse();
        assertThat(v.get("results").get(3).get("status").asText()).isEqualTo("PENDING");

        JsonNode withExpected = readJson(postDlr("/api/v1/dlr/verify",
                "{\"message_ids\":[\"MSG-001\",\"MSG-002\"],\"expected_status\":\"DELIVERED\"}", Map.of()).body());
        assertThat(withExpected.get("all_matched").asBoolean()).isTrue();
        assertThat(withExpected.get("matched").asInt()).isEqualTo(2);
    }

    @Test
    void verifyValidatesInput() {
        assertThat(postDlr("/api/v1/dlr/verify", "{\"message_ids\":[]}", Map.of()).statusCode()).isEqualTo(400);
        assertThat(postDlr("/api/v1/dlr/verify", "{nope", Map.of()).statusCode()).isEqualTo(400);
        assertThat(postDlr("/api/v1/dlr/verify", "{\"message_ids\":[\"a\"],\"expected_status\":\"DONE\"}", Map.of())
                .statusCode()).isEqualTo(400);
    }

    @Test
    void searchByCorrelationId() {
        postDlr(TestPayloads.defaultSms("corr-msg", "917973059161", "DELIVRD", "000", "2026-06-22 11:47:33", "CORR-XYZ"),
                DEFAULT_SMS);
        JsonNode list = getJson("/api/v1/dlr/search?correlation_id=CORR-XYZ");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("message_id").asText()).isEqualTo("corr-msg");
        assertThat(get("/api/v1/dlr/search").statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ ops

    @Test
    void healthReportsDatabase() {
        JsonNode health = getJson("/actuator/health");
        assertThat(health.get("status").asText()).isEqualTo("UP");
        assertThat(health.at("/components/db/status").asText()).isEqualTo("UP");
        assertThat(health.at("/components/db/details/database").asText()).isEqualTo("PostgreSQL");
    }

    @Test
    void metricsAreExposed() {
        postDlr(TestPayloads.defaultSms("metric-1", "DELIVRD"), DEFAULT_SMS);
        postDlr(TestPayloads.defaultSms("metric-1", "DELIVRD"), DEFAULT_SMS);
        for (String m : List.of("dlr.received", "dlr.delivered", "dlr.duplicate", "dlr.rejected", "dlr.failed",
                "dlr.expired", "dlr.unknown", "dlr.processing.error", "dlr.processing.latency")) {
            assertThat(get("/actuator/metrics/" + m).statusCode()).as(m).isEqualTo(200);
        }
        JsonNode dup = getJson("/actuator/metrics/dlr.duplicate?tag=source:DEFAULT_SMS");
        assertThat(dup.at("/measurements/0/value").asDouble()).isGreaterThanOrEqualTo(1.0);
    }
}
