package com.smsframework.dlr.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DlrConfigurationTest {

    private static MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        return e;
    }

    @Test
    void securityMayBeDisabledLocally() {
        assertThatCode(() -> DlrConfiguration.validate(new DlrProperties(), env())).doesNotThrowAnyException();
    }

    @Test
    void productionRequiresSecurity() {
        assertThatThrownBy(() -> DlrConfiguration.validate(new DlrProperties(), env("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void enabledSecurityNeedsAConfiguredMechanism() {
        DlrProperties p = new DlrProperties();
        p.getSecurity().setEnabled(true);
        assertThatThrownBy(() -> DlrConfiguration.validate(p, env("prod"))).hasMessageContaining("no mechanism");

        p.getSecurity().getApiKey().setEnabled(true);
        assertThatThrownBy(() -> DlrConfiguration.validate(p, env("prod"))).hasMessageContaining("no keys");

        p.getSecurity().getApiKey().setKeys(List.of("k"));
        assertThatCode(() -> DlrConfiguration.validate(p, env("prod"))).doesNotThrowAnyException();

        p.getSecurity().getHmac().setEnabled(true);
        p.getSecurity().getHmac().setSecret("short");
        assertThatThrownBy(() -> DlrConfiguration.validate(p, env("prod"))).hasMessageContaining("16 characters");
    }
}
