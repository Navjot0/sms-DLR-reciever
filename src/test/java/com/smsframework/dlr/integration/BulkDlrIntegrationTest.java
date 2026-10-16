package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Bulk-push callbacks: {"batchId":"..","events":[..1000 DLRs..]} on /api/v1/dlr/receive. */
class BulkDlrIntegrationTest extends AbstractIntegrationTest {

    static final String CAMPAIGN = "eec423c8-42f5-473b-b895-d5430fadb0b0";

    /** One recipient DLR in the platform's format (as in the real bulk push). */
    static String event(String campaign, int n, String status, String code) {
        return "{\"message_id\":\"" + campaign + ":" + n + "\",\"service\":\"T\",\"sender\":\"DUMMY\",\"mobile\":\"91899961"
                + String.format("%04d", 5000 + n) + "\",\"status\":\"" + status + "\",\"code\":\"" + code + "\","
                + "\"submit_at\":\"2026-10-15 15:22:37\",\"dlr_received_at\":\"2026-10-15 15:22:37\","
                + "\"entity_id\":\"17011580464447946654\",\"template_id\":\"540723621824\",\"units\":\"1\",\"correlation_id\":null}";
    }

    static String batch(String batchId, List<String> events) {
        return "{\"batchId\":\"" + batchId + "\",\"events\":[" + String.join(",", events) + "]}";
    }

