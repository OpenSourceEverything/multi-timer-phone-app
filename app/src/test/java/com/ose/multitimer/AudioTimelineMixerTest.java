package com.ose.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Verifies that audio cues keep their sample positions across stream writes and mixing. */
public final class AudioTimelineMixerTest {
    /** A whole render equals smaller sequential writes over the same absolute frame range. */
    @Test
    public void chunkBoundariesDoNotMoveBeepOrSpeechSamples() {
        AudioTimelineMixer.Tone tone = new AudioTimelineMixer.Tone(700L, 4_800L);
        PcmSpeechAudio.Clip speech = new PcmSpeechAudio.Clip(2_000L,
                new short[6_000]);
        short[] oneBlock = new short[12_000];
        short[] manyBlocks = new short[oneBlock.length];

        AudioTimelineMixer.render(oneBlock, 0, oneBlock.length, 0L,
                Collections.singletonList(tone), Collections.singletonList(speech));
        for (int firstFrame = 0; firstFrame < manyBlocks.length; firstFrame += 480) {
            int count = Math.min(480, manyBlocks.length - firstFrame);
            AudioTimelineMixer.render(manyBlocks, firstFrame, count, firstFrame,
                    Collections.singletonList(tone), Collections.singletonList(speech));
        }

        assertTrue(Arrays.equals(oneBlock, manyBlocks));
        assertEquals(0, manyBlocks[699]);
        assertEquals(0, manyBlocks[5_500]);
    }

    /** Speech starts at its absolute frame and ducks, rather than erases, a coincident beep. */
    @Test
    public void mixesSpeechAtItsFrameAndKeepsTheBeepUnderIt() {
        AudioTimelineMixer.Tone tone = new AudioTimelineMixer.Tone(0L, 4_800L);
        short[] samples = new short[4_800];
        AudioTimelineMixer.render(samples, 0, samples.length, 0L,
                Collections.singletonList(tone), Collections.emptyList());
        int peakFrame = positivePeakFrame(samples);
        short beepSample = samples[peakFrame];
        short speechSample = 10_000;
        short[] speechSamples = new short[samples.length];
        Arrays.fill(speechSamples, speechSample);

        AudioTimelineMixer.render(samples, 0, samples.length, 0L,
                Collections.singletonList(tone), Collections.singletonList(
                        new PcmSpeechAudio.Clip(0L, speechSamples)));

        assertTrue(beepSample > 0);
        assertEquals(speechSample + Math.round(beepSample * 0.20), samples[peakFrame]);
    }

    /** A beep uses its requested frame and remains silent outside its finite interval. */
    @Test
    public void rendersOnlyInsideTheRequestedBeepInterval() {
        short[] samples = new short[5_000];
        AudioTimelineMixer.render(samples, 0, samples.length, 0L,
                Collections.singletonList(new AudioTimelineMixer.Tone(1_000L, 2_400L)),
                Collections.emptyList());

        assertEquals(0, samples[999]);
        assertEquals(0, samples[1_000]);
        assertTrue(samples[1_500] != 0);
        assertEquals(0, samples[3_400]);
    }

    /** Simultaneous tones and speech saturate safely instead of wrapping PCM samples. */
    @Test
    public void clippingStaysWithinSignedPcmRange() {
        short[] loudSpeech = new short[1_000];
        Arrays.fill(loudSpeech, Short.MAX_VALUE);
        short[] samples = new short[1_000];

        AudioTimelineMixer.render(samples, 0, samples.length, 0L,
                Arrays.asList(new AudioTimelineMixer.Tone(0L, 1_000L),
                        new AudioTimelineMixer.Tone(0L, 1_000L)),
                Collections.singletonList(new PcmSpeechAudio.Clip(0L, loudSpeech)));

        for (short sample : samples) {
            assertTrue(sample <= Short.MAX_VALUE);
            assertTrue(sample >= Short.MIN_VALUE);
        }
    }

    /** Overlapping profiles extend one tone rather than making the signal louder. */
    @Test
    public void overlappingBeepProfilesMergeIntoOneToneInterval() {
        short[] overlapping = new short[8_000];
        short[] merged = new short[overlapping.length];
        AudioTimelineMixer.render(overlapping, 0, overlapping.length, 0L,
                Arrays.asList(new AudioTimelineMixer.Tone(0L, 4_800L),
                        new AudioTimelineMixer.Tone(2_400L, 4_800L)),
                Collections.emptyList());
        AudioTimelineMixer.render(merged, 0, merged.length, 0L,
                Collections.singletonList(new AudioTimelineMixer.Tone(0L, 7_200L)),
                Collections.emptyList());

        assertTrue(Arrays.equals(merged, overlapping));
    }

    /** Finds a positive tone sample to make the ducking assertion independent of phase. */
    private static int positivePeakFrame(short[] samples) {
        int peakFrame = -1;
        short peak = 0;
        for (int frame = 0; frame < samples.length; frame++) {
            if (samples[frame] > peak) {
                peak = samples[frame];
                peakFrame = frame;
            }
        }
        return peakFrame;
    }
}
