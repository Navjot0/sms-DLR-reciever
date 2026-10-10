package com.smsframework.dlr.service;

import com.smsframework.dlr.config.DlrProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import com.smsframework.dlr.capture.WebhookCaptureService;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the explicit DLR source from configured headers, then query parameters.
 * Returns null when none is supplied (payload structure detection is then used).
 * Values are upper-cased, '-' and ' ' become '_', and aliases are applied (WE -> WEBENGAGE).
 */
@Component
public class SourceResolver {

    private final DlrProperties.SourceResolution config;
    private final Map<String, String> aliases = new HashMap<>();

    public SourceResolver(DlrProperties properties) {
        this.config = properties.getSourceResolution();
        config.getAliases().forEach((k, v) -> aliases.put(canonical(k), canonical(v)));
    }

    public String resolveExplicit(HttpServletRequest request) {
        for (String header : config.getHeaders()) {
            String v = request.getHeader(header);
            if (v != null && !v.isBlank()) {
                return canonicalise(v);
            }
        }
        // Query string only: request.getParameter() would also parse (and consume) a form-encoded body,
        // which must reach the capture byte-for-byte.
        Map<String, List<String>> query = WebhookCaptureService.parseQuery(request.getQueryString());
        for (String param : config.getQueryParams()) {
            List<String> values = query.get(param);
            String v = values == null || values.isEmpty() ? null : values.get(0);
            if (v == null && request.getQueryString() == null && !hasFormBody(request)) {
                v = request.getParameter(param);   // e.g. parameters set programmatically; nothing to consume
            }
            if (v != null && !v.isBlank()) {
                return canonicalise(v);
            }
        }
        return null;
    }

    private static boolean hasFormBody(HttpServletRequest request) {
        String ct = request.getContentType();
        return ct != null && (ct.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")
                || ct.toLowerCase(Locale.ROOT).startsWith("multipart/"));
    }

    public boolean detectFromPayload() {
        return config.isDetectFromPayload();
    }

    public String canonicalise(String raw) {
        String c = canonical(raw);
        return aliases.getOrDefault(c, c);
    }

    private static String canonical(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
