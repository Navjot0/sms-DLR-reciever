package com.smsframework.dlr.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsframework.dlr.config.DlrProperties;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Best-effort extraction of message id, status and recipient from a payload no DLR adapter recognises.
 * Field names come from dlr.capture.*-fields (in priority order). A plain "id" is never taken as the message id
 * unless it is configured. JSON is searched breadth first (top level wins) up to dlr.capture.extract-max-depth;
 * form-encoded bodies are read as key=value pairs. Nothing is guessed for other formats.
 */
@Component
public class GenericFieldExtractor {

    public record Extracted(String messageId, String status, String recipient) {
        public static final Extracted NONE = new Extracted(null, null, null);
    }

    private final ObjectMapper mapper;
    private final DlrProperties.Capture config;

    public GenericFieldExtractor(ObjectMapper mapper, DlrProperties properties) {
        this.mapper = mapper;
        this.config = properties.getCapture();
    }

    public Extracted extract(String text, String contentType) {
        if (text == null || text.isBlank()) {
            return Extracted.NONE;
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("application/x-www-form-urlencoded")) {
            return fromMap(parseForm(text));
        }
        String t = text.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                JsonNode json = mapper.readTree(t);
                if (json != null && json.isContainerNode()) {
                    return fromJson(json);
                }
            } catch (Exception ignored) {
                // malformed: nothing reliable to extract
            }
        }
        return Extracted.NONE;
    }

    private Extracted fromJson(JsonNode root) {
        return new Extracted(find(root, config.getMessageIdFields()), find(root, config.getStatusFields()),
                find(root, config.getRecipientFields()));
    }

    /** Breadth-first: the shallowest match of the highest-priority field name wins. */
    private String find(JsonNode root, List<String> fields) {
        for (String field : fields) {
            Deque<JsonNode> level = new ArrayDeque<>();
            level.add(root);
            for (int depth = 0; depth <= config.getExtractMaxDepth() && !level.isEmpty(); depth++) {
                Deque<JsonNode> next = new ArrayDeque<>();
                for (JsonNode n : level) {
                    if (n.isObject()) {
                        JsonNode v = n.get(field);
                        if (v != null && v.isValueNode() && !v.isNull() && !v.asText().isBlank()) {
                            return truncate(v.asText());
                        }
                        n.elements().forEachRemaining(c -> { if (c.isContainerNode()) next.add(c); });
                    } else if (n.isArray()) {
                        int i = 0;
                        for (JsonNode c : n) {
                            if (i++ >= 50) {
                                break;
                            }
                            if (c.isContainerNode()) {
                                next.add(c);
                            }
                        }
                    }
                }
                level = next;
            }
        }
        return null;
    }

    private Extracted fromMap(Map<String, String> m) {
        return new Extracted(first(m, config.getMessageIdFields()), first(m, config.getStatusFields()),
                first(m, config.getRecipientFields()));
    }

    private static String first(Map<String, String> m, List<String> fields) {
        for (String f : fields) {
            String v = m.get(f);
            if (v != null && !v.isBlank()) {
                return truncate(v);
            }
        }
        return null;
    }

    /** a=1&b=2 (URL-decoded); the first value of a repeated key wins. */
    public static Map<String, String> parseForm(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = decode(eq < 0 ? pair : pair.substring(0, eq));
            String v = eq < 0 ? "" : decode(pair.substring(eq + 1));
            m.putIfAbsent(k, v);
        }
        return m;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static String truncate(String s) {
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
