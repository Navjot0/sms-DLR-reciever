package com.smsframework.dlr.unit;

import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.domain.NormalizedStatus;
import com.smsframework.dlr.dto.NormalizedDlr;
import com.smsframework.dlr.service.DedupKeyGenerator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DedupKeyGeneratorTest {

    private final DedupKeyGenerator gen = new DedupKeyGenerator(new DlrProperties());

    private static NormalizedDlr dlr(String id, String status, LocalDateTime at) {
        return NormalizedDlr.of("DEFAULT_SMS").messageId(id).providerStatus(status).statusCode("000")
                .normalizedStatus(NormalizedStatus.DELIVERED).dlrReceivedAt(at);
    }

    @Test
    void identicalCallbacksProduceIdenticalKeys() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 22, 11, 47, 33);
        assertThat(gen.generate(dlr("m1", "DELIVRD", t))).isEqualTo(gen.generate(dlr("m1", "DELIVRD", t)));
        assertThat(gen.generate(dlr("m1", "DELIVRD", t))).hasSize(64);
    }

    @Test
    void differentStatusesAreNotDuplicates() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 22, 11, 47, 33);
        assertThat(gen.generate(dlr("m1", "SUBMITTED", t))).isNotEqualTo(gen.generate(dlr("m1", "DELIVRD", t)));
    }

    @Test
    void differentMessagesAndTimestampsAreNotDuplicates() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 22, 11, 47, 33);
        assertThat(gen.generate(dlr("m1", "DELIVRD", t))).isNotEqualTo(gen.generate(dlr("m2", "DELIVRD", t)));
        assertThat(gen.generate(dlr("m1", "DELIVRD", t))).isNotEqualTo(gen.generate(dlr("m1", "DELIVRD", t.plusSeconds(1))));
    }

    @Test
    void providerStatusIsCaseInsensitiveButMessageIdIsNot() {
        assertThat(gen.generate(dlr("m1", "delivrd", null))).isEqualTo(gen.generate(dlr("m1", "DELIVRD", null)));
        assertThat(gen.generate(dlr("abc", "DELIVRD", null))).isNotEqualTo(gen.generate(dlr("ABC", "DELIVRD", null)));
    }

    @Test
    void configurableFieldsMustIncludeMessageId() {
        DlrProperties p = new DlrProperties();
        p.getIdempotency().setKeyFields(new ArrayList<>(List.of("source", "provider_status")));
        assertThatThrownBy(() -> new DedupKeyGenerator(p)).hasMessageContaining("message_id");
        p.getIdempotency().setKeyFields(new ArrayList<>(List.of("message_id", "bogus")));
        assertThatThrownBy(() -> new DedupKeyGenerator(p)).hasMessageContaining("bogus");
    }
}
