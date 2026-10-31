package com.ose.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.media.AudioFormat;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;

/** Tests deterministic TTS PCM conversion and same-timeline speech/beep composition. */
public final class PcmSpeechAudioTest {
    /** Converts 24 kHz mono PCM to 48 kHz and keeps speech below the mixer's headroom limit. */
    @Test
    public void resamplesPcm16MonoAndNormalizesPeak() {
        byte[] input = new byte[240 * 2];
        ByteBuffer pcm = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < 240; frame++) {
            pcm.putShort((short) (frame % 2 == 0 ? 12_000 : -12_000));
        }

        short[] output = PcmSpeechAudio.toOutputSamples(
                input, 24_000, AudioFormat.ENCODING_PCM_16BIT, 1);

        assertEquals(480, output.length);
        assertTrue(maxAbsolute(output) <= Math.round(Short.MAX_VALUE * 0.60));
        assertTrue(maxAbsolute(output) > 0);
    }

    /** A stereo TTS chunk is downmixed by averaging its two channels per frame. */
    @Test
    public void downmixesStereoChannels() {
        byte[] input = new byte[4 * 2 * 2];
        ByteBuffer pcm = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < 4; frame++) {
            pcm.putShort((short) 16_000);
            pcm.putShort((short) 16_000);
        }

        short[] output = PcmSpeechAudio.toOutputSamples(
                input, 48_000, AudioFormat.ENCODING_PCM_16BIT, 2);

        assertEquals(4, output.length);
        assertTrue(output[1] > 0);
        assertEquals(output[1], output[2]);
    }

    /** Decodes TTS floating-point PCM without exposing values outside full scale. */
    @Test
    public void convertsFloatPcmToBoundedSignedSamples() {
        byte[] input = new byte[3 * Float.BYTES];
        ByteBuffer pcm = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        pcm.putFloat(-0.4f);
        pcm.putFloat(0.8f);
        pcm.putFloat(0.2f);

        short[] output = PcmSpeechAudio.toOutputSamples(
                input, 48_000, AudioFormat.ENCODING_PCM_FLOAT, 1);

        assertEquals(3, output.length);
        assertTrue(output[1] > 0);
        assertTrue(maxAbsolute(output) <= Math.round(Short.MAX_VALUE * 0.60));
    }

    /** Decodes unsigned 8-bit TTS PCM and maps its midpoint to silence. */
    @Test
    public void convertsUnsignedEightBitPcm() {
        short[] output = PcmSpeechAudio.toOutputSamples(
                new byte[]{(byte) 128, (byte) 255, (byte) 0},
                48_000, AudioFormat.ENCODING_PCM_8BIT, 1);

        assertEquals(3, output.length);
        assertEquals(0, output[0]);
        assertTrue(output[1] > 0);
        assertTrue(output[2] < 0);
    }

    /** Rejects malformed PCM rather than allowing a truncated sample into the stream. */
    @Test
    public void rejectsPartialInputFrames() {
        try {
            PcmSpeechAudio.toOutputSamples(
                    new byte[]{1, 2, 3}, 48_000, AudioFormat.ENCODING_PCM_16BIT, 1);
            fail("Expected the partial PCM frame to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("partial PCM frame"));
        }
    }

    /** Places speech on the production mixer timeline and attenuates the coincident beep. */
    @Test
    public void mixesSpeechAtItsScheduledFrameAndKeepsTheBeepAudible() {
        short[] samples = new short[PcmTone.SAMPLE_RATE / 10];
        AudioTimelineMixer.render(samples, 0, samples.length, 0L,
                Collections.singletonList(
                        new AudioTimelineMixer.Tone(0L, samples.length)),
                Collections.emptyList());
        int positiveBeepFrame = positivePeakFrame(samples);
        short originalBeep = samples[positiveBeepFrame];
        short speechSample = 10_000;
        short[] clipSamples = new short[samples.length];
        java.util.Arrays.fill(clipSamples, speechSample);
        PcmSpeechAudio.Clip clip = new PcmSpeechAudio.Clip(0L, clipSamples);

        PcmSpeechAudio.mixInPlace(samples, 0, samples.length, 0L,
                Collections.singletonList(clip));

        assertTrue(originalBeep > 0);
        assertEquals(speechSample + Math.round(originalBeep * 0.20),
                samples[positiveBeepFrame]);
    }

    /** Leaves frames outside a clip unchanged and preserves their scheduled silence. */
    @Test
    public void doesNotMixSpeechBeforeItsStartFrame() {
        short[] samples = new short[1_000];
        PcmSpeechAudio.Clip clip = new PcmSpeechAudio.Clip(
                500L, new short[]{5_000, 5_000});

        PcmSpeechAudio.mixInPlace(samples, 0, samples.length, 0L,
                Collections.singletonList(clip));

        assertEquals(0, samples[499]);
        assertEquals(5_000, samples[500]);
        assertEquals(5_000, samples[501]);
        assertEquals(0, samples[502]);
    }

    /** Finds the peak magnitude of a short PCM test vector. */
    private static int maxAbsolute(short[] samples) {
        int peak = 0;
        for (short sample : samples) {
            peak = Math.max(peak, Math.abs((int) sample));
        }
        return peak;
    }

    /** Finds a positive beep sample where the mixer can prove it applies ducking. */
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
