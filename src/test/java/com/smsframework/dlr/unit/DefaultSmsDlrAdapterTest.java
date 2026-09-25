package com.smsframework.dlr.unit;

import com.smsframework.dlr.adapter.DefaultSmsDlrAdapter;
import com.smsframework.dlr.domain.NormalizedStatus;
import com.smsframework.dlr.dto.NormalizedDlr;
import com.smsframework.dlr.exception.DlrValidationException;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.smsframework.dlr.unit.AdapterTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultSmsDlrAdapterTest {

    private final DefaultSmsDlrAdapter adapter = AdapterTestSupport.defaultAdapter();

    @Test
    void normalizesTheReferencePayloadExactly() {
        NormalizedDlr d = adapter.normalize(json(TestPayloads.DEFAULT_SMS_EXAMPLE));

        assertThat(d.getSource()).isEqualTo("DEFAULT_SMS");
        assertThat(d.getMessageId()).isEqualTo("2ee98174-eec2-46b1-9b3c-baa0853c9538");
        assertThat(d.getProviderStatus()).isEqualTo("DELIVRD");
        assertThat(d.getNormalizedStatus()).isEqualTo(NormalizedStatus.DELIVERED);
        assertThat(d.getStatusCode()).isEqualTo("000");
        assertThat(d.getErrorCode()).isNull();
        assertThat(d.getMobile()).isEqualTo("917973059161");
        assertThat(d.getSender()).isEqualTo("MSEFSL");
        assertThat(d.getService()).isEqualTo("T");
        assertThat(d.getUnits()).isEqualTo(2);
        assertThat(d.getTemplateId()).isEqualTo("1507165786055955979");
        assertThat(d.getEntityId()).as("empty string becomes null").isNull();
        assertThat(d.getCorrelationId())
                .isEqualTo("75892985798379875987198579175987912757589298579837987598719857917598791275");
        assertThat(d.getSubmitAt()).isEqualTo(LocalDateTime.of(2026, 6, 22, 11, 47, 33));
        assertThat(d.getDlrReceivedAt()).isEqualTo(LocalDateTime.of(2026, 6, 22, 11, 47, 33));
    }

    @Test
    void undelivIsFailedAndCarriesErrorCode() {
        NormalizedDlr d = adapter.normalize(json(TestPayloads.defaultSms("m1", "917973059161", "UNDELIV", "034",
                "2026-06-22 11:50:00", "c1")));
        assertThat(d.getNormalizedStatus()).isEqualTo(NormalizedStatus.FAILED);
        assertThat(d.getProviderStatus()).isEqualTo("UNDELIV");
        assertThat(d.getErrorCode()).isEqualTo("034");
    }

    @Test
    void expiredAndRejected() {
        assertThat(adapter.normalize(json(TestPayloads.defaultSms("m2", "EXPIRED"))).getNormalizedStatus())
                .isEqualTo(NormalizedStatus.EXPIRED);
        assertThat(adapter.normalize(json(TestPayloads.defaultSms("m3", "REJECTED"))).getNormalizedStatus())
                .isEqualTo(NormalizedStatus.REJECTED);
        assertThat(adapter.normalize(json(TestPayloads.defaultSms("m4", "REJECTD"))).getNormalizedStatus())
                .isEqualTo(NormalizedStatus.REJECTED);
    }

    @Test
    void unknownStatusKeepsProviderStatus() {
        NormalizedDlr d = adapter.normalize(json(TestPayloads.defaultSms("m5", "ENROUTE")));
        assertThat(d.getNormalizedStatus()).isEqualTo(NormalizedStatus.UNKNOWN);
        assertThat(d.getProviderStatus()).isEqualTo("ENROUTE");
    }

    @Test
    void numericFieldsAreAccepted() {
        NormalizedDlr d = adapter.normalize(json("""
                {"payload":{"message_id":"m6","mobile":917973059161,"status":"DELIVRD","units":2,"code":0}}"""));
        assertThat(d.getMobile()).isEqualTo("917973059161");
        assertThat(d.getUnits()).isEqualTo(2);
        assertThat(d.getStatusCode()).isEqualTo("0");
    }

    @Test
    void missingMessageIdIsRejected() {
        assertThatThrownBy(() -> adapter.normalize(json("""
                {"payload":{"mobile":"917973059161","status":"DELIVRD"}}""")))
                .isInstanceOf(DlrValidationException.class)
                .hasMessage("message_id is missing");
    }

    @Test
    void blankMessageIdIsRejected() {
        assertThatThrownBy(() -> adapter.normalize(json("""
                {"payload":{"message_id":"  ","mobile":"917973059161","status":"DELIVRD"}}""")))
                .hasMessage("message_id is missing");
    }

    @Test
    void missingPayloadIsRejected() {
        assertThatThrownBy(() -> adapter.normalize(json("{\"message_id\":\"x\"}")))
                .isInstanceOf(DlrValidationException.class)
                .hasMessage("payload is missing");
    }

    @Test
    void allMissingRequiredFieldsAreReported() {
        assertThatThrownBy(() -> adapter.normalize(json("{\"payload\":{}}")))
                .hasMessage("message_id is missing; mobile is missing; status is missing");
    }

    @Test
    void wrongTypeIsRejectedNotCrashed() {
        assertThatThrownBy(() -> adapter.normalize(json("""
                {"payload":{"message_id":{"nested":true},"mobile":"1","status":"DELIVRD"}}""")))
                .isInstanceOf(DlrValidationException.class)
                .hasMessageContaining("payload.message_id");
    }

    @Test
    void nonObjectPayloadIsRejected() {
        assertThatThrownBy(() -> adapter.normalize(json("[1,2,3]")))
                .hasMessage("payload must be a JSON object");
    }

    @Test
    void oversizedFieldIsRejected() {
        String longId = "x".repeat(300);
        assertThatThrownBy(() -> adapter.normalize(json(TestPayloads.defaultSms(longId, "DELIVRD"))))
                .hasMessageContaining("message_id exceeds 255 characters");
    }

    @Test
    void badTimestampAndUnitsDoNotRejectTheDlr() {
        NormalizedDlr d = adapter.normalize(json("""
                {"payload":{"message_id":"m7","mobile":"1","status":"DELIVRD","submit_at":"yesterday","units":"two"}}"""));
        assertThat(d.getSubmitAt()).isNull();
        assertThat(d.getUnits()).isNull();
    }

    @Test
    void detectsStructure() {
        assertThat(adapter.supports(null, json(TestPayloads.DEFAULT_SMS_EXAMPLE))).isTrue();
        assertThat(adapter.supports(null, json(TestPayloads.WEBENGAGE_EXAMPLE))).isFalse();
        assertThat(adapter.supports("DEFAULT_SMS", json("{}"))).isTrue();
        assertThat(adapter.supports("WEBENGAGE", json(TestPayloads.DEFAULT_SMS_EXAMPLE))).isFalse();
    }

    @Test
    void peeksMessageIdFromInvalidPayload() {
        assertThat(adapter.peekMessageId(json("{\"payload\":{\"message_id\":\"abc\"}}"))).isEqualTo("abc");
        assertThat(adapter.peekMessageId(json("{}"))).isNull();
    }
}
