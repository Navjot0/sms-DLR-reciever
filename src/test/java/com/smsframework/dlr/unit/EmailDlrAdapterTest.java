package com.smsframework.dlr.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.domain.NormalizedStatus;
import com.smsframework.dlr.dto.NormalizedDlr;
import com.smsframework.dlr.email.EmailDlrAdapter;
import com.smsframework.dlr.exception.DlrValidationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.smsframework.dlr.unit.AdapterTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailDlrAdapterTest {

    private final EmailDlrAdapter adapter = AdapterTestSupport.emailAdapter();

    static final String SES_DELIVERY = "{\"eventType\": \"Delivery\", \"mail\": {\"timestamp\": \"2026-07-23T10:15:28.000Z\", \"source\": \"news@brand.example\", \"messageId\": \"0100018f-ses-0001\", \"destination\": [\"asha@example.com\"]}, \"delivery\": {\"timestamp\": \"2026-07-23T10:15:30.000Z\", \"recipients\": [\"asha@example.com\"], \"smtpResponse\": \"250 2.0.0 OK\"}}";
    static final String SES_BOUNCE = "{\"eventType\": \"Bounce\", \"mail\": {\"timestamp\": \"2026-07-23T10:15:28.000Z\", \"source\": \"news@brand.example\", \"messageId\": \"0100018f-ses-0002\", \"destination\": [\"nobody@example.com\"]}, \"bounce\": {\"bounceType\": \"Permanent\", \"bounceSubType\": \"General\", \"timestamp\": \"2026-07-23T10:15:31.000Z\", \"bouncedRecipients\": [{\"emailAddress\": \"nobody@example.com\", \"action\": \"failed\", \"status\": \"5.1.1\", \"diagnosticCode\": \"smtp; 550 5.1.1 user unknown\"}]}}";
    static final String SNS = "{\"Type\": \"Notification\", \"MessageId\": \"sns-1\", \"TopicArn\": \"arn:aws:sns:ap-south-1:123456789012:ses-events\", \"Message\": \"{\\\"eventType\\\": \\\"Delivery\\\", \\\"mail\\\": {\\\"timestamp\\\": \\\"2026-07-23T10:15:28.000Z\\\", \\\"source\\\": \\\"news@brand.example\\\", \\\"messageId\\\": \\\"0100018f-ses-0001\\\", \\\"destination\\\": [\\\"asha@example.com\\\"]}, \\\"delivery\\\": {\\\"timestamp\\\": \\\"2026-07-23T10:15:30.000Z\\\", \\\"recipients\\\": [\\\"asha@example.com\\\"], \\\"smtpResponse\\\": \\\"250 2.0.0 OK\\\"}}\", \"Timestamp\": \"2026-07-23T10:15:30.500Z\"}";
    static final String KENSCIO = "[{\"xJob\": \"a3f9c2d1e4b5\", \"eventType\": \"DELIVER\", \"eventTimestamp\": \"2026-07-23T10:15:30Z\", \"address\": \"asha@example.com\"}, {\"xJob\": \"a3f9c2d1e4b5\", \"eventType\": \"OPEN\", \"eventTimestamp\": \"2026-07-23T10:16:02Z\", \"address\": \"asha@example.com\"}, {\"x-job\": \"b7e1d0c9a8f6\", \"event-type\": \"BOUNCE\", \"event-timestamp\": \"2026-07-23T10:15:33Z\", \"address\": \"nobody@example.com\"}]";

    @Test
    void sesDelivery() {
        NormalizedDlr d = adapter.normalize(json(SES_DELIVERY));
        assertThat(d.getSource()).isEqualTo("EMAIL");
        assertThat(d.getService()).isEqualTo("SES");
        assertThat(d.getMessageId()).isEqualTo("0100018f-ses-0001");
        assertThat(d.getProviderStatus()).isEqualTo("Delivery");
        assertThat(d.getNormalizedStatus()).isEqualTo(NormalizedStatus.DELIVERED);
        assertThat(d.getMobile()).isEqualTo("asha@example.com");
        assertThat(d.getSender()).isEqualTo("news@brand.example");
        assertThat(d.getDlrReceivedAt()).isEqualTo(LocalDateTime.of(2026, 7, 23, 15, 45, 30));   // Asia/Kolkata
    }

    @Test
    void sesBounceCarriesTheSmtpReason() {
        NormalizedDlr d = adapter.normalize(json(SES_BOUNCE));
        assertThat(d.getNormalizedStatus()).isEqualTo(NormalizedStatus.FAILED);
        assertThat(d.getMobile()).isEqualTo("nobody@example.com");
        assertThat(d.getStatusCode()).isEqualTo("5.1.1");
        assertThat(d.getErrorReason()).isEqualTo("smtp; 550 5.1.1 user unknown");
    }

    @Test
    void sesEventTypesAreNormalized() {
        String base = "{\"eventType\":\"%s\",\"mail\":{\"messageId\":\"m1\",\"destination\":[\"a@b.c\"]}}";
        assertThat(adapter.normalize(json(base.formatted("Send"))).getNormalizedStatus()).isEqualTo(NormalizedStatus.SENT);
        assertThat(adapter.normalize(json(base.formatted("Open"))).getNormalizedStatus()).isEqualTo(NormalizedStatus.READ);
        assertThat(adapter.normalize(json(base.formatted("Click"))).getNormalizedStatus()).isEqualTo(NormalizedStatus.READ);
        assertThat(adapter.normalize(json(base.formatted("Reject"))).getNormalizedStatus()).isEqualTo(NormalizedStatus.REJECTED);
        assertThat(adapter.normalize(json(base.formatted("Complaint"))).getNormalizedStatus()).isEqualTo(NormalizedStatus.UNKNOWN);
        assertThat(adapter.normalize(json(base.formatted("Send"))).getMobile()).isEqualTo("a@b.c");
    }

    @Test
    void snsNotificationIsUnwrapped() {
        JsonNode sns = json(SNS);
        assertThat(adapter.isEmailPayload(sns)).isTrue();
        List<JsonNode> events = adapter.events(sns);
        assertThat(events).hasSize(1);
        assertThat(adapter.normalize(events.get(0)).getMessageId()).isEqualTo("0100018f-ses-0001");
    }

    @Test
    void snsSubscriptionConfirmationHasNoEvents() {
        JsonNode sub = json("{\"Type\":\"SubscriptionConfirmation\",\"TopicArn\":\"arn:x\",\"SubscribeURL\":\"https://sns.example/confirm\"}");
        assertThat(adapter.isEmailPayload(sub)).isTrue();
        assertThat(adapter.events(sub)).isEmpty();
        assertThat(adapter.nonEventNote(sub)).contains("SubscribeURL").contains("https://sns.example/confirm");
    }

    @Test
    void kenscioListWithBothKeySpellings() {
        JsonNode list = json(KENSCIO);
        assertThat(adapter.isEmailPayload(list)).isTrue();
        List<JsonNode> events = adapter.events(list);
        assertThat(events).hasSize(3);
        NormalizedDlr deliver = adapter.normalize(events.get(0));
        assertThat(deliver.getService()).isEqualTo("KENSCIO");
        assertThat(deliver.getMessageId()).isEqualTo("a3f9c2d1e4b5");
        assertThat(deliver.getNormalizedStatus()).isEqualTo(NormalizedStatus.DELIVERED);
        assertThat(deliver.getMobile()).isEqualTo("asha@example.com");
        assertThat(adapter.normalize(events.get(1)).getNormalizedStatus()).isEqualTo(NormalizedStatus.READ);
        NormalizedDlr bounce = adapter.normalize(events.get(2));
        assertThat(bounce.getMessageId()).isEqualTo("b7e1d0c9a8f6");
        assertThat(bounce.getNormalizedStatus()).isEqualTo(NormalizedStatus.FAILED);
    }

    @Test
    void otherChannelsAreNotMistakenForEmail() {
        assertThat(adapter.isEmailPayload(json(com.smsframework.dlr.support.TestPayloads.WEBENGAGE_EXAMPLE))).isFalse();
        assertThat(adapter.isEmailPayload(json(com.smsframework.dlr.support.TestPayloads.DEFAULT_SMS_EXAMPLE))).isFalse();
        assertThat(adapter.isEmailPayload(json(MetaWhatsAppDlrAdapterTest.WEBHOOK))).isFalse();
        assertThat(adapter.isEmailPayload(json(RcsDlrAdapterTest.JIO))).isFalse();
        assertThat(adapter.isEmailPayload(json(RcsDlrAdapterTest.AIRTEL))).isFalse();
        assertThat(AdapterTestSupport.rcsAdapter().detect(json(SES_DELIVERY))).isNull();
    }

    @Test
    void missingMessageIdIsRejected() {
        assertThatThrownBy(() -> adapter.normalize(json("{\"eventType\":\"Delivery\",\"mail\":{}}")))
                .isInstanceOf(DlrValidationException.class).hasMessageContaining("mail.messageId is missing");
    }
}
