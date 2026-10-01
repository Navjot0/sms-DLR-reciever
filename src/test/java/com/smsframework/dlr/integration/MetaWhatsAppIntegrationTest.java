package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Meta (WhatsApp Cloud API) status webhooks end to end against PostgreSQL. */
@TestPropertySource(properties = "dlr.meta.verify-token=test-verify-token")
class MetaWhatsAppIntegrationTest extends AbstractIntegrationTest {

    static String webhook(String... statuses) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"WABA1","changes":[{"field":"messages","value":{
                  "messaging_product":"whatsapp","metadata":{"display_phone_number":"15550001111","phone_number_id":"PN1"},
                  "statuses":[%s]}}]}]}""".formatted(String.join(",", statuses));
    }

    static String status(String wamid, String status, long ts, String recipient) {
        return """
                {"id":"%s","status":"%s","timestamp":"%d","recipient_id":"%s",
                 "pricing":{"billable":true,"pricing_model":"CBP","category":"utility"}}""".formatted(wamid, status, ts, recipient);
    }

    @Test
    void webhookWithSeveralStatusesIsStoredPerStatusAndAlwaysAcked200() {
        HttpResponse<String> r = postDlr(webhook(
                status("wamid.A", "sent", 1727780000, "919000000001"),
                status("wamid.B", "sent", 1727780000, "919000000002"),
                """
                {"id":"wamid.C","status":"failed","timestamp":"1727780001","recipient_id":"919000000003",
                 "errors":[{"code":131026,"title":"Message undeliverable"}]}""",
                "{\"status\":\"sent\"}"), Map.of());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode ack = readJson(r.body());
        assertThat(ack.get("source").asText()).isEqualTo("META");
        assertThat(ack.get("statuses").asInt()).isEqualTo(4);
        assertThat(ack.get("applied").asInt()).isEqualTo(3);
        assertThat(ack.get("rejected").asInt()).isEqualTo(1);

        assertThat(getJson("/api/v1/dlr/wamid.A").get("status").asText()).isEqualTo("SENT");
        JsonNode c = getJson("/api/v1/dlr/wamid.C");
        assertThat(c.get("status").asText()).isEqualTo("FAILED");
        assertThat(c.get("status_code").asText()).isEqualTo("131026");
        assertThat(c.get("source").asText()).isEqualTo("META");
        assertThat(c.get("mobile").asText()).isEqualTo("919000000003");
    }

    @Test
    void sentDeliveredReadProgressAndOutOfOrderIsIgnored() {
        postDlr(webhook(status("wamid.R", "sent", 1727780000, "919000000009")), Map.of());
        postDlr(webhook(status("wamid.R", "read", 1727780010, "919000000009")), Map.of());
        postDlr(webhook(status("wamid.R", "delivered", 1727780005, "919000000009")), Map.of());   // late
        postDlr(webhook(status("wamid.R", "read", 1727780010, "919000000009")), Map.of());        // duplicate

        JsonNode s = getJson("/api/v1/dlr/wamid.R?include_events=true");
        assertThat(s.get("status").asText()).isEqualTo("READ");
        assertThat(s.get("provider_status").asText()).isEqualTo("read");
        assertThat(s.get("event_count").asInt()).isEqualTo(3);
        assertThat(s.get("duplicate_count").asInt()).isEqualTo(1);

        // read satisfies an expected DELIVERED (read implies delivered)
        JsonNode v = readJson(postDlr("/api/v1/dlr/verify",
                "{\"message_ids\":[\"wamid.R\"],\"expected_status\":\"DELIVERED\"}", Map.of()).body());
        assertThat(v.get("all_matched").asBoolean()).isTrue();
        assertThat(v.get("delivered").asInt()).isEqualTo(1);
    }

    @Test
    void inboundMessageWebhookIsAcknowledgedWithoutStoring() {
        HttpResponse<String> r = postDlr("""
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                  "messaging_product":"whatsapp","metadata":{},"messages":[{"id":"wamid.in","type":"text"}]}}]}]}""",
                Map.of());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(readJson(r.body()).get("note").asText()).contains("no statuses");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events", Long.class)).isZero();
    }

    @Test
    void metaCallbackUrlVerification() {
        HttpResponse<String> ok = get("/api/v1/dlr/receive?hub.mode=subscribe&hub.verify_token=test-verify-token&hub.challenge=12345");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("12345");
        assertThat(get("/api/v1/dlr/receive?hub.mode=subscribe&hub.verify_token=wrong&hub.challenge=1").statusCode())
                .isEqualTo(403);
    }

    @Test
    void liveStatsHaveAMetaCategory() {
        postDlr(webhook(status("wamid.S1", "delivered", 1727780000, "919000000011"),
                status("wamid.S2", "read", 1727780000, "919000000012"),
                status("wamid.S3", "sent", 1727780000, "919000000013")), Map.of());
        JsonNode stats = getJson("/api/v1/dlr/live/stats?minutes=60");
        JsonNode meta = null;
        for (JsonNode c : stats.get("categories")) {
            if ("META".equals(c.get("key").asText())) {
                meta = c;
            }
        }
        assertThat(meta).isNotNull();
        assertThat(meta.get("label").asText()).isEqualTo("Meta WhatsApp");
        assertThat(meta.get("dlrs").asLong()).isEqualTo(3);
        assertThat(meta.get("delivered").asLong()).isEqualTo(1);
        assertThat(meta.get("read").asLong()).isEqualTo(1);
        assertThat(meta.get("pending").asLong()).isEqualTo(1);
        assertThat(stats.at("/totals/read").asLong()).isEqualTo(1);
        long chart = 0;
        for (JsonNode b : stats.get("series")) {
            chart += b.get("meta").asLong();
        }
        assertThat(chart).isEqualTo(3);
    }
}
