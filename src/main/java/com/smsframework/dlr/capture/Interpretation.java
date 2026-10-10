package com.smsframework.dlr.capture;

import java.util.List;

/**
 * What the receiver made of a captured request. Derived data only: it never changes the captured body.
 *
 * @param status one of {@link InterpretationStatus}
 */
public record Interpretation(
        InterpretationStatus status,
        String error,
        String source,
        String messageId,
        List<String> messageIds,
        String recipient,
        String providerStatus,
        String normalizedStatus,
        Long dlrEventId,
        String resultJson) {
}
