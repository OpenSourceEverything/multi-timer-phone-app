package com.ose.multitimer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PcmToneTest {
    @Test
    public void durationMapsToExpectedFrames() {
        assertEquals(2_400L, PcmTone.framesForMillis(50L));
        assertEquals(24_000L, PcmTone.framesForMillis(500L));
        assertEquals(96_000L, PcmTone.framesForMillis(2_000L));
        assertEquals(480_000L, PcmTone.framesForMillis(10_000L));
    }

    @Test
    public void frameCountRoundsUpWhenConvertedToMillis() {
        assertEquals(0L, PcmTone.millisForFramesCeiling(0L));
        assertEquals(1L, PcmTone.millisForFramesCeiling(1L));
        assertEquals(1L, PcmTone.millisForFramesCeiling(48L));
        assertEquals(2L, PcmTone.millisForFramesCeiling(49L));
        assertEquals(500L, PcmTone.millisForFramesCeiling(24_000L));
    }

    @Test
    public void oscillatorKeepsPhaseAndEnvelopeContinuousAcrossChunks() {
        short[] oneChunk = new short[1_000];
        PcmTone.Oscillator oneChunkOscillator = new PcmTone.Oscillator();
        oneChunkOscillator.render(oneChunk, oneChunk.length, oneChunk.length);

        short[] firstHalf = new short[500];
        short[] secondHalf = new short[500];
        PcmTone.Oscillator splitOscillator = new PcmTone.Oscillator();
        splitOscillator.render(firstHalf, firstHalf.length, 1_000L);
        splitOscillator.render(secondHalf, secondHalf.length, 500L);

        short[] splitChunks = new short[1_000];
        System.arraycopy(firstHalf, 0, splitChunks, 0, firstHalf.length);
        System.arraycopy(secondHalf, 0, splitChunks, firstHalf.length, secondHalf.length);
        assertArrayEquals(oneChunk, splitChunks);
    }

    @Test
    public void oscillatorFadesInAndOutAroundAnAudibleMiddle() {
        short[] samples = new short[4_800];
        PcmTone.Oscillator oscillator = new PcmTone.Oscillator();
        oscillator.render(samples, samples.length, samples.length);

        double beginningEnergy = rootMeanSquare(samples, 0, 120);
        double middleEnergy = rootMeanSquare(samples, 2_340, 120);
        double endingEnergy = rootMeanSquare(samples, samples.length - 120, 120);

        assertTrue(beginningEnergy > 0.0);
        assertTrue(middleEnergy > beginningEnergy * 2.0);
        assertTrue(middleEnergy > endingEnergy * 2.0);
        assertTrue(middleEnergy < Short.MAX_VALUE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeDurationIsRejected() {
        PcmTone.framesForMillis(-1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void renderRejectsTargetBeforeChunkEnd() {
        new PcmTone.Oscillator().render(new short[100], 100, 99L);
    }

    private static double rootMeanSquare(short[] samples, int offset, int length) {
        double squares = 0.0;
        for (int index = offset; index < offset + length; index++) {
            squares += (double) samples[index] * samples[index];
        }
        return Math.sqrt(squares / length);
    }
}
