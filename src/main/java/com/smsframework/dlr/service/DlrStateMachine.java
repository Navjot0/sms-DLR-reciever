package com.smsframework.dlr.service;

import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.domain.NormalizedStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configurable DLR state transitions (dlr.state-machine.transitions).
 *
 * <pre>
 * UNKNOWN -> SENT | DELIVERED | FAILED | EXPIRED | REJECTED
 * SENT    -> DELIVERED | FAILED | EXPIRED | REJECTED
 * DELIVERED / FAILED / EXPIRED / REJECTED -> (final, nothing by default)
 * </pre>
 *
 * Because transitions are evaluated against the current persisted state, out-of-order
 * DLRs are handled naturally: if DELIVERED arrives before SENT, the late SENT is recorded
 * as an IGNORED event and the final state stays DELIVERED.
 */
@Component
public class DlrStateMachine {

    public enum Outcome {
        /** Incoming status becomes the new current state. */
        APPLY,
        /** Same normalized status as current: repeat delivery of the same state. */
        SAME_STATE,
        /** Transition not allowed (downgrade / out-of-order / final state protection). */
        REJECT_TRANSITION
    }

    public record Decision(Outcome outcome, String reason) {
    }

    private final Map<NormalizedStatus, Set<NormalizedStatus>> transitions = new EnumMap<>(NormalizedStatus.class);

    public DlrStateMachine(DlrProperties properties) {
        for (NormalizedStatus s : NormalizedStatus.values()) {
            transitions.put(s, EnumSet.noneOf(NormalizedStatus.class));
        }
        properties.getStateMachine().getTransitions().forEach((from, toList) -> {
            NormalizedStatus fromStatus = strictParse(from);
            if (toList != null) {
                for (String to : toList) {
                    transitions.get(fromStatus).add(strictParse(to));
                }
            }
        });
    }

    public Decision evaluate(NormalizedStatus current, NormalizedStatus incoming) {
        if (current == null) {
            return new Decision(Outcome.APPLY, "first DLR for message");
        }
        if (current == incoming) {
            return new Decision(Outcome.SAME_STATE, "message already in state " + current);
        }
        if (transitions.get(current).contains(incoming)) {
            return new Decision(Outcome.APPLY, "transition " + current + " -> " + incoming);
        }
        return new Decision(Outcome.REJECT_TRANSITION,
                "transition " + current + " -> " + incoming + " not allowed (out-of-order or final-state protection)");
    }

    public boolean isFinal(NormalizedStatus status) {
        return transitions.get(status).isEmpty();
    }

    public Set<NormalizedStatus> allowedFrom(NormalizedStatus status) {
        return Set.copyOf(transitions.get(status));
    }

    private static NormalizedStatus strictParse(String value) {
        try {
            return NormalizedStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid status in dlr.state-machine.transitions: " + value
                    + " (allowed: " + List.of(NormalizedStatus.values()) + ")");
        }
    }
}
