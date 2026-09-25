package com.smsframework.dlr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smsframework.dlr.domain.ProcessingStatus;

/**
 * Acknowledgement returned to the DLR provider. Automation must NOT rely on this;
 * it must verify the persisted DLR via GET /api/v1/dlr/{messageId} or POST /api/v1/dlr/verify.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DlrReceiveResponse(
        Long eventId,
        String source,
        String messageId,
        ProcessingStatus processingStatus,
        String normalizedStatus,
        String currentStatus,
        String rejectionReason,
        String note) {
}
