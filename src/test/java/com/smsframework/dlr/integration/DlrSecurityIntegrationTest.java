package com.smsframework.dlr.integration;

import com.smsframework.dlr.security.CallbackAuthenticator;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "dlr.security.enabled=true",
        "dlr.security.api-key.enabled=true",
        "dlr.security.api-key.keys=test-key-1,test-key-2",
        "dlr.security.hmac.enabled=true",
        "dlr.security.hmac.secret=integration-test-secret-0123456789"
})
class DlrSecurityIntegrationTest extends AbstractIntegrationTest {

    private static final String SECRET = "integration-test-secret-0123456789";

    private static String sign(String body) {
        return HexFormat.of().formatHex(CallbackAuthenticator.hmac("HmacSHA256", SECRET,
                body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validApiKeyAndSignatureIsAccepted() {
        String body = TestPayloads.defaultSms("sec-ok", "DELIVRD");
        var r = postDlr(body, Map.of("X-DLR-Source", "DEFAULT_SMS", "X-API-Key", "test-key-2",
                "X-DLR-Signature", "sha256=" + sign(body)));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(getJson("/api/v1/dlr/sec-ok").get("status").asText()).isEqualTo("DELIVERED");
        // the HMAC filter buffered the body; the capture still has the exact bytes, credentials masked
        assertThat(jdbc.queryForObject("SELECT convert_from(raw_body, 'UTF8') FROM webhook_requests", String.class))
                .isEqualTo(body);
        assertThat(jdbc.queryForObject("SELECT headers->'x-api-key'->>0 FROM webhook_requests", String.class))
                .isEqualTo("***");
    }

    @Test
    void missingApiKeyIsRefusedAndNothingIsStored() {
        String body = TestPayloads.defaultSms("sec-nokey", "DELIVRD");
        var r = postDlr(body, Map.of("X-DLR-Signature", sign(body)));
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events", Integer.class)).isZero();
        // an authentication failure is not a capture
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_requests", Integer.class)).isZero();
    }

    @Test
    void badSignatureIsRefused() {
        String body = TestPayloads.defaultSms("sec-badsig", "DELIVRD");
        var r = postDlr(body, Map.of("X-API-Key", "test-key-1", "X-DLR-Signature", sign(body + " ")));
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(readJson(r.body()).get("message").asText()).isEqualTo("invalid signature");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_requests", Integer.class)).isZero();
    }

    @Test
    void queryApiIsOpenUnlessProtectQueryApiIsSet() {
        assertThat(get("/api/v1/dlr/anything").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }
}
