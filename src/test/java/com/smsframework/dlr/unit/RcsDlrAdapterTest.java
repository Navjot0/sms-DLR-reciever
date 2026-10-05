package com.smsframework.dlr.unit;

import com.smsframework.dlr.domain.NormalizedStatus;
import com.smsframework.dlr.dto.NormalizedDlr;
import com.smsframework.dlr.exception.DlrValidationException;
import com.smsframework.dlr.rcs.RcsDlrAdapter;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.smsframework.dlr.unit.AdapterTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The four operator wire formats, exactly as the RCS Simulator's DlrFormatters build them. */
class RcsDlrAdapterTest {

    private final RcsDlrAdapter adapter = AdapterTestSupport.rcsAdapter();

    static final String JIO = """
            {"entityType":"STATUS_EVENT","entity":{"eventType":"MESSAGE_DELIVERED","messageId":"jio-1",
             "sendTime":"2026-07-23T10:15:30Z"},"botId":"bot-7","userPhoneNumber":"919000000001"}""";
    static final String JIO_FAILED = """
            {"entityType":"STATUS_EVENT","entity":{"eventType":"MESSAGE_FAILED","messageId":"jio-2",
             "sendTime":"2026-07-23T10:15:30Z","error":{"code":"DEVICE_OFFLINE","errCode":null,"message":"Device offline"}},
             "botId":"bot-7","userPhoneNumber":"919000000001"}""";
    static final String DOTGO = """
            {"message":{"data":"%s","attributes":{"event_type":"FAILED","business_id":"bot-9"}}}""";
    static final String VI = """
            {"event":"message_status","RCSMessage":{"msgId":"vi-1","status":"read","timestamp":"2026-07-23T10:15:30Z"},
             "messageContact":{"userContact":"+919000000003"}}""";
    static final String AIRTEL = """
            {"messageId":"at-1","eventType":"FAILED","sendTime":"2026-07-23T10:15:30Z","agentId":"agent-3",
             "error":{"message":"Message delivery failed","code":"NETWORK_FAILURE"}}""";

    @Test
    void jio() {
        assertThat(adapter.detect(json(JIO))).isEqualTo(RcsDlrAdapter.Operator.JIO);
        NormalizedDlr d = adapter.normalize(json(JIO));
        assertThat(d.getSource()).isEqualTo("RCS");
        assertThat(d.getService()).isEqualTo("JIO");
        assertThat(d.getMessageId()).isEqualTo("jio-1");
        assertThat(d.getProviderStatus()).isEqualTo("MESSAGE_DELIVERED");
        assertThat(d.getNormalizedStatus()).isEqualTo(NormalizedStatus.DELIVERED);
        assertThat(d.getMobile()).isEqualTo("919000000001");
        assertThat(d.getSender()).isEqualTo("bot-7");
        assertThat(d.getDlrReceivedAt()).isEqualTo(LocalDateTime.of(2026, 7, 23, 15, 45, 30));   // Asia/Kolkata

        NormalizedDlr f = adapter.normalize(json(JIO_FAILED));
        assertThat(f.getNormalizedStatus()).isEqualTo(NormalizedStatus.FAILED);
        assertThat(f.getStatusCode()).isEqualTo("DEVICE_OFFLINE");
        assertThat(f.getErrorReason()).isEqualTo("Device offline");
    }

    @Test
    void jioSentAndRead() {
        assertThat(adapter.normalize(json(JIO.replace("MESSAGE_DELIVERED", "MESSAGE_SENT"))).getNormalizedStatus())
                .isEqualTo(NormalizedStatus.SENT);
        assertThat(adapter.normalize(json(JIO.replace("MESSAGE_DELIVERED", "MESSAGE_READ"))).getNormalizedStatus())
                .isEqualTo(NormalizedStatus.READ);
    }

