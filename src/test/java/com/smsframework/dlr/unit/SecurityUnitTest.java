package com.smsframework.dlr.unit;

import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.security.CallbackAuthenticator;
import com.smsframework.dlr.security.IpAllowlist;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityUnitTest {

    private static final byte[] BODY = "{\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "0123456789abcdef-secret";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-22T06:17:33Z"), ZoneOffset.UTC);

    @Test
    void ipAllowlistMatchesAddressesAndCidrs() {
        IpAllowlist list = new IpAllowlist(List.of("10.0.0.0/8", "192.168.1.10", "2001:db8::/32"));
        assertThat(list.allows("10.1.2.3")).isTrue();
        assertThat(list.allows("192.168.1.10")).isTrue();
        assertThat(list.allows("192.168.1.11")).isFalse();
        assertThat(list.allows("11.0.0.1")).isFalse();
        assertThat(list.allows("2001:db8::1")).isTrue();
        assertThat(list.allows("::ffff:10.0.0.5")).isTrue();
        assertThat(list.allows("evil.example.com")).isFalse();
        assertThat(list.allows(null)).isFalse();
    }

    @Test
    void disabledSecurityAllowsEverything() {
        CallbackAuthenticator auth = new CallbackAuthenticator(new DlrProperties().getSecurity(), CLOCK);
        assertThat(auth.authenticate(new MockHttpServletRequest(), BODY)).isEmpty();
    }

    @Test
    void apiKey() {
        DlrProperties.Security sec = new DlrProperties().getSecurity();
        sec.setEnabled(true);
        sec.getApiKey().setEnabled(true);
        sec.getApiKey().setKeys(List.of("k1", "k2"));
        CallbackAuthenticator auth = new CallbackAuthenticator(sec, CLOCK);

        MockHttpServletRequest ok = new MockHttpServletRequest();
        ok.addHeader("X-API-Key", "k2");
        assertThat(auth.authenticate(ok, BODY)).isEmpty();

        MockHttpServletRequest bad = new MockHttpServletRequest();
        bad.addHeader("X-API-Key", "nope");
        assertThat(auth.authenticate(bad, BODY)).hasValueSatisfying(f -> assertThat(f.httpStatus()).isEqualTo(401));
        assertThat(auth.authenticate(new MockHttpServletRequest(), BODY)).isPresent();
    }

    @Test
    void bearerToken() {
        DlrProperties.Security sec = new DlrProperties().getSecurity();
        sec.setEnabled(true);
        sec.getBearer().setEnabled(true);
        sec.getBearer().setTokens(List.of("tok"));
        CallbackAuthenticator auth = new CallbackAuthenticator(sec, CLOCK);

        MockHttpServletRequest ok = new MockHttpServletRequest();
        ok.addHeader("Authorization", "Bearer tok");
        assertThat(auth.authenticate(ok, BODY)).isEmpty();

        MockHttpServletRequest bad = new MockHttpServletRequest();
        bad.addHeader("Authorization", "Basic dG9rOg==");
        assertThat(auth.authenticate(bad, BODY)).isPresent();
    }

    @Test
    void ipAllowlistMechanism() {
        DlrProperties.Security sec = new DlrProperties().getSecurity();
        sec.setEnabled(true);
        sec.getIpAllowlist().setEnabled(true);
        sec.getIpAllowlist().setCidrs(List.of("10.0.0.0/8"));
        CallbackAuthenticator auth = new CallbackAuthenticator(sec, CLOCK);

        MockHttpServletRequest inside = new MockHttpServletRequest();
        inside.setRemoteAddr("10.2.3.4");
        assertThat(auth.authenticate(inside, BODY)).isEmpty();

        MockHttpServletRequest outside = new MockHttpServletRequest();
        outside.setRemoteAddr("8.8.8.8");
        outside.addHeader("X-Forwarded-For", "10.2.3.4"); // ignored unless trust-forwarded-for
        assertThat(auth.authenticate(outside, BODY)).hasValueSatisfying(f -> assertThat(f.httpStatus()).isEqualTo(403));
    }

    @Test
    void hmacSignatureWithAndWithoutTimestamp() {
        DlrProperties.Security sec = new DlrProperties().getSecurity();
        sec.setEnabled(true);
        sec.getHmac().setEnabled(true);
        sec.getHmac().setSecret(SECRET);
        CallbackAuthenticator auth = new CallbackAuthenticator(sec, CLOCK);

        // body-only signature, hex, optionally prefixed with sha256=
        String sig = HexFormat.of().formatHex(CallbackAuthenticator.hmac("HmacSHA256", SECRET, BODY));
        MockHttpServletRequest ok = new MockHttpServletRequest();
        ok.addHeader("X-DLR-Signature", "sha256=" + sig);
        assertThat(auth.authenticate(ok, BODY)).isEmpty();

        // timestamped signature over "ts.body"
        String ts = String.valueOf(CLOCK.millis() / 1000);
        byte[] signed = (ts + "." + new String(BODY, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest withTs = new MockHttpServletRequest();
        withTs.addHeader("X-DLR-Timestamp", ts);
        withTs.addHeader("X-DLR-Signature", HexFormat.of().formatHex(CallbackAuthenticator.hmac("HmacSHA256", SECRET, signed)));
        assertThat(auth.authenticate(withTs, BODY)).isEmpty();

        // stale timestamp (replay)
        String old = String.valueOf(CLOCK.millis() / 1000 - 3600);
        byte[] oldSigned = (old + "." + new String(BODY, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest replay = new MockHttpServletRequest();
        replay.addHeader("X-DLR-Timestamp", old);
        replay.addHeader("X-DLR-Signature", HexFormat.of().formatHex(CallbackAuthenticator.hmac("HmacSHA256", SECRET, oldSigned)));
        assertThat(auth.authenticate(replay, BODY)).isPresent();

        // tampered body
        MockHttpServletRequest tampered = new MockHttpServletRequest();
        tampered.addHeader("X-DLR-Signature", sig);
        assertThat(auth.authenticate(tampered, "{\"payload\":{\"x\":1}}".getBytes(StandardCharsets.UTF_8))).isPresent();
    }
}
