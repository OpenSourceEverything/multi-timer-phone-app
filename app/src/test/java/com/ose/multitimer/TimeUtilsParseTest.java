package com.ose.multitimer;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public final class TimeUtilsParseTest {
    @Parameterized.Parameters(name = "{0} -> {1}ms")
    public static Collection<Object[]> durations() {
        return Arrays.asList(new Object[][]{
                {"0", 0L},
                {"9", 9_000L},
                {"90", 90_000L},
                {"1:30", 90_000L},
                {" 02:03 ", 123_000L},
                {"1:02:03", 3_723_000L},
                {"100:00:00", 360_000_000L}
        });
    }

    private final String input;
    private final long expectedMillis;

    public TimeUtilsParseTest(String input, long expectedMillis) {
        this.input = input;
        this.expectedMillis = expectedMillis;
    }

    @Test
    public void parsesSupportedDurationForms() {
        assertEquals(expectedMillis, TimeUtils.parseDurationMillis(input));
    }
}
