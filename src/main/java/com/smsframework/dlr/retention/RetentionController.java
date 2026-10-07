package com.smsframework.dlr.retention;

import com.smsframework.dlr.config.DlrProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shows the retention settings and lets an operator run the clean-up now instead of waiting for the timer. */
@RestController
@RequestMapping(value = "/api/v1/dlr/retention", produces = MediaType.APPLICATION_JSON_VALUE)
public class RetentionController {

    private final DataRetentionService service;
    private final DlrProperties.Retention config;

    public RetentionController(DataRetentionService service, DlrProperties properties) {
        this.service = service;
        this.config = properties.getRetention();
    }

    @GetMapping
    public Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", config.isEnabled());
        m.put("retention_days", config.getDays());
        m.put("interval_ms", config.getIntervalMs());
        m.put("batch_size", config.getBatchSize());
        return m;
    }

    @PostMapping("/run")
    public DataRetentionService.Result run() {
        return service.purge();
    }
}
