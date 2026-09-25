package com.smsframework.dlr.billing;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Billing for one message, aggregated over its APPLIED billing events.
 * Debit types add, credit types (credit / refund / reversal) subtract.
 *
 * @param units       net billed units (debit units - credit units)
 * @param totalAmount net billed amount (debit total_amount - credit total_amount)
 * @param currency    the currency, or "MIXED" if events used different currencies
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BillingSummary(
        boolean billed,
        Integer events,
        Integer parts,
        Integer units,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        BigDecimal totalAmount,
        String currency,
        OffsetDateTime lastBilledAt) {

    public static final BillingSummary NOT_BILLED =
            new BillingSummary(false, 0, null, null, null, null, null, null, null);
}
