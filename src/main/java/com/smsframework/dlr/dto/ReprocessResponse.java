package com.smsframework.dlr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Result of reprocessing stored REJECTED callbacks. */
public record ReprocessResponse(int attempted, int applied, int duplicate, int ignored, int stillRejected,
                                int skipped, List<Item> results) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(Long originalEventId, Long newEventId, String messageId, String processingStatus,
                       String normalizedStatus, String rejectionReason) {
    }

    public static ReprocessResponse of(List<Item> items) {
        int applied = 0, duplicate = 0, ignored = 0, rejected = 0, skipped = 0;
        for (Item i : items) {
            switch (i.processingStatus()) {
                case "APPLIED", "PARTIAL" -> applied++;
                case "DUPLICATE" -> duplicate++;
                case "IGNORED" -> ignored++;
                case "SKIPPED" -> skipped++;
                default -> rejected++;
            }
        }
        return new ReprocessResponse(items.size(), applied, duplicate, ignored, rejected, skipped, items);
    }
}
