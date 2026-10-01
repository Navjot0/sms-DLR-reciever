package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Live UI page + the feed/stats APIs it polls. */
class DlrLiveUiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void uiPageIsServedAndRootRedirects() throws Exception {
        HttpResponse<String> ui = get("/ui/");
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("DLR Receiver · Live").contains("/api/v1/dlr/live/feed");

        HttpResponse<String> root = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url("/"))).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(root.statusCode()).isEqualTo(302);
        assertThat(root.headers().firstValue("Location").orElse("")).endsWith("/ui/");
    }

    @Test
    void feedReturnsAllKindsNewestFirstAndCursorReturnsOnlyNewerEvents() {
        postDlr(TestPayloads.defaultSms("live-1", "DELIVRD"), Map.of("X-DLR-Source", "DEFAULT_SMS"));
        postDlr(TestPayloads.billing("live-1", 1, "debit", "2"), Map.of());
        postDlr(TestPayloads.CLICK_EXAMPLE, Map.of());

        JsonNode first = getJson("/api/v1/dlr/live/feed");
        assertThat(first.get("items")).hasSize(3);
        assertThat(first.at("/items/0/kind").asText()).isEqualTo("CLICK");
        assertThat(first.at("/items/1/kind").asText()).isEqualTo("BILLING");
        assertThat(first.at("/items/1/total_amount").decimalValue()).isEqualByComparingTo("2");
        assertThat(first.at("/items/2/kind").asText()).isEqualTo("STATUS");
        assertThat(first.at("/items/2/normalized_status").asText()).isEqualTo("DELIVERED");
        JsonNode cursor = first.get("cursor");

        postDlr(TestPayloads.defaultSms("live-2", "UNDELIV"), Map.of("X-DLR-Source", "DEFAULT_SMS"));
        JsonNode next = getJson("/api/v1/dlr/live/feed?after_status=" + cursor.get("status").asLong()
                + "&after_billing=" + cursor.get("billing").asLong() + "&after_click=" + cursor.get("click").asLong());
        assertThat(next.get("items")).hasSize(1);
        assertThat(next.at("/items/0/message_id").asText()).isEqualTo("live-2");
        assertThat(next.at("/items/0/normalized_status").asText()).isEqualTo("FAILED");
    }

    @Test
    void feedHidesDuplicateAndInvalidCallbacks() {
        postDlr(TestPayloads.defaultSms("hide-1", "DELIVRD"), Map.of("X-DLR-Source", "DEFAULT_SMS"));
        postDlr(TestPayloads.defaultSms("hide-1", "DELIVRD"), Map.of("X-DLR-Source", "DEFAULT_SMS"));   // duplicate
        postDlr("{broken", Map.of());

        JsonNode feed = getJson("/api/v1/dlr/live/feed");
        assertThat(feed.get("items")).hasSize(1);
        assertThat(feed.at("/items/0/message_id").asText()).isEqualTo("hide-1");
    }

    @Test
    void statsCountPerCategory() {
        postDlr(TestPayloads.defaultSms("st-1", "DELIVRD"), Map.of("X-DLR-Source", "DEFAULT_SMS"));
        postDlr(TestPayloads.defaultSms("st-1", "DELIVRD"), Map.of("X-DLR-Source", "DEFAULT_SMS"));   // duplicate: not counted
        postDlr(TestPayloads.defaultSms("st-2", "UNDELIV"), Map.of("X-DLR-Source", "DEFAULT_SMS"));
        postDlr(TestPayloads.defaultSms("st-3", "REJECTD"), Map.of("X-DLR-Source", "DEFAULT_SMS"));
        postDlr(TestPayloads.webEngage("we-1", "sms_delivered", "0"), Map.of("X-DLR-Source", "WEBENGAGE"));
        postDlr(TestPayloads.webEngage("we-2", "sms_sent", "0"), Map.of("X-DLR-Source", "WEBENGAGE"));
        postDlr("{broken", Map.of());                                                                   // invalid: not counted
        postDlr(TestPayloads.billing("st-1", 2, "debit", "2"), Map.of());
        postDlr(TestPayloads.CLICK_EXAMPLE, Map.of());
        postDlr(TestPayloads.CLICK_EXAMPLE_2, Map.of());

        JsonNode s = getJson("/api/v1/dlr/live/stats?minutes=60");
        JsonNode t = s.get("totals");
        assertThat(t.get("dlrs").asLong()).isEqualTo(5);
        assertThat(t.get("delivered").asLong()).isEqualTo(2);
        assertThat(t.get("failed").asLong()).isEqualTo(1);
        assertThat(t.get("rejected").asLong()).isEqualTo(1);
        assertThat(t.get("pending").asLong()).isEqualTo(1);
        assertThat(t.get("billing_events").asLong()).isEqualTo(2);
        assertThat(t.get("billed_amount").decimalValue()).isEqualByComparingTo("4");
        assertThat(t.get("billed_currency").asText()).isEqualTo("INR");
        assertThat(t.get("clicks").asLong()).isEqualTo(2);

        JsonNode sms = category(s, "DEFAULT_SMS");
        assertThat(sms.get("label").asText()).isEqualTo("Default SMS");
        assertThat(sms.get("dlrs").asLong()).isEqualTo(3);
        assertThat(sms.get("delivered").asLong()).isEqualTo(1);
        assertThat(sms.get("failed").asLong()).isEqualTo(1);
        assertThat(sms.get("rejected").asLong()).isEqualTo(1);
        assertThat(sms.get("billing_events").asLong()).isEqualTo(2);

        JsonNode we = category(s, "WEBENGAGE");
        assertThat(we.get("dlrs").asLong()).isEqualTo(2);
        assertThat(we.get("delivered").asLong()).isEqualTo(1);
        assertThat(we.get("pending").asLong()).isEqualTo(1);
        assertThat(we.get("billing_events").asLong()).isZero();

        JsonNode url = category(s, "SHORT_URL");
        assertThat(url.get("label").asText()).isEqualTo("Short URL");
        assertThat(url.get("clicks").asLong()).isEqualTo(2);
        assertThat(url.get("links").asLong()).isEqualTo(1);

        long chart = 0;
        for (JsonNode b : s.get("series")) {
            chart += b.get("default_sms").asLong() + b.get("webengage").asLong() + b.get("short_url").asLong();
        }
        assertThat(chart).isEqualTo(3 + 2 + 2);
        assertThat(s.get("bucket_minutes").asInt()).isEqualTo(1);

        JsonNode all = getJson("/api/v1/dlr/live/stats?minutes=0");
        assertThat(all.get("window_minutes").asInt()).isZero();
        assertThat(all.at("/totals/dlrs").asLong()).isEqualTo(5);
        assertThat(all.get("bucket_minutes").asInt()).isEqualTo(60);
    }

    @Test
    void bulkCampaignBillingCallbackIsAppliedAndShown() {
        // ~500 recipients in one callback (~100 KB): used to exceed the old 64 KB limit and was rejected
        String bulk = TestPayloads.billing("bulk-camp", 500, "debit", "0.01");
        assertThat(bulk.length()).isGreaterThan(64 * 1024);
        assertThat(postDlr(bulk, Map.of()).statusCode()).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_billing_events WHERE processing_status = 'APPLIED'",
                Long.class)).isEqualTo(500);
        // the complete callback is stored once, not on every row
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_billing_events WHERE raw_payload IS NOT NULL",
                Long.class)).isEqualTo(1);

        JsonNode t = getJson("/api/v1/dlr/live/stats?minutes=60").get("totals");
        assertThat(t.get("billing_events").asLong()).isEqualTo(500);
        assertThat(t.get("billed_amount").decimalValue()).isEqualByComparingTo("5");
        assertThat(t.get("billing_callbacks_rejected").asLong()).isZero();

        JsonNode feed = getJson("/api/v1/dlr/live/feed?limit=10");
        assertThat(feed.at("/items/0/kind").asText()).isEqualTo("BILLING");
    }

    @Test
    void rejectedBillingCallbackIsCounted() {
        postDlr("{\"event_type\":\"billing\",\"events\":\"not-a-list\"}", Map.of());
        assertThat(getJson("/api/v1/dlr/live/stats?minutes=60").at("/totals/billing_callbacks_rejected").asLong())
                .isEqualTo(1);
    }

    private static JsonNode category(JsonNode stats, String key) {
        for (JsonNode c : stats.get("categories")) {
            if (key.equals(c.get("key").asText())) {
                return c;
            }
        }
        throw new AssertionError("category " + key + " missing");
    }
}
