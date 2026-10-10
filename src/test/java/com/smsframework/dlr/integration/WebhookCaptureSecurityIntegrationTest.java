package com.smsframework.dlr.integration;

import com.smsframework.dlr.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Raw captures are protected like the DLR query API (dlr.security.protect-query-api). */
@TestPropertySource(properties = {
        "dlr.security.enabled=true",
        "dlr.security.api-key.enabled=true",
        "dlr.security.api-key.keys=cap-key",
        "dlr.security.protect-query-api=true"
})
class WebhookCaptureSecurityIntegrationTest extends AbstractIntegrationTest {

    @Test
    void captureApiNeedsTheApiKeyAndCallbacksWithoutItAreNotCaptured() {
        assertThat(postDlr("{\"x\":1}", Map.of()).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_requests", Integer.class)).isZero();

        var ok = postDlr("{\"x\":1}", Map.of("X-API-Key", "cap-key"));
        assertThat(ok.statusCode()).isEqualTo(200);
        String id = ok.headers().firstValue("X-Capture-Id").orElseThrow();

        assertThat(get("/api/v1/webhooks/requests").statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/webhooks/requests/" + id + "/raw").statusCode()).isEqualTo(401);
        var authed = send(HttpRequest.newBuilder(URI.create(url("/api/v1/webhooks/requests/" + id + "/raw")))
                .header("X-API-Key", "cap-key").GET().build());
        assertThat(authed.statusCode()).isEqualTo(200);
        assertThat(authed.body()).isEqualTo("{\"x\":1}");
    }
}
