package com.smsframework.dlr.unit;

import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.domain.NormalizedStatus;
import com.smsframework.dlr.service.StatusNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusNormalizerTest {

    private final StatusNormalizer normalizer = new StatusNormalizer(new DlrProperties());

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "DELIVRD, DELIVERED",
            "DELIVERED, DELIVERED",
            "SMS_DELIVERED, DELIVERED",
            "delivrd, DELIVERED",
            "UNDELIV, FAILED",
            "FAILED, FAILED",
            "SMS_FAILED, FAILED",
            "EXPIRED, EXPIRED",
            "SMS_EXPIRED, EXPIRED",
            "REJECTD, REJECTED",
            "REJECTED, REJECTED",
            "SMS_SENT, SENT",
            "sms_sent, SENT",
            "SUBMITTED, SENT",
            "' DELIVRD ', DELIVERED",
            "ACCEPTD, UNKNOWN",
            "something_new, UNKNOWN"
    })
    void mapsProviderStatuses(String providerStatus, NormalizedStatus expected) {
        assertThat(normalizer.normalize("DEFAULT_SMS", providerStatus)).isEqualTo(expected);
    }

    @Test
    void nullOrBlankIsUnknown() {
        assertThat(normalizer.normalize("DEFAULT_SMS", null)).isEqualTo(NormalizedStatus.UNKNOWN);
        assertThat(normalizer.normalize("DEFAULT_SMS", "  ")).isEqualTo(NormalizedStatus.UNKNOWN);
    }

    @Test
    void smsSentIsNeverDelivered() {
        assertThat(normalizer.normalize("WEBENGAGE", "sms_sent")).isEqualTo(NormalizedStatus.SENT);
    }

    @Test
    void perSourceOverrideTakesPrecedence() {
        DlrProperties p = new DlrProperties();
        p.setProviderStatusMapping(Map.of("WEBENGAGE", Map.of("DELIVERED", List.of("sms_ok"))));
        StatusNormalizer n = new StatusNormalizer(p);
        assertThat(n.normalize("WEBENGAGE", "SMS_OK")).isEqualTo(NormalizedStatus.DELIVERED);
        assertThat(n.normalize("DEFAULT_SMS", "SMS_OK")).isEqualTo(NormalizedStatus.UNKNOWN);
    }

    @Test
    void conflictingMappingFailsFast() {
        DlrProperties p = new DlrProperties();
        p.getStatusMapping().put("FAILED", List.of("DELIVRD"));
        assertThatThrownBy(() -> new StatusNormalizer(p)).isInstanceOf(IllegalStateException.class);
    }
}
