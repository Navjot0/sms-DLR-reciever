package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The CPaaS platform's own RCS webhook (message_dispatch / message_delivery), as received from the platform. */
class RcsPlatformWebhookIntegrationTest extends AbstractIntegrationTest {

    private static String sample(String name) throws Exception {
        return Files.readString(Path.of("samples", name));
    }

    @Test
    void platformRcsEventsAreAcceptedWithoutAnySourceHeader() throws Exception {
        assertThat(postDlr(sample("rcs-platform-dispatch.json"), Map.of()).statusCode()).isEqualTo(200);
        assertThat(postDlr(sample("rcs-platform-delivery.json"), Map.of()).statusCode()).isEqualTo(200);
        assertThat(postDlr(sample("rcs-platform-jio-sent.json"), Map.of()).statusCode()).isEqualTo(200);

        JsonNode vi = getJson("/api/v1/dlr/90ef284e-bd46-42f3-94ee-4b8503a8e133");
        assertThat(vi.get("source").asText()).isEqualTo("RCS");
        assertThat(vi.get("status").asText()).isEqualTo("SENT");
        assertThat(vi.get("provider_status").asText()).isEqualTo("Submitted");
        assertThat(vi.get("mobile").asText()).isEqualTo("919202511257");
        assertThat(vi.get("correlation_id").asText()).isEqualTo("123443431");
        assertThat(vi.get("external_message_id").asText()).isEqualTo("14171700-f552-440b-b58d-d0a1ceae5e43");
        assertThat(vi.get("sender").asText()).isEqualTo("viagent");

        JsonNode dotgo = getJson("/api/v1/dlr/3b8a319d-0d29-4ed4-8b6d-5e0b84dcb92b");
        assertThat(dotgo.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(dotgo.get("mobile").asText()).isEqualTo("917973059161");

        JsonNode jio = getJson("/api/v1/dlr/80051541-e62e-440b-bac0-ca124ac14504");
        assertThat(jio.get("status").asText()).isEqualTo("SENT");
        assertThat(jio.get("correlation_id").asText()).isEqualTo("corelation_id_carousel_dotgo");

        // operator is kept in "service" and shown in the live feed
        JsonNode feed = getJson("/api/v1/dlr/live/feed");
        assertThat(feed.at("/items/0/service").asText()).isEqualTo("JIO");
        assertThat(feed.at("/items/1/service").asText()).isEqualTo("DOTGO");
        assertThat(feed.at("/items/2/service").asText()).isEqualTo("VI");

        // the DLR JSON comes back exactly as the platform sent it
        assertThat(getJson("/api/v1/dlr/3b8a319d-0d29-4ed4-8b6d-5e0b84dcb92b/json"))
                .isEqualTo(readJson(sample("rcs-platform-delivery.json")));
    }

    @Test
    void dispatchThenDeliveryThenReadProgresses() {
        String base = """
                {"event_type":"%s","message_id":"rcs-plat-1","external_message_id":"op-1","corelation_id":null,
                 "status":"%s","timestamp":"2026-10-10T09:2%d:00Z",
                 "message":{"id":"rcs-plat-1","direction":"outbound","number":"919000000001","campaign_id":null},
                 "agent":{"name":"jioagent","provider":"Jio RCS Provider"},"additional_data":{"provider_type":"jio"}}""";
        postDlr(base.formatted("message_dispatch", "sent", 1), Map.of());
        postDlr(base.formatted("message_delivery", "delivered", 2), Map.of());
        postDlr(base.formatted("message_read", "read", 3), Map.of());
        JsonNode s = getJson("/api/v1/dlr/rcs-plat-1");
        assertThat(s.get("status").asText()).isEqualTo("READ");
        assertThat(s.get("event_count").asInt()).isEqualTo(3);
    }

    @Test
    void failedDeliveryKeepsTheReason() {
        postDlr("""
                {"event_type":"message_delivery","message_id":"rcs-plat-2","status":"failed","timestamp":"2026-10-10T09:30:00Z",
                 "delivery_info":{"error_message":"User is not RCS capable","failure_reason":"NOT_RCS_USER"},
                 "message":{"direction":"outbound","number":"919000000002"},"agent":{"name":"viagent"},
                 "additional_data":{"provider":"vi-rcs"}}""", Map.of());
        JsonNode s = getJson("/api/v1/dlr/rcs-plat-2");
        assertThat(s.get("status").asText()).isEqualTo("FAILED");
        assertThat(s.get("status_code").asText()).isEqualTo("NOT_RCS_USER");   // no err_code / provider_code: the reason
        assertThat(s.get("error_reason").asText()).isEqualTo("User is not RCS capable");
    }

    @Test
    void jioFailureCarriesTheOperatorErrorCode() throws Exception {
        assertThat(postDlr(sample("rcs-platform-jio-failed.json"), Map.of()).statusCode()).isEqualTo(200);
        JsonNode s = getJson("/api/v1/dlr/337fe547-587c-4e06-afca-8278afd3d40e");
        assertThat(s.get("source").asText()).isEqualTo("RCS");
        assertThat(s.get("status").asText()).isEqualTo("FAILED");
        assertThat(s.get("provider_status").asText()).isEqualTo("failed");
        assertThat(s.get("status_code").asText()).isEqualTo("5");
        assertThat(s.get("error_code").asText()).isEqualTo("not_found");
        assertThat(s.get("error_reason").asText()).isEqualTo("User Not Found (Code: not_found, Error Code: 5)");
        assertThat(s.get("correlation_id").asText()).isEqualTo("Pushpenderiueriewhfi");
        assertThat(s.get("mobile").asText()).isEqualTo("917973059161");
        assertThat(getJson("/api/v1/dlr/live/feed").at("/items/0/service").asText()).isEqualTo("JIO");
    }

    @Test
    void lateSubmittedAfterDeliveredKeepsDelivered() throws Exception {
        postDlr(sample("rcs-platform-delivery.json"), Map.of());           // delivered 09:24:53.858
        JsonNode ack = readJson(postDlr(sample("rcs-platform-dotgo-submitted.json"), Map.of()).body());   // Submitted 09:24:53.055
        assertThat(ack.get("processing_status").asText()).isEqualTo("IGNORED");
        JsonNode s = getJson("/api/v1/dlr/3b8a319d-0d29-4ed4-8b6d-5e0b84dcb92b");
        assertThat(s.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(s.get("event_count").asInt()).isEqualTo(2);
        JsonNode all = getJson("/api/v1/dlr/3b8a319d-0d29-4ed4-8b6d-5e0b84dcb92b/json?all=true");
        assertThat(all).hasSize(2);
        assertThat(getJson("/api/v1/dlr/3b8a319d-0d29-4ed4-8b6d-5e0b84dcb92b/json").get("status").asText())
                .isEqualTo("delivered");
    }

    @Test
    void jsonApiReturnsThePlatformDlrEvenWhenItWasRejected() throws Exception {
        // an RCS platform event the receiver cannot accept (no status anywhere): still stored with its message_id
        String broken = """
                {"event_type":"message_delivery","message_id":"rcs-rej-1","external_message_id":"x",
                 "message":{"direction":"outbound","number":"919000000001"},"agent":{"name":"viagent"}}""";
        assertThat(postDlr(broken, Map.of()).statusCode()).isEqualTo(200);

        java.net.http.HttpResponse<String> r = get("/api/v1/dlr/rcs-rej-1/json");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(readJson(r.body())).isEqualTo(readJson(broken));
        assertThat(r.headers().firstValue("X-DLR-Processing-Status")).contains("REJECTED");
        assertThat(r.headers().firstValue("X-DLR-Rejection-Reason").orElse("")).contains("status is missing");

        // an accepted one is flagged ACCEPTED
        postDlr(sample("rcs-platform-jio-failed.json"), Map.of());
        java.net.http.HttpResponse<String> ok = get("/api/v1/dlr/337fe547-587c-4e06-afca-8278afd3d40e/json");
        assertThat(ok.headers().firstValue("X-DLR-Processing-Status")).contains("ACCEPTED");
        assertThat(readJson(ok.body())).isEqualTo(readJson(sample("rcs-platform-jio-failed.json")));
    }

    @Test
    void jsonApiReturnsThePlatformDlrForEachStatus() {
        String ev = """
                {"event_type":"%s","message_id":"rcs-st-1","external_message_id":"op-9","corelation_id":"c-9",
                 "status":"%s","timestamp":"2026-10-10T09:3%d:00Z",
                 "message":{"direction":"outbound","number":"919000000009"},"agent":{"name":"jioagent"},
                 "additional_data":{"provider_type":"jio"}}""";
        String submitted = ev.formatted("message_dispatch", "Submitted", 1);
        String sent = ev.formatted("message_dispatch", "sent", 2);
        String delivered = ev.formatted("message_delivery", "delivered", 3);
        String read = ev.formatted("message_read", "read", 4);
        postDlr(submitted, Map.of());
        postDlr(sent, Map.of());
        postDlr(delivered, Map.of());
        postDlr(read, Map.of());

        assertThat(getJson("/api/v1/dlr/rcs-st-1/json?status=submitted")).isEqualTo(readJson(submitted));
        assertThat(getJson("/api/v1/dlr/rcs-st-1/json?status=submit")).isEqualTo(readJson(submitted));
        assertThat(getJson("/api/v1/dlr/rcs-st-1/json?status=sent")).isEqualTo(readJson(sent));
        assertThat(getJson("/api/v1/dlr/rcs-st-1/json?status=DELIVERED")).isEqualTo(readJson(delivered));
        assertThat(getJson("/api/v1/dlr/rcs-st-1/json?status=read")).isEqualTo(readJson(read));
        assertThat(get("/api/v1/dlr/rcs-st-1/json?status=failed").statusCode()).isEqualTo(404);
        assertThat(getJson("/api/v1/dlr/rcs-st-1/json?all=true")).hasSize(4);

        // failed + rejected
        String failed = ev.replace("rcs-st-1", "rcs-st-2").formatted("message_delivery", "failed", 5);
        String rejected = ev.replace("rcs-st-1", "rcs-st-3").formatted("message_delivery", "rejected", 6);
        postDlr(failed, Map.of());
        postDlr(rejected, Map.of());
        assertThat(getJson("/api/v1/dlr/rcs-st-2/json?status=failed")).isEqualTo(readJson(failed));
        assertThat(getJson("/api/v1/dlr/rcs-st-3/json?status=rejected")).isEqualTo(readJson(rejected));
        assertThat(getJson("/api/v1/dlr/rcs-st-3").get("status").asText()).isEqualTo("REJECTED");
    }
}
