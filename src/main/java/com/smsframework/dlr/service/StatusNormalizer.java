package com.smsframework.dlr.service;

import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.domain.NormalizedStatus;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps provider statuses (DELIVRD, UNDELIV, sms_sent, ...) to {@link NormalizedStatus}
 * using configuration (dlr.status-mapping and dlr.provider-status-mapping.&lt;SOURCE&gt;).
 * Matching is case-insensitive. Unmapped values become UNKNOWN. The original provider
 * status is never modified.
 */
@Component
public class StatusNormalizer {

    private final Map<String, NormalizedStatus> global;
    private final Map<String, Map<String, NormalizedStatus>> perSource = new HashMap<>();

    public StatusNormalizer(DlrProperties properties) {
        this.global = invert(properties.getStatusMapping());
        properties.getProviderStatusMapping().forEach((source, mapping) ->
                perSource.put(source.toUpperCase(Locale.ROOT), invert(mapping)));
    }

    public NormalizedStatus normalize(String source, String providerStatus) {
        if (providerStatus == null || providerStatus.isBlank()) {
            return NormalizedStatus.UNKNOWN;
        }
        String key = providerStatus.trim().toUpperCase(Locale.ROOT);
        if (source != null) {
            Map<String, NormalizedStatus> specific = perSource.get(source.toUpperCase(Locale.ROOT));
            if (specific != null && specific.containsKey(key)) {
                return specific.get(key);
            }
        }
        return global.getOrDefault(key, NormalizedStatus.UNKNOWN);
    }

    private static Map<String, NormalizedStatus> invert(Map<String, List<String>> mapping) {
        Map<String, NormalizedStatus> inverted = new HashMap<>();
        if (mapping == null) {
            return inverted;
        }
        mapping.forEach((normalized, providerStatuses) -> {
            NormalizedStatus target = NormalizedStatus.parse(normalized);
            if (target == NormalizedStatus.UNKNOWN && !"UNKNOWN".equalsIgnoreCase(normalized)) {
                throw new IllegalStateException("Invalid normalized status in dlr status mapping: " + normalized);
            }
            if (providerStatuses != null) {
                for (String ps : providerStatuses) {
                    NormalizedStatus previous = inverted.put(ps.trim().toUpperCase(Locale.ROOT), target);
                    if (previous != null && previous != target) {
                        throw new IllegalStateException("Provider status '" + ps + "' mapped to both "
                                + previous + " and " + target);
                    }
                }
            }
        });
        return inverted;
    }
}
