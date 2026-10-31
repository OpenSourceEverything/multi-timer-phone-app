package com.ose.multitimer;

import android.media.AudioFormat;

import java.util.List;

/** Converts synthesized speech to the shared 48 kHz output format and mixes it by frame. */
final class PcmSpeechAudio {
    /** Keep synthesized speech below full scale so the beep can be mixed safely. */
    private static final double SPEECH_PEAK = 0.60;
    /** Reduce, but do not remove, a beep that coincides with spoken audio. */
    private static final double BEEP_DUCK_GAIN = 0.20;
    /** Fade clip edges over five milliseconds to avoid clicks at speech boundaries. */
    private static final int EDGE_RAMP_FRAMES = PcmTone.SAMPLE_RATE / 200;

    private PcmSpeechAudio() {
        // This helper is pure PCM conversion and mixing; it has no instance state.
    }

    /**
     * Converts TTS PCM bytes to normalized 48 kHz mono signed-16-bit samples.
     *
     * @param bytes chunk-ordered TTS bytes captured for one complete utterance
     * @param sampleRate input frames per second reported by TTS
     * @param encoding Android PCM encoding reported by TTS
     * @param channelCount input channels reported by TTS
     * @return mono samples ready for the shared timer AudioTrack
     */
    static short[] toOutputSamples(byte[] bytes, int sampleRate, int encoding,
            int channelCount) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("TTS returned no PCM audio");
        }
        if (sampleRate <= 0 || channelCount <= 0) {
            throw new IllegalArgumentException("TTS returned an invalid audio format");
        }

        int bytesPerSample = bytesPerSample(encoding);
        int bytesPerFrame = Math.multiplyExact(bytesPerSample, channelCount);
        if (bytes.length % bytesPerFrame != 0) {
            throw new IllegalArgumentException("TTS audio ends in a partial PCM frame");
        }
        int inputFrameCount = bytes.length / bytesPerFrame;
        if (inputFrameCount == 0) {
            throw new IllegalArgumentException("TTS returned no complete PCM frames");
        }

        float[] mono = decodeToMono(bytes, inputFrameCount, bytesPerSample,
                channelCount, encoding);
        double peak = findPeak(mono);
        if (peak < 1.0e-6) {
            throw new IllegalArgumentException("TTS audio is silent");
        }

        long outputFrameCountLong = Math.max(1L, Math.round(
                inputFrameCount * (double) PcmTone.SAMPLE_RATE / sampleRate));
        if (outputFrameCountLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("TTS utterance is too large to mix");
        }
        int outputFrameCount = (int) outputFrameCountLong;
        short[] output = new short[outputFrameCount];
        double gain = SPEECH_PEAK / peak;

        for (int outputFrame = 0; outputFrame < outputFrameCount; outputFrame++) {
            double inputPosition = outputFrame * (double) sampleRate
                    / PcmTone.SAMPLE_RATE;
            int inputStart = Math.min((int) inputPosition, inputFrameCount - 1);
            int inputEnd = Math.min(inputStart + 1, inputFrameCount - 1);
            double fraction = inputPosition - inputStart;
            double interpolated = mono[inputStart]
                    + (mono[inputEnd] - mono[inputStart]) * fraction;

            int edgeDistance = Math.min(outputFrame, outputFrameCount - 1 - outputFrame);
            double edgeGain = Math.min(1.0, (edgeDistance + 1.0) / EDGE_RAMP_FRAMES);
            output[outputFrame] = saturate(interpolated * gain * edgeGain * Short.MAX_VALUE);
        }
        return output;
    }

    /** Mixes all speech clips into a chunk that already contains the periodic beep. */
    static void mixInPlace(short[] destination, int offsetFrames, int frameCount,
            long firstStreamFrame, List<Clip> clips) {
        if (destination == null || clips == null || offsetFrames < 0 || frameCount < 0
                || offsetFrames > destination.length - frameCount || firstStreamFrame < 0L) {
            throw new IllegalArgumentException("Invalid speech-mix buffer or frame range");
        }

        for (int frame = 0; frame < frameCount; frame++) {
            long streamFrame = firstStreamFrame + frame;
            double speechSum = 0.0;
            boolean speechIsActive = false;
            for (Clip clip : clips) {
                long clipFrame = streamFrame - clip.startFrame;
                if (clipFrame >= 0L && clipFrame < clip.samples.length) {
                    speechSum += clip.samples[(int) clipFrame];
                    speechIsActive = true;
                }
            }
            if (speechIsActive) {
                int index = offsetFrames + frame;
                double combined = destination[index] * BEEP_DUCK_GAIN + speechSum;
                destination[index] = saturate(combined);
            }
        }
    }

    /** Immutable speech samples positioned on the shared AudioTrack timeline. */
    static final class Clip {
        final long startFrame;
        final short[] samples;

        Clip(long startFrame, short[] samples) {
            if (startFrame < 0L || samples == null || samples.length == 0) {
                throw new IllegalArgumentException("Speech clip needs a start frame and samples");
            }
            this.startFrame = startFrame;
            this.samples = samples.clone();
        }
    }

    /** Decodes supported PCM formats and averages channels to one mono stream. */
    private static float[] decodeToMono(byte[] bytes, int frameCount, int bytesPerSample,
            int channels, int encoding) {
        float[] mono = new float[frameCount];
        int frameBytes = bytesPerSample * channels;
        for (int frame = 0; frame < frameCount; frame++) {
            double channelSum = 0.0;
            int frameOffset = frame * frameBytes;
            for (int channel = 0; channel < channels; channel++) {
                int offset = frameOffset + channel * bytesPerSample;
                channelSum += decodeSample(bytes, offset, encoding);
            }
            mono[frame] = (float) (channelSum / channels);
        }
        return mono;
    }

    /** Reads one little-endian PCM sample into the normalized -1.0 to 1.0 range. */
    private static float decodeSample(byte[] bytes, int offset, int encoding) {
        if (AudioFormat.ENCODING_PCM_8BIT == encoding) {
            return ((bytes[offset] & 0xff) - 128) / 128.0f;
        }
        if (AudioFormat.ENCODING_PCM_16BIT == encoding) {
            int value = (bytes[offset] & 0xff) | (bytes[offset + 1] << 8);
            return (short) value / 32768.0f;
        }
        int bits = (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | (bytes[offset + 3] << 24);
        float value = Float.intBitsToFloat(bits);
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0.0f;
        }
        return Math.max(-1.0f, Math.min(1.0f, value));
    }

    /** Returns the encoded sample width for formats exposed by TTS synthesis callbacks. */
    private static int bytesPerSample(int encoding) {
        if (AudioFormat.ENCODING_PCM_8BIT == encoding) {
            return 1;
        }
        if (AudioFormat.ENCODING_PCM_16BIT == encoding) {
            return 2;
        }
        if (AudioFormat.ENCODING_PCM_FLOAT == encoding) {
            return 4;
        }
        throw new IllegalArgumentException("Unsupported TTS PCM encoding: " + encoding);
    }

    /** Finds the largest absolute mono sample for conservative clip normalization. */
    private static double findPeak(float[] samples) {
        double peak = 0.0;
        for (float sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    /** Clips mixed audio to the signed-16-bit output range. */
    private static short saturate(double value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
    }
}
