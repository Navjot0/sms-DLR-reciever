package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Data older than dlr.retention.days (7) is deleted; newer data is untouched. */
class DataRetentionIntegrationTest extends AbstractIntegrationTest {

    private static final Map<String, String> SMS = Map.of("X-DLR-Source", "DEFAULT_SMS");

    private void age(String table, String where, int days) {
        jdbc.update("UPDATE " + table + " SET created_at = created_at - make_interval(days => " + days + ") WHERE " + where);
    }

    @Test
    void oldDataIsDeletedAndRecentDataIsKept() {
        // old message: status DLR + an exact duplicate + billing + click
        postDlr(TestPayloads.defaultSms("old-1", "DELIVRD"), SMS);
        postDlr(TestPayloads.defaultSms("old-1", "DELIVRD"), SMS);
        postDlr(TestPayloads.billing("old-1", 2, "debit", "1"), Map.of());
        postDlr(TestPayloads.CLICK_EXAMPLE, Map.of());
        // recent message
        postDlr(TestPayloads.defaultSms("new-1", "DELIVRD"), SMS);
        postDlr(TestPayloads.billing("new-1", 1, "debit", "1"), Map.of());

        age("dlr_events", "message_id = 'old-1'", 8);
        jdbc.update("UPDATE dlr_message_status SET last_received_at = last_received_at - interval '8 days' "
                + "WHERE message_id = 'old-1'");
        age("dlr_billing_events", "message_id = 'old-1'", 8);
        age("dlr_click_events", "TRUE", 8);

        JsonNode r = readJson(postDlr("/api/v1/dlr/retention/run", "", Map.of()).body());
        assertThat(r.get("retention_days").asInt()).isEqualTo(7);
        assertThat(r.at("/deleted/dlr_message_status").asLong()).isEqualTo(1);
        assertThat(r.at("/deleted/dlr_events").asLong()).isEqualTo(2);
        assertThat(r.at("/deleted/dlr_billing_events").asLong()).isEqualTo(2);
        assertThat(r.at("/deleted/dlr_click_events").asLong()).isEqualTo(1);
        assertThat(r.get("total").asLong()).isEqualTo(6);

        assertThat(getJson("/api/v1/dlr/old-1").get("received").asBoolean()).isFalse();
        JsonNode kept = getJson("/api/v1/dlr/new-1");
        assertThat(kept.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(kept.at("/billing/billed").asBoolean()).isTrue();

        // a second run finds nothing
        assertThat(readJson(postDlr("/api/v1/dlr/retention/run", "", Map.of()).body()).get("total").asLong()).isZero();
    }

    @Test
    void anOldEventOfAStillActiveMessageDoesNotBreakTheMessage() {
        // SENT 8 days ago, DELIVERED today: the old SENT event goes, the message and its current DLR stay
        postDlr(TestPayloads.defaultSms("mix-1", "SUBMITTED"), SMS);
        age("dlr_events", "message_id = 'mix-1'", 8);
        postDlr(TestPayloads.defaultSms("mix-1", "DELIVRD"), SMS);
        // a message whose only (state-backing) event is old but which was touched recently is kept whole
        postDlr(TestPayloads.defaultSms("mix-2", "DELIVRD"), SMS);
        age("dlr_events", "message_id = 'mix-2'", 8);

        JsonNode r = readJson(postDlr("/api/v1/dlr/retention/run", "", Map.of()).body());
        assertThat(r.at("/deleted/dlr_events").asLong()).isEqualTo(1);
        assertThat(r.at("/deleted/dlr_message_status").asLong()).isZero();

        assertThat(getJson("/api/v1/dlr/mix-1").get("status").asText()).isEqualTo("DELIVERED");
        assertThat(getJson("/api/v1/dlr/mix-1/json").at("/payload/status").asText()).isEqualTo("DELIVRD");
        assertThat(getJson("/api/v1/dlr/mix-2").get("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void settingsAreVisible() {
        JsonNode s = getJson("/api/v1/dlr/retention");
        assertThat(s.get("enabled").asBoolean()).isTrue();
        assertThat(s.get("retention_days").asInt()).isEqualTo(7);
    }
}
