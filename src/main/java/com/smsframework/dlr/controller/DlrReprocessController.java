package com.smsframework.dlr.controller;

import com.smsframework.dlr.dto.ReprocessResponse;
import com.smsframework.dlr.service.DlrReprocessService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Re-run stored REJECTED callbacks through the pipeline (after deploying a fix for a payload format, or to
 * apply a callback that arrived without a recognisable source).
 */
@RestController
@RequestMapping(value = "/api/v1/dlr/events", produces = MediaType.APPLICATION_JSON_VALUE)
public class DlrReprocessController {

    private final DlrReprocessService reprocessService;

    public DlrReprocessController(DlrReprocessService reprocessService) {
        this.reprocessService = reprocessService;
    }

    /** Reprocess one rejected callback. ?source=DEFAULT_SMS forces the provider. */
    @PostMapping("/{eventId}/reprocess")
    public ReprocessResponse.Item reprocessOne(@PathVariable long eventId,
                                               @RequestParam(required = false) String source) {
        return reprocessService.reprocess(eventId, source);
    }

    /** Reprocess up to ?limit= (max 1000) rejected callbacks that were not reprocessed before. */
    @PostMapping("/rejected/reprocess")
    public ReprocessResponse reprocessRejected(@RequestParam(defaultValue = "100") int limit,
                                               @RequestParam(required = false) String source) {
        return reprocessService.reprocessRejected(limit, source);
    }
}
