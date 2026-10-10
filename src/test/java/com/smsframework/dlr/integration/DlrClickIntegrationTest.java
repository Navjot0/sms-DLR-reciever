package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Short-link click events on the same endpoint, correlated with the status DLR by message_id. */
class DlrClickIntegrationTest extends AbstractIntegrationTest {

    private static final String MSG = "68c3d2ef-9ced-46b8-aa1c-13be73ad9321";

    private static String statusDlr(String status) {
        return """
                {"code":"000","units":"1","mobile":"919177873237","sender":"DUMMY","status":"%s","service":"T",
                 "submit_at":"2026-09-25 23:20:00","message_id":"%s:1","dlr_received_at":"2026-09-25 23:20:05"}"""
                .formatted(status, MSG);
    }

    @Test
    void clicksAreStoredAndCorrelatedWithTheStatusDlr() throws Exception {
        postDlr(statusDlr("DELIVRD"), Map.of());
        HttpResponse<String> first = postDlr(TestPayloads.CLICK_EXAMPLE, Map.of());
        HttpResponse<String> second = postDlr(TestPayloads.CLICK_EXAMPLE_2, Map.of());

        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode ack = readJson(first.body());
        assertThat(ack.get("event_type").asText()).isEqualTo("short_link");
        assertThat(ack.get("processing_status").asText()).isEqualTo("APPLIED");
        assertThat(ack.get("message_id").asText()).isEqualTo(MSG);
        assertThat(readJson(second.body()).get("visited_count").asInt()).isEqualTo(3);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM dlr_click_events ORDER BY id LIMIT 1");
        assertThat(row.get("message_id")).isEqualTo(MSG);
        assertThat(row.get("provider_message_id")).isEqualTo(MSG + ":1");
        assertThat(row.get("part_number")).isEqualTo(1);
        assertThat(row.get("contact")).isEqualTo("919177873237");
        assertThat(row.get("url_key")).isEqualTo("ZIO7ER");
        assertThat(row.get("short_url")).isEqualTo("stqa.gtls.in/DUMMY/bBz/ZIO7ER");
        assertThat(row.get("destination_url")).isEqualTo("https://login.microsoftonline.com/common/login");
        assertThat(row.get("visited_count")).isEqualTo(2);
        assertThat(row.get("ip_address")).isEqualTo("152.58.121.146");
        assertThat(row.get("device_type")).isEqualTo("desktop");
        assertThat(row.get("browser")).isEqualTo("Chrome");
        assertThat(row.get("correlation_id")).isNull();
        assertThat(row.get("clicked_at").toString()).startsWith("2026-09-25 23:27:24");
        assertThat(JSON.readTree(row.get("raw_payload").toString())).isEqualTo(JSON.readTree(TestPayloads.CLICK_EXAMPLE));
        // clicks never touch the delivery state
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events", Integer.class)).isEqualTo(1);

        for (String lookup : new String[]{MSG, MSG + ":1"}) {
            JsonNode r = getJson("/api/v1/dlr/" + lookup);
            assertThat(r.get("status").asText()).isEqualTo("DELIVERED");
            assertThat(r.at("/clicks/clicked").asBoolean()).as(lookup).isTrue();
            assertThat(r.at("/clicks/clicks").asInt()).isEqualTo(2);
            assertThat(r.at("/clicks/visited_count").asInt()).isEqualTo(3);
            assertThat(r.at("/clicks/unique_ips").asInt()).isEqualTo(1);
            assertThat(r.at("/clicks/url_keys/0").asText()).isEqualTo("ZIO7ER");
            assertThat(r.at("/clicks/first_clicked_at").asText()).isEqualTo("2026-09-25T23:27:24");
            assertThat(r.at("/clicks/last_clicked_at").asText()).isEqualTo("2026-09-25T23:30:25");
            assertThat(r.at("/clicks/last_device_type").asText()).isEqualTo("desktop");
        }

        JsonNode details = getJson("/api/v1/dlr/" + MSG + "/clicks");
        assertThat(details.get("events")).hasSize(2);
        assertThat(details.at("/events/1/visited_count").asInt()).isEqualTo(3);
    }

    @Test
    void resentClickIsADuplicateAndNotCountedTwice() {
        postDlr(TestPayloads.CLICK_EXAMPLE, Map.of());
        HttpResponse<String> again = postDlr(TestPayloads.CLICK_EXAMPLE, Map.of());
        assertThat(readJson(again.body()).get("processing_status").asText()).isEqualTo("DUPLICATE");
        assertThat(getJson("/api/v1/dlr/" + MSG + "/clicks").at("/clicks/clicks").asInt()).isEqualTo(1);
    }

    @Test
    void clickBeforeStatusDlrIsVisible() {
        postDlr(TestPayloads.CLICK_EXAMPLE, Map.of());
        JsonNode r = getJson("/api/v1/dlr/" + MSG);
        assertThat(r.get("received").asBoolean()).isFalse();
        assertThat(r.at("/clicks/clicked").asBoolean()).isTrue();
    }

