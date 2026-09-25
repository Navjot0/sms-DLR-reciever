package com.smsframework.dlr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/** Response of POST /api/v1/dlr/verify (automation verification). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DlrVerificationResponse(
        int total,
        int received,
        int delivered,
        int failed,
        int expired,
        int rejected,
        int sent,
        int unknown,
        int missing,
        /* messages with at least one APPLIED billing DLR / without any */
        int billed,
        int billingMissing,
        /* messages with at least one short-link click / without any */
        int clicked,
        int clickMissing,
        String expectedStatus,
        Boolean requireBilling,
        Boolean requireClick,
        Integer matched,
        Boolean allMatched,
        List<Result> results) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(
            String messageId,
            boolean received,
            String status,
            String providerStatus,
            String statusCode,
            String source,
            String correlationId,
            boolean billed,
            Integer billedUnits,
            BigDecimal billedAmount,
            String currency,
            boolean clicked,
            Integer clickCount,
            Boolean matched) {
    }
}
