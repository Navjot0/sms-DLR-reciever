package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multipart SMS as sent by the live gateway for UI campaigns: the status DLR carries "<id>:1" and the billing
 * DLR carries "<id>:1".."<id>:4". Both must correlate to the same message.
 */
class DlrMultipartIntegrationTest extends AbstractIntegrationTest {

    private static final String MSG = "c9b2e601-16f9-4448-b12c-d823c1e5e046";

    private static String flatStatus(String id, String status) {
        return """
                {"code":"000","units":"1","mobile":"919876543210","sender":"DUMMY","status":"%s","service":"T",
                 "submit_at":"2026-09-25 22:15:51","message_id":"%s","template_id":"147411722932",
                 "correlation_id":null,"dlr_received_at":"2026-09-25 22:15:51"}""".formatted(status, id);
    }

    private static final String BILLING_4_PARTS = """
            {"event_type":"billing","events":[
             {"transaction_type":"debit","message_id":"%1$s:1","product":"SMS Promotional","units":1,"sale_price":2,"currency":"INR","surcharge":0,"total_amount":2},
             {"transaction_type":"debit","message_id":"%1$s:2","product":"SMS Promotional","units":1,"sale_price":2,"currency":"INR","surcharge":0,"total_amount":2},
             {"transaction_type":"debit","message_id":"%1$s:3","product":"SMS Promotional","units":1,"sale_price":2,"currency":"INR","surcharge":0,"total_amount":2},
             {"transaction_type":"debit","message_id":"%1$s:4","product":"SMS Promotional","units":1,"sale_price":2,"currency":"INR","surcharge":0,"total_amount":2}]}"""
            .formatted(MSG);

    @Test
    void statusDlrWithPartSuffixCorrelatesWithMultipartBilling() {
        assertThat(postDlr(flatStatus(MSG + ":1", "DELIVRD"), Map.of()).statusCode()).isEqualTo(200);
        assertThat(postDlr(BILLING_4_PARTS, Map.of()).statusCode()).isEqualTo(200);

        Map<String, Object> ev = jdbc.queryForMap("SELECT message_id, provider_message_id, part_number FROM dlr_events");
        assertThat(ev.get("message_id")).isEqualTo(MSG);
        assertThat(ev.get("provider_message_id")).isEqualTo(MSG + ":1");
        assertThat(ev.get("part_number")).isEqualTo(1);

        for (String lookup : new String[]{MSG, MSG + ":1"}) {
            JsonNode r = getJson("/api/v1/dlr/" + lookup);
            assertThat(r.get("message_id").asText()).as(lookup).isEqualTo(MSG);
            assertThat(r.get("received").asBoolean()).isTrue();
            assertThat(r.get("status").asText()).isEqualTo("DELIVERED");
            assertThat(r.at("/billing/billed").asBoolean()).as(lookup).isTrue();
            assertThat(r.at("/billing/parts").asInt()).isEqualTo(4);
            assertThat(r.at("/billing/units").asInt()).isEqualTo(4);
            assertThat(r.at("/billing/total_amount").decimalValue()).isEqualByComparingTo("8");
            assertThat(r.at("/parts/0/part").asInt()).isEqualTo(1);
            assertThat(r.at("/parts/0/status").asText()).isEqualTo("DELIVERED");
        }
        assertThat(getJson("/api/v1/dlr/" + MSG + ":1").get("requested_id").asText()).isEqualTo(MSG + ":1");
        assertThat(getJson("/api/v1/dlr/" + MSG).has("requested_id")).isFalse();

        JsonNode v = readJson(postDlr("/api/v1/dlr/verify", "{\"message_ids\":[\"" + MSG + ":1\"],"
                + "\"expected_status\":\"DELIVERED\",\"require_billing\":true}", Map.of()).body());
        assertThat(v.get("all_matched").asBoolean()).isTrue();
        assertThat(v.at("/results/0/message_id").asText()).isEqualTo(MSG + ":1");
        assertThat(v.at("/results/0/billed_units").asInt()).isEqualTo(4);
    }

    @Test
    void eachPartIsRecordedAndSamePartRepeatedIsADuplicate() {
        postDlr(flatStatus(MSG + ":1", "DELIVRD"), Map.of());
        postDlr(flatStatus(MSG + ":2", "DELIVRD"), Map.of());
        postDlr(flatStatus(MSG + ":2", "DELIVRD"), Map.of());

        assertThat(jdbc.queryForList("SELECT processing_status FROM dlr_events ORDER BY id", String.class))
                .containsExactly("APPLIED", "IGNORED", "DUPLICATE");
        JsonNode r = getJson("/api/v1/dlr/" + MSG);
        assertThat(r.get("parts")).hasSize(2);
        assertThat(r.at("/parts/1/provider_message_id").asText()).isEqualTo(MSG + ":2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_message_status", Integer.class)).isEqualTo(1);
    }

    @Test
    void migrationBackfillMovesOldSuffixedRowsToTheBaseId() throws Exception {
        // Rows as stored by the previous version (message_id kept the ":1" suffix)
        Long eventId = jdbc.queryForObject("""
                INSERT INTO dlr_events (source, message_id, provider_status, normalized_status, raw_payload, processing_status)
                VALUES ('DEFAULT_SMS', ?, 'DELIVRD', 'DELIVERED', '{}'::jsonb, 'APPLIED') RETURNING id""",
                Long.class, MSG + ":1");
        jdbc.update("""
                INSERT INTO dlr_message_status (message_id, source, provider_status, normalized_status, last_event_id)
                VALUES (?, 'DEFAULT_SMS', 'DELIVRD', 'DELIVERED', ?)""", MSG + ":1", eventId);

        String sql = new ClassPathResource("db/migration/V3__multipart_message_ids.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (String stmt : sql.replaceAll("(?m)^--.*$", "").split(";")) {
            if (stmt.trim().toUpperCase().startsWith("UPDATE")) {
                jdbc.update(stmt);
            }
        }

        assertThat(jdbc.queryForObject("SELECT message_id FROM dlr_message_status", String.class)).isEqualTo(MSG);
        assertThat(jdbc.queryForMap("SELECT message_id, provider_message_id, part_number FROM dlr_events"))
                .containsEntry("message_id", MSG).containsEntry("provider_message_id", MSG + ":1").containsEntry("part_number", 1);
        postDlr(BILLING_4_PARTS, Map.of());
        assertThat(getJson("/api/v1/dlr/" + MSG + ":1").at("/billing/billed").asBoolean()).isTrue();
    }

    @Test
    void idsWithoutNumericSuffixAreUntouched() {
        postDlr(TestPayloads.webEngage("11111222233333447982", "sms_sent", "0"), Map.of("X-DLR-Source", "WEBENGAGE"));
        JsonNode r = getJson("/api/v1/dlr/11111222233333447982");
        assertThat(r.get("message_id").asText()).isEqualTo("11111222233333447982");
        assertThat(r.has("parts")).isFalse();
    }
}
