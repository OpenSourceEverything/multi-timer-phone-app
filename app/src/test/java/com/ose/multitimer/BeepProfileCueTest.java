package com.ose.multitimer;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public final class BeepProfileCueTest {
    @Parameterized.Parameters(name = "({0}, {1}] -> {2}")
    public static Collection<Object[]> boundaries() {
        return Arrays.asList(new Object[][]{
                {0L, 59_999L, false},
                {0L, 60_000L, true},
                {59_999L, 60_000L, true},
                {60_000L, 60_000L, false},
                {60_000L, 119_999L, false},
                {60_000L, 120_000L, true},
                {-10L, 60_000L, true},
                {120_000L, 180_001L, true}
        });
    }

    private final long exclusiveFrom;
    private final long inclusiveTo;
    private final boolean expected;

    public BeepProfileCueTest(long exclusiveFrom, long inclusiveTo, boolean expected) {
        this.exclusiveFrom = exclusiveFrom;
        this.inclusiveTo = inclusiveTo;
        this.expected = expected;
    }

    @Test
    public void periodicCueUsesExclusiveInclusiveBoundaries() {
        BeepProfile profile = BeepProfile.periodic("Minute", 500L, 60_000L, false);
        assertEquals(expected, profile.hasCueBetween(exclusiveFrom, inclusiveTo));
    }
}
