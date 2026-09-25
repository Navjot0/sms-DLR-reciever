package com.smsframework.dlr.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request of POST /api/v1/dlr/verify.
 * <ul>
 *   <li>expected_status (optional): each result carries "matched"; the summary carries "matched"/"all_matched".</li>
 *   <li>require_billing (optional, default false): a message only counts as matched when a billing DLR was also
 *       received for it.</li>
 * </ul>
 */
public record DlrVerificationRequest(
        @NotEmpty(message = "message_ids must not be empty") List<String> messageIds,
        String expectedStatus,
        Boolean requireBilling) {

    public boolean billingRequired() {
        return Boolean.TRUE.equals(requireBilling);
    }
}
