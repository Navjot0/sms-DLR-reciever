package com.smsframework.dlr.unit;

import com.smsframework.dlr.util.DlrValues;
import com.smsframework.dlr.util.MobileMasker;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class UtilTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void masksMobileNumbers() {
        assertThat(MobileMasker.mask("917973059161")).isEqualTo("9179******61");
        assertThat(MobileMasker.mask("919014305913")).isEqualTo("9190******13");
        assertThat(MobileMasker.mask("12345")).isEqualTo("12***");
        assertThat(MobileMasker.mask("123")).isEqualTo("***");
        assertThat(MobileMasker.mask(null)).isNull();
    }

    @Test
    void parsesProviderTimestamps() {
        LocalDateTime expected = LocalDateTime.of(2026, 6, 22, 11, 47, 33);
        assertThat(DlrValues.parseTimestamp("2026-06-22 11:47:33", IST)).contains(expected);
        assertThat(DlrValues.parseTimestamp("2026-06-22T11:47:33", IST)).contains(expected);
        assertThat(DlrValues.parseTimestamp("2026-06-22T06:17:33Z", IST)).contains(expected);
        assertThat(DlrValues.parseTimestamp("1782109053", IST)).contains(expected);      // epoch seconds
        assertThat(DlrValues.parseTimestamp("1782109053000", IST)).contains(expected);   // epoch millis
        assertThat(DlrValues.parseTimestamp("not a date", IST)).isEmpty();
        assertThat(DlrValues.parseTimestamp("", IST)).isEmpty();
    }

    @Test
    void parsesIntegersLeniently() {
        assertThat(DlrValues.parseInteger("2")).isEqualTo(2);
        assertThat(DlrValues.parseInteger(" 3 ")).isEqualTo(3);
        assertThat(DlrValues.parseInteger("3.0")).isEqualTo(3);
        assertThat(DlrValues.parseInteger("x")).isNull();
        assertThat(DlrValues.parseInteger(null)).isNull();
    }
}
