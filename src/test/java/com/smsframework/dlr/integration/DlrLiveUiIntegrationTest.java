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
    void statsCountTheWindow() {
        postDlr(TestPayloads.defaultSms("st-1", "DELIVRD"), Map.of("X-DLR-Source", "DEFAULT_SMS"));
        postDlr(TestPayloads.defaultSms("st-1", "DELIVRD"), Map.of("X-DLR-Source", "DEFAULT_SMS"));   // duplicate
        postDlr(TestPayloads.defaultSms("st-2", "UNDELIV"), Map.of("X-DLR-Source", "DEFAULT_SMS"));
        postDlr("{broken", Map.of());
        postDlr(TestPayloads.billing("st-1", 2, "debit", "2"), Map.of());
        postDlr(TestPayloads.CLICK_EXAMPLE, Map.of());

        JsonNode s = getJson("/api/v1/dlr/live/stats?minutes=60");
        assertThat(s.get("status_callbacks").asLong()).isEqualTo(4);
        assertThat(s.at("/by_normalized_status/DELIVERED").asLong()).isEqualTo(1);
        assertThat(s.at("/by_normalized_status/FAILED").asLong()).isEqualTo(1);
        assertThat(s.at("/by_processing_status/DUPLICATE").asLong()).isEqualTo(1);
        assertThat(s.at("/by_processing_status/REJECTED").asLong()).isEqualTo(1);
        assertThat(s.get("billing_events").asLong()).isEqualTo(2);
        assertThat(s.get("billed_amount").decimalValue()).isEqualByComparingTo("4");
        assertThat(s.get("billed_currency").asText()).isEqualTo("INR");
        assertThat(s.get("clicks").asLong()).isEqualTo(1);
        long perMinute = 0;
        for (JsonNode b : s.get("per_minute")) {
            perMinute += b.get("status").asLong() + b.get("billing").asLong() + b.get("click").asLong();
        }
        assertThat(perMinute).isEqualTo(4 + 2 + 1);
    }
}