    @Test
    void statusWithoutClicksShowsNotClicked() {
        postDlr(statusDlr("DELIVRD"), Map.of());
        JsonNode r = getJson("/api/v1/dlr/" + MSG);
        assertThat(r.at("/clicks/clicked").asBoolean()).isFalse();
        assertThat(r.at("/clicks/clicks").asInt()).isZero();
    }

    @Test
    void verifyCanRequireAClick() {
        postDlr(statusDlr("DELIVRD"), Map.of());
        postDlr(TestPayloads.CLICK_EXAMPLE, Map.of());
        postDlr(TestPayloads.defaultSms("no-click", "DELIVRD"), Map.of("X-DLR-Source", "DEFAULT_SMS"));

        JsonNode v = readJson(postDlr("/api/v1/dlr/verify",
                "{\"message_ids\":[\"" + MSG + ":1\",\"no-click\"],\"require_click\":true}", Map.of()).body());
        assertThat(v.get("clicked").asInt()).isEqualTo(1);
        assertThat(v.get("click_missing").asInt()).isEqualTo(1);
        assertThat(v.get("matched").asInt()).isEqualTo(1);
        assertThat(v.get("all_matched").asBoolean()).isFalse();
        assertThat(v.at("/results/0/clicked").asBoolean()).isTrue();
        assertThat(v.at("/results/0/click_count").asInt()).isEqualTo(1);
        assertThat(v.at("/results/1/clicked").asBoolean()).isFalse();
    }

    @Test
    void invalidClickIsStoredAsRejected() {
        HttpResponse<String> r = postDlr("{\"event\":\"short_link\",\"data\":{\"message_id\":\"" + MSG + ":1\"}}", Map.of());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(readJson(r.body()).get("interpretation_status").asText()).isEqualTo("INVALID_DLR");
        Map<String, Object> row = jdbc.queryForMap("SELECT message_id, rejection_reason FROM dlr_events");
        assertThat(row.get("rejection_reason")).isEqualTo("short_link: data.url_key is missing");
        assertThat(row.get("message_id")).isEqualTo(MSG);
        assertThat(postDlr(TestPayloads.CLICK_EXAMPLE, Map.of("X-DLR-Source", "WEBENGAGE")).statusCode()).isEqualTo(200);
    }

    @Test
    void clickMetricsAreExposed() {
        postDlr(TestPayloads.CLICK_EXAMPLE, Map.of());
        for (String m : new String[]{"dlr.click.received", "dlr.click.applied", "dlr.click.duplicate", "dlr.click.rejected"}) {
            assertThat(get("/actuator/metrics/" + m).statusCode()).as(m).isEqualTo(200);
        }
    }

    @Test
    void clickJsonIsReturnedExactlyAsTheplatformSentIt() {
        assertThat(get("/api/v1/dlr/" + MSG + "/clicks/json").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/dlr/" + MSG + "/clicks/json?all=true").body()).isEqualTo("[]");

        postDlr(TestPayloads.CLICK_EXAMPLE, Map.of());
        postDlr(TestPayloads.CLICK_EXAMPLE_2, Map.of());      // "\/" escapes, no spaces
        postDlr(TestPayloads.CLICK_EXAMPLE_2, Map.of());      // exact repeat: left out

        HttpResponse<String> latest = get("/api/v1/dlr/" + MSG + "/clicks/json");
        assertThat(latest.statusCode()).isEqualTo(200);
        assertThat(latest.body()).isEqualTo(TestPayloads.CLICK_EXAMPLE_2);
        assertThat(latest.headers().firstValue("X-Click-Count")).contains("2");
        // the recipient id works too
        assertThat(get("/api/v1/dlr/" + MSG + ":1/clicks/json").body()).isEqualTo(TestPayloads.CLICK_EXAMPLE_2);
        assertThat(get("/api/v1/dlr/" + MSG + ":2/clicks/json").statusCode()).isEqualTo(404);

        assertThat(get("/api/v1/dlr/" + MSG + "/clicks/json?all=true").body())
                .isEqualTo("[" + TestPayloads.CLICK_EXAMPLE + "," + TestPayloads.CLICK_EXAMPLE_2 + "]");

        // the click list, live feed and lookup carry the same original text
        assertThat(getJson("/api/v1/dlr/" + MSG + "/clicks").at("/events/1/raw_text").asText())
                .isEqualTo(TestPayloads.CLICK_EXAMPLE_2);
        assertThat(getJson("/api/v1/dlr/live/feed").at("/items/0/raw_text").asText())
                .isEqualTo(TestPayloads.CLICK_EXAMPLE_2);
        JsonNode tl = getJson("/api/v1/dlr/live/message?id=" + MSG).get("timeline");
        assertThat(tl.get(tl.size() - 1).get("raw_text").asText()).isEqualTo(TestPayloads.CLICK_EXAMPLE_2);
    }
}