    static List<String> recipients(String campaign, int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            out.add(event(campaign, i, "DELIVRD", "000"));
        }
        return out;
    }

    private JsonNode events(String captureId, String query) {
        return getJson("/api/v1/webhooks/requests/" + captureId + "/events" + query);
    }

    // ------------------------------------------------------------------

    @Test
    void thousandRecipientBatchIsCapturedWholeAndEveryRecipientIsVerifiable() {
        List<String> evs = recipients(CAMPAIGN, 1, 1000);
        // original formatting kept: spaces and a line break inside the array
        String body = "{\"batchId\":\"17d787a3-7a53-406b-b4b8-59c6397bebd0\",\n  \"events\": [ "
                + String.join(",\n    ", evs) + " ]}";
        HttpResponse<String> r = postDlr(body, Map.of());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode ack = readJson(r.body());
        String captureId = ack.get("capture_id").asText();
        assertThat(ack.get("batch_id").asText()).isEqualTo("17d787a3-7a53-406b-b4b8-59c6397bebd0");
        assertThat(ack.get("events").asInt()).isEqualTo(1000);
        assertThat(ack.get("interpreted").asInt()).isEqualTo(1000);
        assertThat(ack.get("interpretation_status").asText()).isEqualTo("INTERPRETED");
        // judged per recipient: each of the 1000 recipients gets its first DLR
        assertThat(ack.get("applied").asInt()).isEqualTo(1000);

        // the whole request, byte for byte
        assertThat(get("/api/v1/webhooks/requests/" + captureId + "/raw").body()).isEqualTo(body);
        // one DLR per recipient, linked to the request
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events WHERE batch_capture_id = ?::uuid",
                Integer.class, captureId)).isEqualTo(1000);

        // events in their original order, each with its own text
        JsonNode page = events(captureId, "?offset=885&limit=2");
        assertThat(page.get("total").asInt()).isEqualTo(1000);
        assertThat(page.at("/items/0/index").asInt()).isEqualTo(885);
        assertThat(page.at("/items/0/message_id").asText()).isEqualTo(CAMPAIGN + ":886");
        assertThat(page.at("/items/0/raw_event").asText()).isEqualTo(evs.get(885));
        assertThat(page.at("/items/0/recipient").asText()).isEqualTo("918999615886");

        // every recipient is queryable on its own
        JsonNode one = getJson("/api/v1/dlr/" + CAMPAIGN + ":886");
        assertThat(one.get("received").asBoolean()).isTrue();
        assertThat(one.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(one.get("mobile").asText()).isEqualTo("918999615886");
        assertThat(get("/api/v1/dlr/" + CAMPAIGN + ":886/json").body()).isEqualTo(evs.get(885));
        JsonNode v = readJson(postDlr("/api/v1/dlr/verify", "{\"message_ids\":[\"" + CAMPAIGN + ":1\",\"" + CAMPAIGN
                + ":500\",\"" + CAMPAIGN + ":1000\"],\"expected_status\":\"DELIVERED\"}", Map.of()).body());
        assertThat(v.get("all_matched").asBoolean()).isTrue();
        assertThat(v.at("/results/2/dlr/mobile").asText()).isEqualTo("918999616000");

        // capture search by a recipient id
        assertThat(getJson("/api/v1/webhooks/requests?message_id=" + CAMPAIGN + ":886").get("items")).hasSize(1);
    }

    @Test
    void recipientWithoutItsOwnDlrIsNotReportedAsReceived() {
        postDlr(batch("b-partial", recipients("camp-p", 1, 3)), Map.of());
        JsonNode missing = getJson("/api/v1/dlr/camp-p:4");
        assertThat(missing.get("received").asBoolean()).isFalse();
        assertThat(missing.get("status").asText()).isEqualTo("PENDING");
        assertThat(get("/api/v1/dlr/camp-p:4/json").statusCode()).isEqualTo(404);
        JsonNode v = readJson(postDlr("/api/v1/dlr/verify",
                "{\"message_ids\":[\"camp-p:3\",\"camp-p:4\"],\"expected_status\":\"DELIVERED\"}", Map.of()).body());
        assertThat(v.at("/results/0/matched").asBoolean()).isTrue();
        assertThat(v.at("/results/1/received").asBoolean()).isFalse();
        assertThat(v.get("missing").asInt()).isEqualTo(1);
    }

    @Test
    void mixedEventsAreProcessedIndependently() {
        String failed = event("camp-m", 2, "UNDELIV", "001");
        String body = batch("b-mixed", List.of(
                event("camp-m", 1, "DELIVRD", "000"),      // 0 applied
                failed,                                     // 1 failed status, recognised
                "{\"foo\":\"bar\",\"msgId\":\"x\"}",        // 2 unknown structure
                "42",                                       // 3 not an object
                "{\"mobile\":\"919000000000\",\"status\":\"DELIVRD\",\"service\":\"T\"}",   // 4 recognised, no message id
                event("camp-m", 1, "DELIVRD", "000"),      // 5 duplicate of 0
                event("camp-m", 3, "WEIRDSTATE", "999"),   // 6 unexpected status: kept, never invented
                "null"));                                   // 7 null
        JsonNode ack = readJson(postDlr(body, Map.of()).body());
        assertThat(ack.get("events").asInt()).isEqualTo(8);
        assertThat(ack.get("interpretation_status").asText()).isEqualTo("PARTIAL");
        assertThat(ack.get("duplicate").asInt()).isEqualTo(1);
        assertThat(ack.get("malformed").asInt()).isEqualTo(2);

        JsonNode items = events(ack.get("capture_id").asText(), "").get("items");
        List<String> outcomes = new ArrayList<>();
        items.forEach(i -> outcomes.add(i.get("outcome").asText()));
        assertThat(outcomes.get(0)).isEqualTo("APPLIED");
        assertThat(outcomes.get(1)).isEqualTo("APPLIED");                     // recipient 2's own first DLR
        assertThat(outcomes.get(2)).isEqualTo("UNRECOGNIZED");
        assertThat(outcomes.get(3)).isEqualTo("MALFORMED");
        assertThat(outcomes.get(4)).isIn("REJECTED", "UNRECOGNIZED");
        assertThat(outcomes.get(5)).isEqualTo("DUPLICATE");
        assertThat(outcomes.get(7)).isEqualTo("MALFORMED");
        assertThat(items.get(2).get("raw_event").asText()).isEqualTo("{\"foo\":\"bar\",\"msgId\":\"x\"}");
        assertThat(items.get(2).get("normalized_status").isNull()).isTrue();          // no invented status
        assertThat(items.get(6).get("provider_status").asText()).isEqualTo("WEIRDSTATE");
        assertThat(items.get(6).get("normalized_status").asText()).isEqualTo("UNKNOWN");

        assertThat(getJson("/api/v1/dlr/camp-m:2").get("status").asText()).isEqualTo("FAILED");
        assertThat(get("/api/v1/dlr/camp-m:2/json").body()).isEqualTo(failed);
        assertThat(getJson("/api/v1/dlr/camp-m:1").get("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void batchShapes() {
        // no batchId: still a batch
        JsonNode noId = readJson(postDlr("{\"events\":[" + event("camp-s", 1, "DELIVRD", "000") + "]}", Map.of()).body());
        assertThat(noId.get("events").asInt()).isEqualTo(1);
        assertThat(noId.has("batch_id")).isTrue();
        assertThat(noId.get("batch_id").isNull()).isTrue();
        assertThat(getJson("/api/v1/dlr/camp-s:1").get("status").asText()).isEqualTo("DELIVERED");

        // empty array: captured, nothing to interpret
        JsonNode empty = readJson(postDlr("{\"batchId\":\"b-empty\",\"events\":[]}", Map.of()).body());
        assertThat(empty.get("events").asInt()).isZero();
        assertThat(empty.get("interpretation_status").asText()).isEqualTo("NO_DLR");

        // missing events / non-array events: not a batch, just an unrecognised capture
        HttpResponse<String> missing = postDlr("{\"batchId\":\"b-none\"}", Map.of());
        assertThat(missing.statusCode()).isEqualTo(200);
        assertThat(missing.headers().firstValue("X-Interpretation-Status")).contains("UNRECOGNIZED");
        HttpResponse<String> object = postDlr("{\"batchId\":\"b-obj\",\"events\":{\"a\":1}}", Map.of());
        assertThat(object.headers().firstValue("X-Interpretation-Status")).contains("UNRECOGNIZED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_requests WHERE batch_event_count IS NOT NULL",
                Integer.class)).isEqualTo(2);

        // a top-level array of DLRs is a batch too
        JsonNode arr = readJson(postDlr("[" + event("camp-s", 2, "DELIVRD", "000") + "," + event("camp-s", 3, "UNDELIV", "001")
                + "]", Map.of()).body());
        assertThat(arr.get("interpreted").asInt()).isEqualTo(2);
        assertThat(getJson("/api/v1/dlr/camp-s:3").get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void duplicateAndOverlappingBatches() {
        String first = batch("b-dup", recipients("camp-d", 1, 50));
        JsonNode a = readJson(postDlr(first, Map.of()).body());
        JsonNode b = readJson(postDlr(first, Map.of()).body());           // retry of the same batch
        assertThat(a.get("interpreted").asInt()).isEqualTo(50);
        assertThat(b.get("duplicate").asInt()).isEqualTo(50);
        assertThat(b.get("applied").asInt()).isZero();
        assertThat(a.get("capture_id").asText()).isNotEqualTo(b.get("capture_id").asText());   // both requests kept

        // same batchId, partially overlapping events: only the new ones are new
        JsonNode c = readJson(postDlr(batch("b-dup", recipients("camp-d", 41, 60)), Map.of()).body());
        assertThat(c.get("duplicate").asInt()).isEqualTo(10);
        assertThat(c.get("applied").asInt() + c.get("ignored").asInt()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events WHERE message_id = 'camp-d' "
                + "AND processing_status <> 'DUPLICATE'", Integer.class)).isEqualTo(60);
        assertThat(getJson("/api/v1/dlr/camp-d:60").get("received").asBoolean()).isTrue();
    }

    @Test
    void concurrentDeliveryOfTheSameBatchStoresEachEventOnce() throws Exception {
        String body = batch("b-conc", recipients("camp-c", 1, 300));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<HttpResponse<String>>> fs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                fs.add(pool.submit(() -> postDlr(body, Map.of())));
            }
            int duplicates = 0;
            for (Future<HttpResponse<String>> f : fs) {
                HttpResponse<String> r = f.get();
                assertThat(r.statusCode()).isEqualTo(200);
                duplicates += readJson(r.body()).get("duplicate").asInt();
            }
            assertThat(duplicates).isEqualTo(900);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events WHERE message_id = 'camp-c' "
                + "AND processing_status <> 'DUPLICATE'", Integer.class)).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_batch_events", Integer.class)).isEqualTo(1200);
    }

    @Test
    void databaseErrorOnOneEventDoesNotLoseItsSiblingsOrTheRequest() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_fail_one() RETURNS trigger AS $$
                BEGIN
                  IF NEW.provider_message_id = 'camp-f:2' THEN RAISE EXCEPTION 'simulated failure'; END IF;
                  RETURN NEW;
                END; $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER test_fail_one BEFORE INSERT ON dlr_events FOR EACH ROW EXECUTE FUNCTION test_fail_one()");
        try {
            String body = batch("b-fail", recipients("camp-f", 1, 5));
            HttpResponse<String> r = postDlr(body, Map.of());
            assertThat(r.statusCode()).isEqualTo(200);
            JsonNode ack = readJson(r.body());
            assertThat(ack.get("failed").asInt()).isEqualTo(1);
            assertThat(ack.get("interpreted").asInt()).isEqualTo(4);
            assertThat(ack.get("interpretation_status").asText()).isEqualTo("PARTIAL");
            String cid = ack.get("capture_id").asText();
            JsonNode failed = events(cid, "?outcome=ERROR").get("items");
            assertThat(failed).hasSize(1);
            assertThat(failed.at("/0/message_id").asText()).isEqualTo("camp-f:2");
            assertThat(failed.at("/0/reason").asText()).contains("simulated failure");
            assertThat(get("/api/v1/webhooks/requests/" + cid + "/raw").body()).isEqualTo(body);
            assertThat(getJson("/api/v1/dlr/camp-f:5").get("received").asBoolean()).isTrue();
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_one ON dlr_events");
            jdbc.execute("DROP FUNCTION test_fail_one()");
        }
    }

    @Test
    void liveFeedShowsOneRowPerBulkRequest() {
        JsonNode before = getJson("/api/v1/dlr/live/feed");
        postDlr(batch("b-feed", recipients("camp-l", 1, 20)), Map.of());
        JsonNode feed = getJson("/api/v1/dlr/live/feed");
        assertThat(feed.get("items")).hasSize(before.get("items").size() + 1);
        JsonNode row = feed.at("/items/0");
        assertThat(row.get("kind").asText()).isEqualTo("BATCH");
        assertThat(row.get("batch_id").asText()).isEqualTo("b-feed");
        assertThat(row.get("event_count").asInt()).isEqualTo(20);
        assertThat(row.at("/batch_summary/interpreted").asInt()).isEqualTo(20);
        long cursor = feed.at("/cursor/request").asLong();
        assertThat(getJson("/api/v1/dlr/live/feed?after_status=" + feed.at("/cursor/status").asLong()
                + "&after_billing=999999&after_click=999999&after_request=" + cursor).get("items")).isEmpty();
        // stats still count every recipient DLR
        assertThat(getJson("/api/v1/dlr/live/stats?minutes=60").at("/totals/dlrs").asInt()).isGreaterThanOrEqualTo(20);
    }
}
