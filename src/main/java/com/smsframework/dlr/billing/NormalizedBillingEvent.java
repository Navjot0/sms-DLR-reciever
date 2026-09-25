package com.smsframework.dlr.billing;

import java.math.BigDecimal;

/**
 * One billing event after validation/normalization. When {@link #rejectionReason()} is non-null the event
 * is invalid and is stored as REJECTED (other events of the same callback are still processed).
 *
 * @param messageId        correlation key (billing message id without the ":part" suffix)
 * @param billingMessageId message id exactly as sent by the provider
 */
public record NormalizedBillingEvent(
        int batchIndex,
        String rawEvent,
        String messageId,
        String billingMessageId,
        Integer partNumber,
        String transactionType,
        String product,
        Integer units,
        BigDecimal salePrice,
        String currency,
        BigDecimal surcharge,
        BigDecimal totalAmount,
        String rejectionReason) {

    public boolean valid() {
        return rejectionReason == null;
    }

    public static NormalizedBillingEvent rejected(int index, String rawEvent, String messageId, String reason) {
        return new NormalizedBillingEvent(index, rawEvent, messageId, messageId, null, null, null, null, null, null,
                null, null, reason);
    }
}
