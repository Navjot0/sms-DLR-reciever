package com.smsframework.dlr.util;

/**
 * Splits provider message ids of the form "&lt;message_id&gt;&lt;sep&gt;&lt;part&gt;" (e.g.
 * "c9b2e601-16f9-4448-b12c-d823c1e5e046:1") into the SMS message id and the part number of a multipart SMS.
 * Only a numeric suffix (max 6 digits) after the LAST separator is treated as a part; anything else is kept as is.
 * An empty separator disables splitting.
 */
public final class MessageIdParts {

    public record Parsed(String messageId, Integer part, String original) {
        public boolean hasPart() {
            return part != null;
        }
    }

    private MessageIdParts() {
    }

    public static Parsed parse(String id, String separator) {
        if (id == null || separator == null || separator.isEmpty()) {
            return new Parsed(id, null, id);
        }
        int at = id.lastIndexOf(separator);
        if (at > 0 && at + separator.length() < id.length()) {
            String suffix = id.substring(at + separator.length());
            if (suffix.length() <= 6 && suffix.chars().allMatch(Character::isDigit)) {
                return new Parsed(id.substring(0, at), Integer.parseInt(suffix), id);
            }
        }
        return new Parsed(id, null, id);
    }

    public static String base(String id, String separator) {
        return parse(id, separator).messageId();
    }
}
