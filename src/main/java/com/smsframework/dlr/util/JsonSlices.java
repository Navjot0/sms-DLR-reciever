package com.smsframework.dlr.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts the elements of a JSON array out of the original request text, exactly as they were sent (spacing, key
 * order, escapes and duplicate keys untouched). Used to keep every event of a bulk DLR callback as its own text.
 */
public final class JsonSlices {

    private static final JsonFactory FACTORY = new JsonFactory();

    private JsonSlices() {
    }

    /**
     * @param json  the whole document
     * @param field name of the top-level field holding the array, or null when the document itself is the array
     * @return one string per array element (in order), or null when the array cannot be located
     */
    public static List<String> arrayElements(String json, String field) {
        try (JsonParser p = FACTORY.createParser(json)) {
            JsonToken t = p.nextToken();
            if (field == null) {
                return t == JsonToken.START_ARRAY ? elements(p, json) : null;
            }
            if (t != JsonToken.START_OBJECT) {
                return null;
            }
            List<String> found = null;
            while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken v = p.nextToken();
                if (field.equals(name) && v == JsonToken.START_ARRAY) {
                    found = elements(p, json);          // a repeated key: the last one wins, like the parsed tree
                } else {
                    p.skipChildren();
                }
            }
            return found;
        } catch (Exception e) {
            return null;
        }
    }

    /** Parser positioned on START_ARRAY; returns with the parser on the matching END_ARRAY. */
    private static List<String> elements(JsonParser p, String json) throws Exception {
        List<String> out = new ArrayList<>();
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
            int start = (int) p.currentTokenLocation().getCharOffset();
            if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
                p.skipChildren();
            } else {
                p.getText();                                // finish the token so the location is after it
            }
            int end = (int) p.currentLocation().getCharOffset();
            out.add(json.substring(start, end));
        }
        return out;
    }
}
