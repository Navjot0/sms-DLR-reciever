package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** dlr.bulk.max-events / chunk-size: a too-large batch is captured but not interpreted; small chunks still work. */
@TestPropertySource(properties = {"dlr.bulk.max-events=5", "dlr.bulk.chunk-size=2"})
class BulkDlrLimitIntegrationTest extends AbstractIntegrationTest {

    @Test
    void batchAboveTheEventLimitIsCapturedButNotInterpreted() {
        String body = BulkDlrIntegrationTest.batch("b-big", BulkDlrIntegrationTest.recipients("camp-big", 1, 6));
        JsonNode ack = readJson(postDlr(body, Map.of()).body());
        assertThat(ack.get("captured").asBoolean()).isTrue();
        assertThat(ack.get("interpretation_status").asText()).isEqualTo("TOO_LARGE");
        assertThat(ack.get("limit_exceeded").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events", Integer.class)).isZero();
        assertThat(get("/api/v1/webhooks/requests/" + ack.get("capture_id").asText() + "/raw").body()).isEqualTo(body);
    }

    @Test
    void batchAtTheLimitIsWrittenInSeveralChunks() {
        JsonNode ack = readJson(postDlr(BulkDlrIntegrationTest.batch("b-ok",
                BulkDlrIntegrationTest.recipients("camp-ok", 1, 5)), Map.of()).body());
        assertThat(ack.get("interpreted").asInt()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_batch_events", Integer.class)).isEqualTo(5);
    }
}
