package com.smsframework.dlr.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request of POST /api/v1/dlr/verify.
 * expected_status is optional; when given, each result carries "matched" and the summary carries "all_matched".
 */
public record DlrVerificationRequest(
        @NotEmpty(message = "message_ids must not be empty") List<String> messageIds,
        String expectedStatus) {
}
