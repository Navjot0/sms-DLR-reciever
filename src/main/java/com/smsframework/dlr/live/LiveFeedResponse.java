package com.smsframework.dlr.live;

import java.util.List;

/**
 * Response of GET /api/v1/dlr/live/feed. Pass the returned cursor back as
 * after_status / after_billing / after_click to receive only newer events.
 */
public record LiveFeedResponse(List<LiveFeedItem> items, Cursor cursor) {

    public record Cursor(long status, long billing, long click) {
    }
}
