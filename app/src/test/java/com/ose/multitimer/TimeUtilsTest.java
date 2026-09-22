package com.ose.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class TimeUtilsTest {
    @Test
    public void formatsDisplayWithHoursOnlyWhenNeeded() {
        assertEquals("00:00", TimeUtils.formatDisplay(-1L));
        assertEquals("00:59", TimeUtils.formatDisplay(59_999L));
        assertEquals("01:00", TimeUtils.formatDisplay(60_000L));
        assertEquals("1:00:01", TimeUtils.formatDisplay(3_601_000L));
        assertEquals("100:00:00", TimeUtils.formatDisplay(360_000_000L));
    }

    @Test
    public void parsesAndNormalizesScriptOffsets() {
        assertEquals(
                Arrays.asList(30_000L, 60_000L, 120_000L),
                TimeUtils.parseScriptOffsetsMillis("1:00, 30\n00:30, 2:00"));
        assertEquals(Collections.emptyList(), TimeUtils.parseScriptOffsetsMillis(" \n "));
        assertEquals(
                "00:30, 01:00, 02:00",
                TimeUtils.formatScriptOffsetsMillis(Arrays.asList(30_000L, 60_000L, 120_000L)));
    }

    @Test
    public void rejectsMalformedOrOverflowingDurations() {
        for (String invalid : Arrays.asList("", "-1", "1:60", "1:60:00", "1::2", "1:2:3:4",
                "999999999999999999999999")) {
            try {
                TimeUtils.parseDurationMillis(invalid);
                fail("Expected invalid duration: " + invalid);
            } catch (IllegalArgumentException expected) {
                // Expected.
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void scriptOffsetsMustBePositive() {
        TimeUtils.parseScriptOffsetsMillis("00:00");
    }
}
