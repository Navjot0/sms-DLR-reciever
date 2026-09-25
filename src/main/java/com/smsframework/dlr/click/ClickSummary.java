package com.smsframework.dlr.click;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Short-link clicks of one message (APPLIED click events only).
 *
 * @param clicks        number of click callbacks received (duplicates excluded)
 * @param visitedCount  highest visited_count reported by the link service (its own running counter)
 * @param urlKeys       distinct short links clicked
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClickSummary(
        boolean clicked,
        Integer clicks,
        Integer visitedCount,
        Integer uniqueIps,
        List<String> urlKeys,
        LocalDateTime firstClickedAt,
        LocalDateTime lastClickedAt,
        String lastDestinationUrl,
        String lastDeviceType) {

    public static final ClickSummary NOT_CLICKED = new ClickSummary(false, 0, null, null, null, null, null, null, null);
}
