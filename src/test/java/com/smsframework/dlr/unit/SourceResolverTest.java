package com.smsframework.dlr.unit;

import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.service.SourceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SourceResolverTest {

    private final SourceResolver resolver = new SourceResolver(new DlrProperties());

    @Test
    void headerTakesPrecedenceOverQueryParameter() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("X-DLR-Source", "webengage");
        r.setParameter("source", "DEFAULT_SMS");
        assertThat(resolver.resolveExplicit(r)).isEqualTo("WEBENGAGE");
    }

    @Test
    void providerHeaderAndQueryParameterAndAliases() {
        MockHttpServletRequest h = new MockHttpServletRequest();
        h.addHeader("X-DLR-Provider", "default-sms");
        assertThat(resolver.resolveExplicit(h)).isEqualTo("DEFAULT_SMS");

        MockHttpServletRequest q = new MockHttpServletRequest();
        q.setParameter("provider", "we");
        assertThat(resolver.resolveExplicit(q)).isEqualTo("WEBENGAGE");
    }

    @Test
    void noSourceMeansDetection() {
        assertThat(resolver.resolveExplicit(new MockHttpServletRequest())).isNull();
    }
}
