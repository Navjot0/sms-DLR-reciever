package com.smsframework.dlr.click;

import java.util.List;

/** Response of GET /api/v1/dlr/{messageId}/clicks. */
public record ClickDetailsResponse(String messageId, ClickSummary clicks, List<ClickEventView> events) {
}
