package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live-gateway format (flat JSON, no X-DLR-Source), tolerant URLs ("//api/..."), and reprocessing of
 * callbacks that were rejected before a fix was deployed.
 */
class DlrFlatPayloadAndReprocessIntegrationTest extends AbstractIntegrationTest {

    private static final String MSG = "664f1ac5-3f1b-4a2b-b9e3-08c89b13b8a4";

    @Test
    void flatDefaultSmsPayloadWithoutSourceHeaderIsApplied() {
        HttpResponse<String> post = postDlr(TestPayloads.DEFAULT_SMS_FLAT_EXAMPLE, Map.of());
        assertThat(post.statusCode()).isEqualTo(200);
        assertThat(readJson(post.body()).get("source").asText()).isEqualTo("DEFAULT_SMS");

        JsonNode r = getJson("/api/v1/dlr/" + MSG);
        assertThat(r.get("received").asBoolean()).isTrue();
        assertThat(r.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(r.get("provider_status").asText()).isEqualTo("DELIVRD");
        assertThat(r.get("mobile").asText()).isEqualTo("918727973019");
        assertThat(r.get("units").asInt()).isEqualTo(2);
        assertThat(r.get("received_at").asText()).isEqualTo("2026-09-25T17:43:10");
    }

    @Test
    void doubleSlashInUrlStillResolves() {
        postDlr("//api/v1/dlr/receive", TestPayloads.DEFAULT_SMS_FLAT_EXAMPLE, Map.of());
        HttpResponse<String> r = get("//api/v1/dlr/" + MSG);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(readJson(r.body()).get("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void unrecognisedPayloadKeepsItsMessageIdForDiagnostics() {
        postDlr("{\"message_id\":\"odd-1\",\"state\":\"whatever\"}", Map.of());
        JsonNode r = getJson("/api/v1/dlr/odd-1");
        assertThat(r.get("received").asBoolean()).isFalse();
        assertThat(r.get("rejected_events").asInt()).isEqualTo(1);
        assertThat(r.get("last_rejection_reason").asText()).startsWith("unable to determine DLR source");
    }

    @Test
    void previouslyRejectedCallbackCanBeReprocessed() {
        // Simulate the row an older version stored: flat payload, source UNKNOWN, REJECTED.
        Long id = jdbc.queryForObject("""
                INSERT INTO dlr_events (source, raw_payload, processing_status, rejection_reason)
                VALUES ('UNKNOWN', CAST(? AS jsonb), 'REJECTED',
                        'unable to determine DLR source from headers, query parameters or payload structure')
                RETURNING id""", Long.class, TestPayloads.DEFAULT_SMS_FLAT_EXAMPLE);
        assertThat(getJson("/api/v1/dlr/" + MSG).get("received").asBoolean()).isFalse();

        HttpResponse<String> r = postDlr("/api/v1/dlr/events/" + id + "/reprocess", "", Map.of());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode item = readJson(r.body());
        assertThat(item.get("processing_status").asText()).isEqualTo("APPLIED");
        assertThat(item.get("message_id").asText()).isEqualTo(MSG);

        assertThat(getJson("/api/v1/dlr/" + MSG).get("status").asText()).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT processing_note FROM dlr_events WHERE id = ?", String.class, id))
                .startsWith("reprocessed -> APPLIED");

        // Bulk reprocess skips rows already reprocessed; reprocessing the same row again is a DUPLICATE.
        assertThat(readJson(postDlr("/api/v1/dlr/events/rejected/reprocess", "", Map.of()).body())
                .get("attempted").asInt()).isZero();
        assertThat(readJson(postDlr("/api/v1/dlr/events/" + id + "/reprocess", "", Map.of()).body())
                .get("processing_status").asText()).isEqualTo("DUPLICATE");
    }

    @Test
    void bulkReprocessHandlesMixedRows() {
        postDlr("{broken", Map.of());                                   // not reprocessable
        postDlr("{\"payload\":{\"message_id\":\"still-bad\"}}", Map.of("X-DLR-Source", "DEFAULT_SMS")); // still invalid
        jdbc.update("""
                INSERT INTO dlr_events (source, raw_payload, processing_status, rejection_reason)
                VALUES ('UNKNOWN', CAST(? AS jsonb), 'REJECTED', 'old version')""", TestPayloads.DEFAULT_SMS_FLAT_EXAMPLE);

        JsonNode r = readJson(postDlr("/api/v1/dlr/events/rejected/reprocess?limit=10", "", Map.of()).body());
        assertThat(r.get("attempted").asInt()).isEqualTo(3);
        assertThat(r.get("skipped").asInt()).isEqualTo(1);
        assertThat(r.get("still_rejected").asInt()).isEqualTo(1);
        assertThat(r.get("applied").asInt()).isEqualTo(1);

        // the retry that was rejected again is not picked up in a loop
        assertThat(readJson(postDlr("/api/v1/dlr/events/rejected/reprocess?limit=10", "", Map.of()).body())
                .get("attempted").asInt()).isZero();
    }

    @Test
    void onlyRejectedEventsCanBeReprocessed() {
        postDlr(TestPayloads.DEFAULT_SMS_FLAT_EXAMPLE, Map.of());
        Long applied = jdbc.queryForObject("SELECT id FROM dlr_events WHERE processing_status='APPLIED'", Long.class);
        assertThat(postDlr("/api/v1/dlr/events/" + applied + "/reprocess", "", Map.of()).statusCode()).isEqualTo(400);
        assertThat(postDlr("/api/v1/dlr/events/999999/reprocess", "", Map.of()).statusCode()).isEqualTo(404);
    }
}
