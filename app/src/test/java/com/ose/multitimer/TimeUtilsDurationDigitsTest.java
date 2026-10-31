package com.ose.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Arrays;

import org.junit.Test;

public final class TimeUtilsDurationDigitsTest {
    @Test
    public void formatsDigitsFromTheRightAsFixedHoursMinutesSeconds() {
        assertEquals("00:00:00", TimeUtils.formatDurationDigits(""));
        assertEquals("00:00:03", TimeUtils.formatDurationDigits("3"));
        assertEquals("00:00:30", TimeUtils.formatDurationDigits("30"));
        assertEquals("00:03:00", TimeUtils.formatDurationDigits("300"));
        assertEquals("00:30:00", TimeUtils.formatDurationDigits("3000"));
        assertEquals("12:34:56", TimeUtils.formatDurationDigits("123456"));
        assertEquals("100:00:00", TimeUtils.formatDurationDigits("1000000"));
    }

    @Test
    public void parsesValidDigitBuffersAsPositiveDurations() {
        assertEquals(3_000L, TimeUtils.parseDurationDigitsMillis("3"));
        assertEquals(30_000L, TimeUtils.parseDurationDigitsMillis("30"));
        assertEquals(180_000L, TimeUtils.parseDurationDigitsMillis("300"));
        assertEquals(1_800_000L, TimeUtils.parseDurationDigitsMillis("3000"));
        assertEquals(45_296_000L, TimeUtils.parseDurationDigitsMillis("123456"));
        assertEquals(359_999_000L, TimeUtils.parseDurationDigitsMillis("995959"));
        assertEquals(360_000_000L, TimeUtils.parseDurationDigitsMillis("1000000"));
    }

    @Test
    public void formattingRejectsNonDigitOrOversizedBuffers() {
        for (String invalid : Arrays.asList("1:30", " 30", "12a", "12345678901")) {
            expectIllegalArgument(() -> TimeUtils.formatDurationDigits(invalid));
        }
        expectIllegalArgument(() -> TimeUtils.formatDurationDigits(null));
    }

    @Test
    public void parsingRejectsEmptyZeroAndInvalidMinuteOrSecondGroups() {
        for (String invalid : Arrays.asList("", "0", "000000", "60", "6000", "126060")) {
            expectIllegalArgument(() -> TimeUtils.parseDurationDigitsMillis(invalid));
        }
        expectIllegalArgument(() -> TimeUtils.parseDurationDigitsMillis(null));
    }

    @Test
    public void derivesCompactDigitBuffersFromWholeSecondDurations() {
        assertEquals("", TimeUtils.durationDigitsFromMillis(0L));
        assertEquals("3", TimeUtils.durationDigitsFromMillis(3_000L));
        assertEquals("30", TimeUtils.durationDigitsFromMillis(30_000L));
        assertEquals("300", TimeUtils.durationDigitsFromMillis(180_000L));
        assertEquals("3000", TimeUtils.durationDigitsFromMillis(1_800_000L));
        assertEquals("123456", TimeUtils.durationDigitsFromMillis(45_296_000L));
        assertEquals("995959", TimeUtils.durationDigitsFromMillis(359_999_000L));
        assertEquals("1000000", TimeUtils.durationDigitsFromMillis(360_000_000L));
    }

    @Test
    public void compactBufferRejectsNegativeFractionalOrOutOfRangeDurations() {
        expectIllegalArgument(() -> TimeUtils.durationDigitsFromMillis(-1L));
        expectIllegalArgument(() -> TimeUtils.durationDigitsFromMillis(1_001L));
        expectIllegalArgument(() -> TimeUtils.durationDigitsFromMillis(3_600_000_000_000L));
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
