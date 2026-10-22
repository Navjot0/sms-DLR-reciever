package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** dlr.accept-invalid=true (the production default): DLRs that fail validation are kept as received, never "invalid". */
@TestPropertySource(properties = "dlr.accept-invalid=true")
class AcceptAllDlrIntegrationTest extends AbstractIntegrationTest {

    /** CPaaS platform RCS event without a status (strict mode: "platform: status is missing"). */
    static String rcsNoStatus(String id, String number) {
        return "{\"event_type\":\"message_delivery\",\"message_id\":\"" + id + "\",\"message\":{\"number\":\"" + number
                + "\",\"direction\":\"outbound\"},\"agent\":{\"name\":\"a\"}}";
    }

    @Test
    void bulkRcsEventsThatFailValidationAreAcceptedNotInvalid() {
        String inbound = "{\"event_type\":\"message_received\",\"message_id\":\"rcs-in\",\"status\":\"received\","
                + "\"message\":{\"number\":\"919000000009\",\"direction\":\"inbound\"},\"agent\":{\"name\":\"a\"}}";
        String body = "{\"batchId\":\"b-rcs\",\"events\":[" + rcsNoStatus("rcs-a", "919000000001") + ","
                + rcsNoStatus("rcs-b", "919000000002") + "," + inbound + "]}";
        HttpResponse<String> res = postDlr(body, Map.of());
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode ack = readJson(res.body());
        assertThat(ack.get("rejected").asInt()).isZero();
        assertThat(ack.get("interpreted").asInt()).isEqualTo(3);
        assertThat(ack.get("interpretation_status").asText()).isEqualTo("INTERPRETED");

        JsonNode a = getJson("/api/v1/dlr/rcs-a");
        assertThat(a.get("received").asBoolean()).isTrue();
        assertThat(a.get("source").asText()).isEqualTo("RCS");
        assertThat(a.get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(a.get("record_state").asText()).isEqualTo("PARSED");
        assertThat(getJson("/api/v1/dlr/rcs-in").get("provider_status").asText()).isEqualTo("received");

        JsonNode m = getJson("/api/v1/dlr/messages?message_id=rcs-b").at("/items/0");
        assertThat(m.get("processing_status").asText()).isEqualTo("APPLIED");
        assertThat(m.get("mobile").asText()).isEqualTo("919000000002");
        assertThat(m.get("processing_note").asText()).contains("accepted without validation");
        assertThat(getJson("/api/v1/webhooks/requests?kind=bulk&batch_id=b-rcs").at("/items/0/failed_event_count").asInt()).isZero();
    }

    @Test
    void singleDlrThatFailsValidationIsStoredAsReceived() {
        HttpResponse<String> res = postDlr("{\"payload\":{\"message_id\":\"nomob-1\",\"status\":\"DELIVRD\"}}",
                Map.of("X-DLR-Source", "DEFAULT_SMS"));
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode s = getJson("/api/v1/dlr/nomob-1");
        assertThat(s.get("received").asBoolean()).isTrue();
        assertThat(s.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(s.get("record_state").asText()).isEqualTo("PARSED");

        postDlr(rcsNoStatus("rcs-single", "919000000003"), Map.of());
        assertThat(getJson("/api/v1/dlr/rcs-single").get("received").asBoolean()).isTrue();
    }

    @Test
    void eventWithoutAnyMessageIdIsKeptButNotCountedInvalid() {
        String body = "{\"batchId\":\"b-noid\",\"events\":[{\"event_type\":\"message_delivery\",\"message\":{\"number\":\"919000000004\"},"
                + "\"agent\":{\"name\":\"a\"},\"status\":\"delivered\"}," + rcsNoStatus("rcs-c", "919000000005") + "]}";
        JsonNode ack = readJson(postDlr(body, Map.of()).body());
        assertThat(ack.get("rejected").asInt()).isZero();
        assertThat(ack.get("interpreted").asInt()).isEqualTo(1);
        assertThat(getJson("/api/v1/dlr/messages?batch_id=b-noid").get("items")).hasSize(2);   // nothing dropped
    }
}
