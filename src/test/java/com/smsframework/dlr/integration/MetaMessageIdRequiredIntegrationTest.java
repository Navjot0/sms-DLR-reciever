package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Default behaviour: a Meta status is keyed by the platform's message_id and rejected without one. */
class MetaMessageIdRequiredIntegrationTest extends AbstractIntegrationTest {

    @Test
    void statusWithoutMessageIdIsRejectedButAcknowledgedAndHiddenFromTheUi() {
        HttpResponse<String> r = postDlr(MetaWhatsAppIntegrationTest.webhook(
                """
                {"id":"wamid.WITH","message_id":"11111111-aaaa-bbbb-cccc-000000000001","status":"delivered",
                 "timestamp":"1791213334","recipient_id":"918999620083"}""",
                """
                {"id":"wamid.WITHOUT","status":"read","timestamp":"1791215008","recipient_id":"918736830403"}"""),
                Map.of());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode ack = readJson(r.body());
        assertThat(ack.get("applied").asInt()).isEqualTo(1);
        assertThat(ack.get("rejected").asInt()).isEqualTo(1);

        assertThat(getJson("/api/v1/dlr/11111111-aaaa-bbbb-cccc-000000000001").get("status").asText())
                .isEqualTo("DELIVERED");
        assertThat(getJson("/api/v1/dlr/wamid.WITHOUT").get("received").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT rejection_reason FROM dlr_events WHERE processing_status = 'REJECTED'",
                String.class)).contains("message_id is missing");

        JsonNode feed = getJson("/api/v1/dlr/live/feed");
        assertThat(feed.get("items")).hasSize(1);
        assertThat(feed.at("/items/0/message_id").asText()).isEqualTo("11111111-aaaa-bbbb-cccc-000000000001");
    }
}
