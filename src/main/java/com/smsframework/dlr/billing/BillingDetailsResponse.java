package com.smsframework.dlr.billing;

import java.util.List;

/** Response of GET /api/v1/dlr/{messageId}/billing. */
public record BillingDetailsResponse(String messageId, BillingSummary billing, List<BillingEventView> events) {
}
