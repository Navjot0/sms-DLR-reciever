package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Bulk Received + Individual Messages views and the record_state of the verification APIs. */
class IndividualMessagesIntegrationTest extends AbstractIntegrationTest {

    /** A bulk event in the spec's plain format (message ids without a ":<n>" suffix). */
    static String event(String id, String mobile, String status, String code) {
        return "{\"message_id\":\"" + id + "\",\"mobile\":\"" + mobile + "\",\"status\":\"" + status + "\",\"code\":\"" + code
                + "\",\"service\":\"T\",\"sender\":\"DUMMY\",\"custom_field\":{\"x\":[1,2]}}";
    }

    private JsonNode messages(String query) {
        return getJson("/api/v1/dlr/messages" + query);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void singleDlrIsOneIndividualMessage() {
        postDlr(TestPayloads.defaultSms("single-1", "DELIVRD"), Map.of("X-DLR-Source", "DEFAULT_SMS"));
        JsonNode r = messages("?message_id=single-1");
        assertThat(r.get("items")).hasSize(1);
        JsonNode m = r.at("/items/0");
        assertThat(m.get("source_type").asText()).isEqualTo("SINGLE");
        assertThat(m.get("status").asText()).isEqualTo("DELIVRD");
        assertThat(m.get("normalized_status").asText()).isEqualTo("DELIVERED");
        assertThat(m.get("processing_status").asText()).isEqualTo("APPLIED");
        assertThat(m.get("capture_id").isNull()).isFalse();             // linked to its captured request
        assertThat(m.get("batch_id").isNull()).isTrue();
        assertThat(getJson("/api/v1/dlr/single-1").get("record_state").asText()).isEqualTo("PARSED");
    }

    @Test
    void bulkOfHundredGivesOneBulkCaptureAndHundredMessages() {
        List<String> evs = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            evs.add(event(String.format("msg-%03d", i), "9199990" + String.format("%05d", i), i % 10 == 0 ? "FAILED" : "DELIVRD",
                    i % 10 == 0 ? "001" : "000"));
        }
        String body = "{\"batchId\":\"batch-123\",\"events\":[" + String.join(",", evs) + "]}";
        JsonNode ack = readJson(postDlr(body, Map.of()).body());
        assertThat(ack.get("events").asInt()).isEqualTo(100);

        // Bulk Received: exactly one request, with its counts
        JsonNode bulk = getJson("/api/v1/webhooks/requests?kind=bulk");
        assertThat(bulk.get("items")).hasSize(1);
        assertThat(bulk.at("/items/0/batch_id").asText()).isEqualTo("batch-123");
        assertThat(bulk.at("/items/0/batch_event_count").asInt()).isEqualTo(100);
        assertThat(bulk.at("/items/0/processed_event_count").asInt()).isEqualTo(100);
        assertThat(bulk.at("/items/0/failed_event_count").asInt()).isZero();
        assertThat(getJson("/api/v1/webhooks/requests?kind=single").get("items")).isEmpty();

        // Individual Messages: 100 rows for the batch, paged server-side
        JsonNode page1 = messages("?batch_id=batch-123&limit=60");
        assertThat(page1.get("items")).hasSize(60);
        assertThat(page1.get("has_more").asBoolean()).isTrue();
        JsonNode page2 = messages("?batch_id=batch-123&limit=60&offset=60");
        assertThat(page2.get("items")).hasSize(40);
        assertThat(page2.get("has_more").asBoolean()).isFalse();

        // exact search returns that one event, even though 100 share the batch
        JsonNode one = messages("?message_id=msg-042");
        assertThat(one.get("items")).hasSize(1);
        JsonNode m = one.at("/items/0");
        assertThat(m.get("source_type").asText()).isEqualTo("BULK");
        assertThat(m.get("event_index").asInt()).isEqualTo(41);
        assertThat(m.get("batch_id").asText()).isEqualTo("batch-123");
        assertThat(m.get("capture_id").asText()).isEqualTo(ack.get("capture_id").asText());
        assertThat(m.get("raw_event").asText()).isEqualTo(evs.get(41));         // unknown fields kept, exact text

        // different statuses kept independently
        assertThat(messages("?batch_id=batch-123&status=FAILED&limit=200").get("items")).hasSize(10);
        assertThat(getJson("/api/v1/dlr/msg-010").get("status").asText()).isEqualTo("FAILED");
        assertThat(getJson("/api/v1/dlr/msg-010").get("status_code").asText()).isEqualTo("001");
        assertThat(getJson("/api/v1/dlr/msg-011").get("status").asText()).isEqualTo("DELIVERED");

        // partial id, mobile, source type and time range
        assertThat(messages("?message_id_like=msg-09&limit=200").get("items")).hasSize(10);
        assertThat(messages("?mobile=919999000042").get("items")).hasSize(1);
        assertThat(messages("?source_type=BULK&limit=200").get("items")).hasSize(100);
        assertThat(messages("?source_type=SINGLE").get("items")).isEmpty();
        assertThat(messages("?from=" + enc("2000-01-01T00:00:00Z") + "&to=" + enc("2099-01-01T00:00:00Z") + "&limit=200")
                .get("items")).hasSize(100);
        assertThat(messages("?from=" + enc("2099-01-01T00:00:00Z")).get("items")).isEmpty();

        // verification of every message of the batch
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            ids.append(i > 1 ? "," : "").append("\"msg-").append(String.format("%03d", i)).append('"');
        }
        JsonNode v = readJson(postDlr("/api/v1/dlr/verify", "{\"message_ids\":[" + ids + "]}", Map.of()).body());
        assertThat(v.get("received").asInt()).isEqualTo(100);
        assertThat(v.get("delivered").asInt()).isEqualTo(90);
        assertThat(v.get("failed").asInt()).isEqualTo(10);
        assertThat(v.at("/results/41/record_state").asText()).isEqualTo("PARSED");
    }

    @Test
    void malformedAndUncorrelatedEventsStayVisible() {
        String body = "{\"batchId\":\"batch-bad\",\"events\":[" + event("ok-1", "919000000001", "DELIVRD", "000")
                + ",\"not an object\",{\"mobile\":\"919000000002\",\"note\":\"no id\"}," + event("ok-2", "919000000003", "DELIVRD", "000")
                + "]}";
        JsonNode ack = readJson(postDlr(body, Map.of()).body());
        assertThat(ack.get("interpreted").asInt()).isEqualTo(2);
        JsonNode bulk = getJson("/api/v1/webhooks/requests?kind=bulk&batch_id=batch-bad").at("/items/0");
        assertThat(bulk.get("processed_event_count").asInt()).isEqualTo(2);
        assertThat(bulk.get("failed_event_count").asInt()).isEqualTo(2);

        JsonNode all = messages("?batch_id=batch-bad");
        assertThat(all.get("items")).hasSize(4);          // nothing silently dropped
        List<String> states = new ArrayList<>();
        all.get("items").forEach(i -> states.add(i.get("processing_status").asText()));
        assertThat(states).contains("APPLIED", "MALFORMED", "UNRECOGNIZED");
        JsonNode uncorrelated = messages("?batch_id=batch-bad&processing_status=UNRECOGNIZED").at("/items/0");
        assertThat(uncorrelated.get("message_id").isNull()).isTrue();
        assertThat(uncorrelated.get("raw_event").asText()).isEqualTo("{\"mobile\":\"919000000002\",\"note\":\"no id\"}");
        assertThat(getJson("/api/v1/dlr/ok-2").get("received").asBoolean()).isTrue();
    }

    @Test
    void sameBatchIdWithADifferentBodyIsNotDiscarded() {
        postDlr("{\"batchId\":\"batch-rep\",\"events\":[" + event("rep-1", "919000000001", "DELIVRD", "000") + "]}", Map.of());
        postDlr("{\"batchId\":\"batch-rep\",\"events\":[" + event("rep-2", "919000000002", "DELIVRD", "000") + "]}", Map.of());
        postDlr("{\"batchId\":\"batch-rep\",\"events\":[" + event("rep-1", "919000000001", "DELIVRD", "000") + "]}", Map.of());   // retry
        assertThat(getJson("/api/v1/webhooks/requests?kind=bulk&batch_id=batch-rep").get("items")).hasSize(3);  // history kept
        assertThat(getJson("/api/v1/dlr/rep-2").get("received").asBoolean()).isTrue();
        // the retry is one more row, marked DUPLICATE; the logical record is counted once
        JsonNode rep1 = messages("?message_id=rep-1");
        assertThat(rep1.get("items")).hasSize(2);
        assertThat(rep1.at("/items/0/processing_status").asText()).isEqualTo("DUPLICATE");
        JsonNode v = readJson(postDlr("/api/v1/dlr/verify", "{\"message_ids\":[\"rep-1\",\"rep-2\"]}", Map.of()).body());
        assertThat(v.get("delivered").asInt()).isEqualTo(2);
        assertThat(getJson("/api/v1/dlr/rep-1").get("duplicate_count").asInt()).isEqualTo(1);
    }

    @Test
    void recordStateSeparatesWhatWasReceivedFromTheDeliveryStatus() {
        // invalid: recognised DEFAULT_SMS format without a mobile
        postDlr("{\"payload\":{\"message_id\":\"inv-1\",\"status\":\"DELIVRD\"}}", Map.of("X-DLR-Source", "DEFAULT_SMS"));
        // captured, not a DLR the receiver understands
        postDlr("{\"msgId\":\"cap-1\",\"deliveryStatus\":\"DELIVERED\"}", Map.of());
        JsonNode inv = getJson("/api/v1/dlr/inv-1");
        assertThat(inv.get("received").asBoolean()).isFalse();
        assertThat(inv.get("status").asText()).isEqualTo("PENDING");
        assertThat(inv.get("record_state").asText()).isEqualTo("INVALID");
        assertThat(getJson("/api/v1/dlr/cap-1").get("record_state").asText()).isEqualTo("CAPTURED_NOT_INTERPRETED");
        assertThat(getJson("/api/v1/dlr/none-1").get("record_state").asText()).isEqualTo("NOT_FOUND");

        JsonNode v = readJson(postDlr("/api/v1/dlr/verify",
                "{\"message_ids\":[\"inv-1\",\"cap-1\",\"none-1\"],\"expected_status\":\"DELIVERED\"}", Map.of()).body());
        assertThat(v.at("/results/0/record_state").asText()).isEqualTo("INVALID");
        assertThat(v.at("/results/1/record_state").asText()).isEqualTo("CAPTURED_NOT_INTERPRETED");
        assertThat(v.at("/results/2/record_state").asText()).isEqualTo("NOT_FOUND");
        assertThat(v.get("all_matched").asBoolean()).isFalse();

        // the captured-but-unknown single callback is listed as an individual message with its id
        JsonNode m = messages("?message_id=cap-1").at("/items/0");
        assertThat(m.get("source_type").asText()).isEqualTo("SINGLE");
        assertThat(m.get("processing_status").asText()).isEqualTo("UNRECOGNIZED");
        assertThat(m.get("normalized_status").isNull()).isTrue();
    }
}