    @Test
    void dotgoBase64Envelope() {
        String payload = DOTGO.formatted("eyJtZXNzYWdlSWQiOiAiZGctMSIsICJzZW5kZXJQaG9uZU51bWJlciI6ICI5MTkwMDAwMDAwMDIiLCAiZXZlbnRUeXBlIjogIkZBSUxFRCIsICJzZW5kVGltZSI6ICIyMDI2LTA3LTIzVDEwOjE1OjMwWiIsICJyZWFzb24iOiAiVXNlciBub3QgUkNTIGNhcGFibGUiLCAiY29kZSI6ICJOT1RfUkNTX1VTRVIifQ==");
        assertThat(adapter.detect(json(payload))).isEqualTo(RcsDlrAdapter.Operator.DOTGO);
        NormalizedDlr d = adapter.normalize(json(payload));
        assertThat(d.getService()).isEqualTo("DOTGO");
        assertThat(d.getMessageId()).isEqualTo("dg-1");
        assertThat(d.getNormalizedStatus()).isEqualTo(NormalizedStatus.FAILED);
        assertThat(d.getMobile()).isEqualTo("919000000002");
        assertThat(d.getSender()).isEqualTo("bot-9");
        assertThat(d.getStatusCode()).isEqualTo("NOT_RCS_USER");
        assertThat(d.getErrorReason()).isEqualTo("User not RCS capable");
        assertThat(adapter.peekMessageId(json(payload))).isEqualTo("dg-1");
    }

    @Test
    void dotgoWithBrokenDataIsRejected() {
        assertThatThrownBy(() -> adapter.normalize(json(DOTGO.formatted("not-base64!!"))))
                .isInstanceOf(DlrValidationException.class).hasMessageContaining("base64");
    }

    @Test
    void vi() {
        assertThat(adapter.detect(json(VI))).isEqualTo(RcsDlrAdapter.Operator.VI);
        NormalizedDlr d = adapter.normalize(json(VI));
        assertThat(d.getService()).isEqualTo("VI");
        assertThat(d.getMessageId()).isEqualTo("vi-1");
        assertThat(d.getProviderStatus()).isEqualTo("read");
        assertThat(d.getNormalizedStatus()).isEqualTo(NormalizedStatus.READ);
        assertThat(d.getMobile()).isEqualTo("919000000003");      // leading + removed
    }

    @Test
    void airtel() {
        assertThat(adapter.detect(json(AIRTEL))).isEqualTo(RcsDlrAdapter.Operator.AIRTEL);
        NormalizedDlr d = adapter.normalize(json(AIRTEL));
        assertThat(d.getService()).isEqualTo("AIRTEL");
        assertThat(d.getMessageId()).isEqualTo("at-1");
        assertThat(d.getNormalizedStatus()).isEqualTo(NormalizedStatus.FAILED);
        assertThat(d.getSender()).isEqualTo("agent-3");
        assertThat(d.getStatusCode()).isEqualTo("NETWORK_FAILURE");
        assertThat(adapter.normalize(json("{\"messageId\":\"at-2\",\"eventType\":\"INTERNAL_ERROR\"}"))
                .getNormalizedStatus()).isEqualTo(NormalizedStatus.FAILED);
    }

    @Test
    void otherChannelsAreNotMistakenForRcs() {
        assertThat(adapter.detect(json(com.smsframework.dlr.support.TestPayloads.WEBENGAGE_EXAMPLE))).isNull();
        assertThat(adapter.detect(json(com.smsframework.dlr.support.TestPayloads.DEFAULT_SMS_EXAMPLE))).isNull();
        assertThat(adapter.detect(json(MetaWhatsAppDlrAdapterTest.WEBHOOK))).isNull();
        assertThat(adapter.supports(null, json("{\"hello\":1}"))).isFalse();
    }

    @Test
    void missingMessageIdIsRejected() {
        assertThatThrownBy(() -> adapter.normalize(json("{\"entityType\":\"STATUS_EVENT\",\"entity\":{\"eventType\":\"MESSAGE_SENT\"}}")))
                .isInstanceOf(DlrValidationException.class).hasMessageContaining("message id is missing");
    }
}
