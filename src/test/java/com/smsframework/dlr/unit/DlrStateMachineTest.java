package com.smsframework.dlr.unit;

import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.service.DlrStateMachine;
import com.smsframework.dlr.service.DlrStateMachine.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static com.smsframework.dlr.domain.NormalizedStatus.DELIVERED;
import static com.smsframework.dlr.domain.NormalizedStatus.FAILED;
import static com.smsframework.dlr.domain.NormalizedStatus.SENT;
import static com.smsframework.dlr.domain.NormalizedStatus.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DlrStateMachineTest {

    private final DlrStateMachine sm = new DlrStateMachine(new DlrProperties());

    @Test
    void firstDlrIsAlwaysApplied() {
        assertThat(sm.evaluate(null, SENT).outcome()).isEqualTo(Outcome.APPLY);
        assertThat(sm.evaluate(null, UNKNOWN).outcome()).isEqualTo(Outcome.APPLY);
    }

    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @CsvSource({
            "SENT, DELIVERED, APPLY",
            "SENT, FAILED, APPLY",
            "SENT, EXPIRED, APPLY",
            "SENT, REJECTED, APPLY",
            "UNKNOWN, SENT, APPLY",
            "UNKNOWN, DELIVERED, APPLY",
            "DELIVERED, SENT, REJECT_TRANSITION",
            "DELIVERED, FAILED, REJECT_TRANSITION",
            "FAILED, DELIVERED, REJECT_TRANSITION",
            "EXPIRED, SENT, REJECT_TRANSITION",
            "SENT, UNKNOWN, REJECT_TRANSITION",
            "DELIVERED, UNKNOWN, REJECT_TRANSITION",
            "DELIVERED, DELIVERED, SAME_STATE",
            "SENT, SENT, SAME_STATE"
    })
    void defaultTransitions(String from, String to, Outcome expected) {
        assertThat(sm.evaluate(com.smsframework.dlr.domain.NormalizedStatus.valueOf(from),
                com.smsframework.dlr.domain.NormalizedStatus.valueOf(to)).outcome()).isEqualTo(expected);
    }

    @Test
    void finalStatesAreFinal() {
        // DELIVERED only allows the WhatsApp read receipt; READ itself is final
        assertThat(sm.allowedFrom(DELIVERED)).containsExactly(com.smsframework.dlr.domain.NormalizedStatus.READ);
        assertThat(sm.isFinal(com.smsframework.dlr.domain.NormalizedStatus.READ)).isTrue();
        assertThat(sm.isFinal(FAILED)).isTrue();
        assertThat(sm.isFinal(SENT)).isFalse();
    }

    @Test
    void finalOverrideCanBeConfigured() {
        DlrProperties p = new DlrProperties();
        p.getStateMachine().getTransitions().put("FAILED", new ArrayList<>(List.of("DELIVERED")));
        DlrStateMachine custom = new DlrStateMachine(p);
        assertThat(custom.evaluate(FAILED, DELIVERED).outcome()).isEqualTo(Outcome.APPLY);
        assertThat(custom.evaluate(DELIVERED, FAILED).outcome()).isEqualTo(Outcome.REJECT_TRANSITION);
    }

    @Test
    void invalidConfigurationFailsFast() {
        DlrProperties p = new DlrProperties();
        p.getStateMachine().getTransitions().put("SENT", new ArrayList<>(List.of("DELIVERD")));
        assertThatThrownBy(() -> new DlrStateMachine(p)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DELIVERD");
    }
}
