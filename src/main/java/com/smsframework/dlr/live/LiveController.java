package com.smsframework.dlr.live;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Live DLR monitor: the UI at /ui (static/ui/index.html) and the two read-only APIs it polls.
 * Polling a database cursor (instead of server push) keeps it correct behind a load balancer:
 * every instance answers from the same PostgreSQL tables.
 */
@Controller
public class LiveController {

    private final LiveRepository repository;
    private final LiveMessageRepository messages;

    public LiveController(LiveRepository repository, LiveMessageRepository messages) {
        this.repository = repository;
        this.messages = messages;
    }

    /**
     * Lookup for the UI. "&lt;id&gt;:2329" returns that one recipient's status, billing, clicks and callbacks
     * (bulk campaigns share one message_id across recipients); a bare id returns the message as a whole.
     */
    @GetMapping(value = "/api/v1/dlr/live/message", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public java.util.Map<String, Object> message(@RequestParam("id") String id) {
        return messages.lookup(id);
    }

    @GetMapping({"/", "/ui"})
    public String root() {
        return "redirect:/ui/";
    }

    @GetMapping("/ui/")
    public String ui() {
        return "forward:/ui/index.html";
    }

    /**
     * Newest events across status DLRs, billing and clicks. Without cursors: the latest `limit`.
     * With after_* cursors: only events newer than those ids.
     */
    @GetMapping(value = "/api/v1/dlr/live/feed", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public LiveFeedResponse feed(@RequestParam(name = "after_status", defaultValue = "0") long afterStatus,
                                 @RequestParam(name = "after_billing", defaultValue = "0") long afterBilling,
                                 @RequestParam(name = "after_click", defaultValue = "0") long afterClick,
                                 @RequestParam(defaultValue = "100") int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        LiveFeedResponse.Cursor cursor = new LiveFeedResponse.Cursor(
                repository.maxId("dlr_events"), repository.maxId("dlr_billing_events"), repository.maxId("dlr_click_events"));
        return new LiveFeedResponse(repository.feed(afterStatus, afterBilling, afterClick, capped), cursor);
    }

    @GetMapping(value = "/api/v1/dlr/live/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public LiveStatsResponse stats(@RequestParam(defaultValue = "60") int minutes) {
        // 0 = all time; otherwise 1 minute .. 30 days
        return repository.stats(minutes <= 0 ? 0 : Math.min(minutes, 30 * 24 * 60));
    }
}
