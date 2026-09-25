package com.smsframework.dlr.unit;

import com.smsframework.dlr.adapter.WebEngageDlrAdapter;
import com.smsframework.dlr.domain.NormalizedStatus;
import com.smsframework.dlr.dto.NormalizedDlr;
import com.smsframework.dlr.exception.DlrValidationException;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Test;

import static com.smsframework.dlr.unit.AdapterTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebEngageDlrAdapterTest {

    private final WebEngageDlrAdapter adapter = AdapterTestSupport.webEngageAdapter();

    @Test
    void normalizesTheReferencePayloadExactly() {
        NormalizedDlr d = adapter.normalize(json(TestPayloads.WEBENGAGE_EXAMPLE));

        assertThat(d.getSource()).isEqualTo("WEBENGAGE");
        assertThat(d.getMessageId()).isEqualTo("f1189190-3fab-4a74-9130-f932be1de679");
        assertThat(d.getMobile()).isEqualTo("919014305913");
        assertThat(d.getProviderStatus()).isEqualTo("sms_sent");
        assertThat(d.getNormalizedStatus()).as("sms_sent must NOT be treated as delivered").isEqualTo(NormalizedStatus.SENT);
        assertThat(d.getStatusCode()).isEqualTo("0");
        assertThat(d.getUnits()).isEqualTo(3);
        assertThat(d.getErrorCode()).isNull();
        assertThat(d.getCorrelationId()).isNull();
    }

    @Test
    void failedStatusCarriesErrorDetails() {
        NormalizedDlr d = adapter.normalize(json("""
                {"messageId":"w1","toNumber":"919014305913","status":"sms_failed","statusCode":"2003","message":"DND"}"""));
        assertThat(d.getNormalizedStatus()).isEqualTo(NormalizedStatus.FAILED);
        assertThat(d.getErrorCode()).isEqualTo("2003");
        assertThat(d.getErrorReason()).isEqualTo("DND");
    }

    @Test
    void deliveredAndExpired() {
        assertThat(adapter.normalize(json(TestPayloads.webEngage("w2", "sms_delivered", "0"))).getNormalizedStatus())
                .isEqualTo(NormalizedStatus.DELIVERED);
        assertThat(adapter.normalize(json(TestPayloads.webEngage("w3", "sms_expired", "5"))).getNormalizedStatus())
                .isEqualTo(NormalizedStatus.EXPIRED);
    }

    @Test
    void optionalFieldsMayBeAbsent() {
        NormalizedDlr d = adapter.normalize(json("""
                {"messageId":"w4","toNumber":"919014305913","status":"sms_sent"}"""));
        assertThat(d.getStatusCode()).isNull();
        assertThat(d.getUnits()).isNull();
    }

    @Test
    void missingMessageIdIsRejected() {
        assertThatThrownBy(() -> adapter.normalize(json("""
                {"toNumber":"919014305913","status":"sms_sent"}""")))
                .isInstanceOf(DlrValidationException.class)
                .hasMessage("messageId is missing");
    }

    @Test
    void missingToNumberAndStatusAreRejected() {
        assertThatThrownBy(() -> adapter.normalize(json("{\"messageId\":\"w5\"}")))
                .hasMessage("status is missing; toNumber is missing");
    }

    @Test
    void detectsStructure() {
        assertThat(adapter.supports(null, json(TestPayloads.WEBENGAGE_EXAMPLE))).isTrue();
        assertThat(adapter.supports(null, json(TestPayloads.DEFAULT_SMS_EXAMPLE))).isFalse();
        assertThat(adapter.supports("WEBENGAGE", json("{}"))).isTrue();
    }
}
