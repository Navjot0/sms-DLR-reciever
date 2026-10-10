package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** RCS operator DLRs (Jio, Dotgo, Vi, Airtel) end to end, detected from the payload with no source header. */
class RcsDlrIntegrationTest extends AbstractIntegrationTest {

    static String jio(String id, String eventType, String time) {
        return """
                {"entityType":"STATUS_EVENT","entity":{"eventType":"%s","messageId":"%s","sendTime":"%s"},
                 "botId":"bot-7","userPhoneNumber":"919000000001"}""".formatted(eventType, id, time);
    }

    @Test
    void jioSentDeliveredReadProgression() {
        assertThat(postDlr(jio("jio-int-1", "MESSAGE_SENT", "2026-07-23T10:15:30Z"), Map.of()).statusCode()).isEqualTo(200);
        postDlr(jio("jio-int-1", "MESSAGE_DELIVERED", "2026-07-23T10:15:31Z"), Map.of());
        postDlr(jio("jio-int-1", "MESSAGE_READ", "2026-07-23T10:15:32Z"), Map.of());

        JsonNode s = getJson("/api/v1/dlr/jio-int-1");
        assertThat(s.get("source").asText()).isEqualTo("RCS");
        assertThat(s.get("status").asText()).isEqualTo("READ");
        assertThat(s.get("provider_status").asText()).isEqualTo("MESSAGE_READ");
        assertThat(s.get("mobile").asText()).isEqualTo("919000000001");
        assertThat(s.get("event_count").asInt()).isEqualTo(3);
    }

    @Test
    void everyOperatorFormatIsAcceptedAndCountedInTheRcsCategory() {
        postDlr(jio("jio-int-2", "MESSAGE_DELIVERED", "2026-07-23T10:15:31Z"), Map.of());
        postDlr("""
                {"message":{"data":"eyJtZXNzYWdlSWQiOiAiZGctaW50LTEiLCAic2VuZGVyUGhvbmVOdW1iZXIiOiAiOTE5MDAwMDAwMDAyIiwgImV2ZW50VHlwZSI6ICJSRUFEIiwgInNlbmRUaW1lIjogIjIwMjYtMDctMjNUMTA6MTU6MzJaIn0=","attributes":{"event_type":"READ","business_id":"bot-9"}}}""", Map.of());
        postDlr("""
                {"event":"message_status","RCSMessage":{"msgId":"vi-int-1","status":"sent","timestamp":"2026-07-23T10:15:30Z"},
                 "messageContact":{"userContact":"+919000000003"}}""", Map.of());
        postDlr("""
                {"messageId":"at-int-1","eventType":"FAILED","sendTime":"2026-07-23T10:15:30Z","agentId":"agent-3",
                 "error":{"message":"Message delivery failed","code":"NETWORK_FAILURE"}}""", Map.of("X-DLR-Source", "AIRTEL"));

        assertThat(getJson("/api/v1/dlr/jio-int-2").get("status").asText()).isEqualTo("DELIVERED");
        assertThat(getJson("/api/v1/dlr/dg-int-1").get("status").asText()).isEqualTo("READ");
        assertThat(getJson("/api/v1/dlr/vi-int-1").get("status").asText()).isEqualTo("SENT");
        JsonNode at = getJson("/api/v1/dlr/at-int-1");
        assertThat(at.get("status").asText()).isEqualTo("FAILED");
        assertThat(at.get("status_code").asText()).isEqualTo("NETWORK_FAILURE");
        assertThat(at.get("source").asText()).isEqualTo("RCS");

        JsonNode stats = getJson("/api/v1/dlr/live/stats?minutes=60");
        JsonNode rcs = null;
        for (JsonNode c : stats.get("categories")) {
            if ("RCS".equals(c.get("key").asText())) {
                rcs = c;
            }
        }
        assertThat(rcs).isNotNull();
        assertThat(rcs.get("dlrs").asLong()).isEqualTo(4);          // messages
        assertThat(rcs.get("delivered").asLong()).isEqualTo(2);     // delivered or read
        assertThat(rcs.get("read").asLong()).isEqualTo(1);
        assertThat(rcs.get("failed").asLong()).isEqualTo(1);
        assertThat(rcs.get("pending").asLong()).isEqualTo(1);

        JsonNode feed = getJson("/api/v1/dlr/live/feed");
        assertThat(feed.get("items")).hasSize(4);
        assertThat(feed.at("/items/0/service").asText()).isEqualTo("AIRTEL");
    }

    @Test
    void unrecognisedPayloadWithRcsSourceIsRejected() {
        assertThat(postDlr("{\"hello\":\"world\"}", Map.of("X-DLR-Source", "RCS")).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT rejection_reason FROM dlr_events", String.class))
                .contains("Jio, Dotgo, Vi or Airtel");
    }
}
