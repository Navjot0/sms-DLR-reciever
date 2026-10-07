package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Lookup and verify hand back the DLR exactly as CPaaS sent it, so automation can check the DLR format. */
class DlrOriginalPayloadIntegrationTest extends AbstractIntegrationTest {

    static final String CPAAS_DLR = """
            {
              "message_id": "1522ff82-2818-4670-b24e-306f0cf9266b",
              "service": "T",
              "sender": "DUMMY",
              "mobile": "919034424020",
              "status": "REJECTED",
              "code": "807",
              "submit_at": "2026-10-06 15:29:58",
              "dlr_received_at": "2026-10-06 15:29:58",
              "entity_id": "17011580464447946654",
              "template_id": null,
              "units": "2",
              "correlation_id": null
            }""";

    static String recipientDlr(String id, String mobile, String status, String code) {
        return """
                {"message_id":"%s","service":"T","sender":"DUMMY","mobile":"%s","status":"%s","code":"%s",
                 "submit_at":"2026-10-06 15:29:58","dlr_received_at":"2026-10-06 15:30:0%s",
                 "entity_id":"17011580464447946654","template_id":null,"units":"1","correlation_id":null}"""
                .formatted(id, mobile, status, code, id.substring(id.length() - 1));
    }

    @Test
    void lookupReturnsTheSameJsonThatWasSent() {
        assertThat(postDlr(CPAAS_DLR, Map.of()).statusCode()).isEqualTo(200);

        JsonNode s = getJson("/api/v1/dlr/1522ff82-2818-4670-b24e-306f0cf9266b");
        assertThat(s.get("status").asText()).isEqualTo("REJECTED");
        assertThat(s.get("dlr")).isEqualTo(readJson(CPAAS_DLR));      // every field, nulls included
        assertThat(s.get("dlr").get("template_id").isNull()).isTrue();
        assertThat(s.get("dlr").get("units").asText()).isEqualTo("2");
    }

    @Test
    void verifyReturnsEachRecipientsOwnDlrAndStatus() {
        // bulk campaign: one message_id, ":<n>" per recipient
        postDlr(recipientDlr("21cc3333-c705-48dd-bf66-c8bf5f8399bb:1", "919000000001", "DELIVRD", "000"), Map.of());
        postDlr(recipientDlr("21cc3333-c705-48dd-bf66-c8bf5f8399bb:2", "919000000002", "REJECTED", "807"), Map.of());
        postDlr(recipientDlr("21cc3333-c705-48dd-bf66-c8bf5f8399bb:3", "919000000003", "DELIVRD", "000"), Map.of());
        postDlr(CPAAS_DLR, Map.of());

        JsonNode v = readJson(postDlr("/api/v1/dlr/verify", """
                {"message_ids":["21cc3333-c705-48dd-bf66-c8bf5f8399bb:3","21cc3333-c705-48dd-bf66-c8bf5f8399bb:2",
                                "1522ff82-2818-4670-b24e-306f0cf9266b","not-sent-yet"]}""", Map.of()).body());
        JsonNode r3 = v.at("/results/0");
        assertThat(r3.get("message_id").asText()).isEqualTo("21cc3333-c705-48dd-bf66-c8bf5f8399bb:3");
        assertThat(r3.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(r3.get("dlr")).isEqualTo(readJson(
                recipientDlr("21cc3333-c705-48dd-bf66-c8bf5f8399bb:3", "919000000003", "DELIVRD", "000")));

        JsonNode r2 = v.at("/results/1");
        assertThat(r2.get("status").asText()).isEqualTo("REJECTED");          // its own status, not the campaign's
        assertThat(r2.get("provider_status").asText()).isEqualTo("REJECTED");
        assertThat(r2.get("status_code").asText()).isEqualTo("807");
        assertThat(r2.at("/dlr/message_id").asText()).isEqualTo("21cc3333-c705-48dd-bf66-c8bf5f8399bb:2");
        assertThat(r2.at("/dlr/mobile").asText()).isEqualTo("919000000002");

        assertThat(v.at("/results/2/dlr")).isEqualTo(readJson(CPAAS_DLR));
        assertThat(v.at("/results/3/received").asBoolean()).isFalse();
        assertThat(v.at("/results/3").has("dlr")).isFalse();
        assertThat(v.get("delivered").asInt()).isEqualTo(1);
        assertThat(v.get("rejected").asInt()).isEqualTo(2);

        // lookup of one recipient returns that recipient's DLR
        JsonNode one = getJson("/api/v1/dlr/21cc3333-c705-48dd-bf66-c8bf5f8399bb:2");
        assertThat(one.at("/dlr/mobile").asText()).isEqualTo("919000000002");
    }

    @Test
    void jsonOnlyApiReturnsJustTheDlr() {
        HttpResponse<String> none = get("/api/v1/dlr/1522ff82-2818-4670-b24e-306f0cf9266b/json");
        assertThat(none.statusCode()).isEqualTo(404);
        assertThat(readJson(none.body()).get("received").asBoolean()).isFalse();

        postDlr(CPAAS_DLR, Map.of());
        HttpResponse<String> r = get("/api/v1/dlr/1522ff82-2818-4670-b24e-306f0cf9266b/json");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(readJson(r.body())).isEqualTo(readJson(CPAAS_DLR));      // nothing else in the response
    }

    @Test
    void jsonOnlyApiPerRecipientAllAndBulk() {
        String sent = recipientDlr("camp-9:1", "919000000001", "SUBMITTED", "001");
        String delivered = recipientDlr("camp-9:1", "919000000001", "DELIVRD", "000").replace("15:30:01", "15:30:09");
        String other = recipientDlr("camp-9:2", "919000000002", "REJECTED", "807");
        postDlr(sent, Map.of());
        postDlr(delivered, Map.of());
        postDlr(other, Map.of());

        assertThat(getJson("/api/v1/dlr/camp-9:1/json")).isEqualTo(readJson(delivered));
        assertThat(getJson("/api/v1/dlr/camp-9:2/json")).isEqualTo(readJson(other));

        JsonNode all = getJson("/api/v1/dlr/camp-9:1/json?all=true");
        assertThat(all).hasSize(2);
        assertThat(all.get(0)).isEqualTo(readJson(sent));
        assertThat(all.get(1)).isEqualTo(readJson(delivered));
        assertThat(getJson("/api/v1/dlr/nothing-here/json?all=true")).isEmpty();

        JsonNode bulk = readJson(postDlr("/api/v1/dlr/json",
                "{\"message_ids\":[\"camp-9:2\",\"missing-id\",\"camp-9:1\"]}", Map.of()).body());
        assertThat(bulk).hasSize(3);
        assertThat(bulk.get(0)).isEqualTo(readJson(other));
        assertThat(bulk.get(1).isNull()).isTrue();
        assertThat(bulk.get(2)).isEqualTo(readJson(delivered));
    }
}
