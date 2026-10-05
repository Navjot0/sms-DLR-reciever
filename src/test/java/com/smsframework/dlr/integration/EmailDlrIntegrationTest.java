package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Email DLRs (Amazon SES direct + SNS, Kenscio) end to end, detected from the payload. */
class EmailDlrIntegrationTest extends AbstractIntegrationTest {

    static String ses(String id, String eventType, String detailKey, String time, String to) {
        return """
                {"eventType":"%s","mail":{"timestamp":"2026-07-23T10:15:28.000Z","source":"news@brand.example",
                 "messageId":"%s","destination":["%s"]},"%s":{"timestamp":"%s","recipients":["%s"]}}"""
                .formatted(eventType, id, to, detailKey, time, to);
    }

    @Test
    void sesSendDeliveryOpenClickProgression() {
        String to = "a.very.long.recipient.address.for.testing@subdomain.example-company.co.in";   // > 30 chars
        assertThat(postDlr(ses("ses-int-1", "Send", "send", "2026-07-23T10:15:29.000Z", to), Map.of()).statusCode()).isEqualTo(200);
        postDlr(ses("ses-int-1", "Delivery", "delivery", "2026-07-23T10:15:30.000Z", to), Map.of());
        postDlr(ses("ses-int-1", "Open", "open", "2026-07-23T10:16:00.000Z", to), Map.of());
        postDlr(ses("ses-int-1", "Click", "click", "2026-07-23T10:16:05.000Z", to), Map.of());

        JsonNode s = getJson("/api/v1/dlr/ses-int-1");
        assertThat(s.get("source").asText()).isEqualTo("EMAIL");
        assertThat(s.get("status").asText()).isEqualTo("READ");
        assertThat(s.get("mobile").asText()).isEqualTo(to);
        assertThat(s.get("sender").asText()).isEqualTo("news@brand.example");
        assertThat(s.get("event_count").asInt()).isEqualTo(4);
    }

    @Test
    void snsWrappedNotificationAndSubscriptionHandshake() {
        HttpResponse<String> r = postDlr("{\"Type\": \"Notification\", \"MessageId\": \"sns-1\", \"TopicArn\": \"arn:aws:sns:ap-south-1:123456789012:ses-events\", \"Message\": \"{\\\"eventType\\\": \\\"Delivery\\\", \\\"mail\\\": {\\\"timestamp\\\": \\\"2026-07-23T10:15:28.000Z\\\", \\\"source\\\": \\\"news@brand.example\\\", \\\"messageId\\\": \\\"0100018f-ses-0001\\\", \\\"destination\\\": [\\\"asha@example.com\\\"]}, \\\"delivery\\\": {\\\"timestamp\\\": \\\"2026-07-23T10:15:30.000Z\\\", \\\"recipients\\\": [\\\"asha@example.com\\\"], \\\"smtpResponse\\\": \\\"250 2.0.0 OK\\\"}}\", \"Timestamp\": \"2026-07-23T10:15:30.500Z\"}", Map.of());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(readJson(r.body()).get("applied").asInt()).isEqualTo(1);
        assertThat(getJson("/api/v1/dlr/0100018f-ses-0001").get("status").asText()).isEqualTo("DELIVERED");

        HttpResponse<String> sub = postDlr(
                "{\"Type\":\"SubscriptionConfirmation\",\"TopicArn\":\"arn:x\",\"SubscribeURL\":\"https://sns.example/confirm\"}", Map.of());
        assertThat(sub.statusCode()).isEqualTo(200);
        assertThat(readJson(sub.body()).get("note").asText()).contains("SubscribeURL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events", Long.class)).isEqualTo(1);
    }

    @Test
    void kenscioBatchAndTheEmailCategory() {
        HttpResponse<String> r = postDlr("[{\"xJob\": \"a3f9c2d1e4b5\", \"eventType\": \"DELIVER\", \"eventTimestamp\": \"2026-07-23T10:15:30Z\", \"address\": \"asha@example.com\"}, {\"xJob\": \"a3f9c2d1e4b5\", \"eventType\": \"OPEN\", \"eventTimestamp\": \"2026-07-23T10:16:02Z\", \"address\": \"asha@example.com\"}, {\"x-job\": \"b7e1d0c9a8f6\", \"event-type\": \"BOUNCE\", \"event-timestamp\": \"2026-07-23T10:15:33Z\", \"address\": \"nobody@example.com\"}]", Map.of());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode ack = readJson(r.body());
        assertThat(ack.get("source").asText()).isEqualTo("EMAIL");
        assertThat(ack.get("statuses").asInt()).isEqualTo(3);
        assertThat(ack.get("applied").asInt()).isEqualTo(3);
        postDlr("{\"xJob\":\"a3f9c2d1e4b5\",\"eventType\":\"CLICK\",\"eventTimestamp\":\"2026-07-23T10:17:00Z\","
                + "\"address\":\"asha@example.com\",\"url\":\"https://brand.example/offer\"}", Map.of());
        postDlr("{\"eventType\": \"Bounce\", \"mail\": {\"timestamp\": \"2026-07-23T10:15:28.000Z\", \"source\": \"news@brand.example\", \"messageId\": \"0100018f-ses-0002\", \"destination\": [\"nobody@example.com\"]}, \"bounce\": {\"bounceType\": \"Permanent\", \"bounceSubType\": \"General\", \"timestamp\": \"2026-07-23T10:15:31.000Z\", \"bouncedRecipients\": [{\"emailAddress\": \"nobody@example.com\", \"action\": \"failed\", \"status\": \"5.1.1\", \"diagnosticCode\": \"smtp; 550 5.1.1 user unknown\"}]}}", Map.of());

        assertThat(getJson("/api/v1/dlr/a3f9c2d1e4b5").get("status").asText()).isEqualTo("READ");
        assertThat(getJson("/api/v1/dlr/b7e1d0c9a8f6").get("status").asText()).isEqualTo("FAILED");
        JsonNode bounce = getJson("/api/v1/dlr/0100018f-ses-0002");
        assertThat(bounce.get("status").asText()).isEqualTo("FAILED");
        assertThat(bounce.get("status_code").asText()).isEqualTo("5.1.1");

        JsonNode stats = getJson("/api/v1/dlr/live/stats?minutes=60");
        JsonNode email = null;
        for (JsonNode c : stats.get("categories")) {
            if ("EMAIL".equals(c.get("key").asText())) {
                email = c;
            }
        }
        assertThat(email).isNotNull();
        assertThat(email.get("label").asText()).isEqualTo("Email");
        assertThat(email.get("dlrs").asLong()).isEqualTo(3);        // messages
        assertThat(email.get("delivered").asLong()).isEqualTo(1);   // delivered or opened
        assertThat(email.get("read").asLong()).isEqualTo(1);        // opened
        assertThat(email.get("clicks").asLong()).isEqualTo(1);      // clicked
        assertThat(email.get("failed").asLong()).isEqualTo(2);      // bounced
        long chart = 0;
        for (JsonNode b : stats.get("series")) {
            chart += b.get("email").asLong();
        }
        assertThat(chart).isEqualTo(5);
        assertThat(getJson("/api/v1/dlr/live/feed").at("/items/0/service").asText()).isEqualTo("SES");
    }
}
