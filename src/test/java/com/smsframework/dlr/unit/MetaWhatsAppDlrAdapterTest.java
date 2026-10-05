package com.smsframework.dlr.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smsframework.dlr.domain.NormalizedStatus;
import com.smsframework.dlr.dto.NormalizedDlr;
import com.smsframework.dlr.exception.DlrValidationException;
import com.smsframework.dlr.meta.MetaWhatsAppDlrAdapter;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.smsframework.dlr.unit.AdapterTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaWhatsAppDlrAdapterTest {

    private final MetaWhatsAppDlrAdapter adapter = AdapterTestSupport.metaAdapter();

    static final String WEBHOOK = """
            {"object":"whatsapp_business_account","entry":[{"id":"WABA1","changes":[{"field":"messages","value":{
              "messaging_product":"whatsapp","metadata":{"display_phone_number":"15550001111","phone_number_id":"PN1"},
              "statuses":[
                {"id":"wamid.A","status":"delivered","timestamp":"1727780000","recipient_id":"919000000001",
                 "biz_opaque_callback_data":"camp-7","conversation":{"id":"conv-1"},
                 "pricing":{"billable":true,"pricing_model":"CBP","category":"marketing"}},
                {"id":"wamid.B","status":"failed","timestamp":"1727780005","recipient_id":"919000000002",
                 "errors":[{"code":131026,"title":"Message undeliverable","message":"Message undeliverable",
                            "error_data":{"details":"Receiver is incapable of receiving this message"}}]}
              ]}}]}]}""";

    @Test
    void splitsEveryStatusOfTheWebhook() {
        JsonNode w = json(WEBHOOK);
        assertThat(adapter.isWebhook(w)).isTrue();
        List<ObjectNode> statuses = adapter.statuses(w);
        assertThat(statuses).hasSize(2);
        assertThat(statuses.get(0).path("entry_id").asText()).isEqualTo("WABA1");
        assertThat(statuses.get(0).path("metadata").path("phone_number_id").asText()).isEqualTo("PN1");
    }

    @Test
    void mapsDeliveredStatus() {
        NormalizedDlr d = adapter.normalize(adapter.statuses(json(WEBHOOK)).get(0));
        assertThat(d.getSource()).isEqualTo("META");
        assertThat(d.getMessageId()).isEqualTo("wamid.A");
        assertThat(d.getProviderStatus()).isEqualTo("delivered");
        assertThat(d.getNormalizedStatus()).isEqualTo(NormalizedStatus.DELIVERED);
        assertThat(d.getMobile()).isEqualTo("919000000001");
        assertThat(d.getCorrelationId()).isEqualTo("camp-7");
        assertThat(d.getExternalMessageId()).isNull();
        assertThat(d.getRequestId()).isEqualTo("conv-1");
        assertThat(d.getService()).isEqualTo("marketing");
        assertThat(d.getSender()).isEqualTo("15550001111");
        assertThat(d.getDlrReceivedAt()).isEqualTo(LocalDateTime.of(2024, 10, 1, 16, 23, 20));  // Asia/Kolkata
    }

    @Test
    void mapsFailureWithErrorDetails() {
        NormalizedDlr d = adapter.normalize(adapter.statuses(json(WEBHOOK)).get(1));
        assertThat(d.getNormalizedStatus()).isEqualTo(NormalizedStatus.FAILED);
        assertThat(d.getStatusCode()).isEqualTo("131026");
        assertThat(d.getErrorCode()).isEqualTo("131026");
        assertThat(d.getErrorReason()).isEqualTo("Message undeliverable - Receiver is incapable of receiving this message");
    }

    @Test
    void sentAndReadAreNormalized() {
        assertThat(adapter.normalize(json("{\"id\":\"w1\",\"status\":\"sent\",\"recipient_id\":\"91\"}"))
                .getNormalizedStatus()).isEqualTo(NormalizedStatus.SENT);
        assertThat(adapter.normalize(json("{\"id\":\"w1\",\"status\":\"read\",\"recipient_id\":\"91\"}"))
                .getNormalizedStatus()).isEqualTo(NormalizedStatus.READ);
        assertThat(adapter.normalize(json("{\"id\":\"w1\",\"status\":\"deleted\",\"recipient_id\":\"91\"}"))
                .getNormalizedStatus()).isEqualTo(NormalizedStatus.UNKNOWN);
    }

    @Test
    void inboundMessageWebhookHasNoStatuses() {
        JsonNode inbound = json("""
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                  "messaging_product":"whatsapp","metadata":{},"contacts":[],"messages":[{"id":"wamid.in","type":"text"}]}}]}]}""");
        assertThat(adapter.statuses(inbound)).isEmpty();
        assertThat(adapter.nonStatusContent(inbound)).contains("messages");
    }

    @Test
    void missingIdIsRejected() {
        assertThatThrownBy(() -> adapter.normalize(json("{\"status\":{\"status\":\"sent\"}}")))
                .isInstanceOf(DlrValidationException.class).hasMessageContaining("id (wamid) is missing");
    }

    @Test
    void byDefaultAStatusWithoutMessageIdIsRejected() {
        MetaWhatsAppDlrAdapter strict = AdapterTestSupport.strictMetaAdapter();
        assertThatThrownBy(() -> strict.normalize(json("{\"id\":\"wamid.X\",\"status\":\"read\",\"recipient_id\":\"91\"}")))
                .isInstanceOf(DlrValidationException.class).hasMessageContaining("message_id is missing");
        assertThat(strict.normalize(json("{\"id\":\"wamid.X\",\"message_id\":\"m-9\",\"status\":\"read\"}"))
                .getMessageId()).isEqualTo("m-9");
        assertThat(strict.peekMessageId(json("{\"id\":\"wamid.X\"}"))).isNull();
    }

    @Test
    void platformMessageIdIsUsedInsteadOfTheWamid() {
        NormalizedDlr d = adapter.normalize(json("""
                {"field":"messages","object":"whatsapp_business_account","status":{
                  "id":"wamid.Hc0NvcW","status":"delivered","timestamp":"1791213334",
                  "message_id":"7e305e16-a9f5-4b8d-9741-34da6eb45dc4","recipient_id":"918999620083"},
                 "entry_id":"102934821739482","metadata":{"phone_number_id":"919691926477",
                 "display_phone_number":"15550001111"},"messaging_product":"whatsapp"}"""));
        assertThat(d.getMessageId()).isEqualTo("7e305e16-a9f5-4b8d-9741-34da6eb45dc4");
        assertThat(d.getExternalMessageId()).isEqualTo("wamid.Hc0NvcW");
        assertThat(d.getNormalizedStatus()).isEqualTo(NormalizedStatus.DELIVERED);
        assertThat(d.getMobile()).isEqualTo("918999620083");
        assertThat(adapter.peekMessageId(json("{\"status\":{\"id\":\"w\",\"message_id\":\"m-1\"}}"))).isEqualTo("m-1");
    }
}
