package com.smsframework.dlr.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Optional;

/**
 * Lenient value parsing for provider payloads. Parsing problems never reject a DLR
 * (the raw payload is always kept); the unparseable value simply becomes null.
 */
public final class DlrValues {

    private static final DateTimeFormatter SPACE_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter();

    private DlrValues() {
    }

    public static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    public static Integer parseInteger(String v) {
        String t = blankToNull(v);
        if (t == null) {
            return null;
        }
        try {
            return Integer.valueOf(t);
        } catch (NumberFormatException e) {
            try {
                double d = Double.parseDouble(t);
                return (d == Math.rint(d) && !Double.isInfinite(d)) ? (int) d : null;
            } catch (NumberFormatException e2) {
                return null;
            }
        }
    }

    /**
     * Accepts "yyyy-MM-dd HH:mm:ss[.SSS]", ISO local/offset date-times and epoch seconds/millis.
     * Zoned values are converted to the given zone; unzoned values are kept as sent by the provider.
     */
    public static Optional<LocalDateTime> parseTimestamp(String v, ZoneId zone) {
        String t = blankToNull(v);
        if (t == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(t, SPACE_FORMAT));
        } catch (DateTimeParseException ignored) {
            // try next
        }
        try {
            return Optional.of(LocalDateTime.parse(t));
        } catch (DateTimeParseException ignored) {
            // try next
        }
        try {
            return Optional.of(OffsetDateTime.parse(t).atZoneSameInstant(zone).toLocalDateTime());
        } catch (DateTimeParseException ignored) {
            // try next
        }
        if (t.chars().allMatch(Character::isDigit) && t.length() >= 9 && t.length() <= 13) {
            long n = Long.parseLong(t);
            Instant instant = t.length() >= 12 ? Instant.ofEpochMilli(n) : Instant.ofEpochSecond(n);
            return Optional.of(LocalDateTime.ofInstant(instant, zone));
        }
        return Optional.empty();
    }
}
