package com.ose.multitimer;

/** Pure helpers for the continuous PCM tone streamed by {@link BeepPlayer}. */
final class PcmTone {
    static final int SAMPLE_RATE = 48_000;
    static final int CHUNK_MILLIS = 10;
    static final int CHUNK_FRAMES = SAMPLE_RATE * CHUNK_MILLIS / 1_000;
    private static final int FADE_MILLIS = 10;
    private static final double FREQUENCY_HZ = 1_000.0;
    private static final double AMPLITUDE = 0.35;

    private PcmTone() {
    }

    static long framesForMillis(long durationMillis) {
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("Duration cannot be negative");
        }
        return Math.multiplyExact(durationMillis, SAMPLE_RATE) / 1_000L;
    }

    static long millisForFramesCeiling(long frames) {
        if (frames <= 0L) {
            return 0L;
        }
        return Math.addExact(Math.multiplyExact(frames, 1_000L), SAMPLE_RATE - 1L)
                / SAMPLE_RATE;
    }

    /** Stateful oscillator with continuous phase and short attack/release ramps. */
    static final class Oscillator {
        private static final int FADE_FRAMES = SAMPLE_RATE * FADE_MILLIS / 1_000;
        private static final double RADIANS_PER_FRAME =
                2.0 * Math.PI * FREQUENCY_HZ / SAMPLE_RATE;
        private static final double SCALE = Short.MAX_VALUE * AMPLITUDE;
        private static final double MAX_GAIN_STEP = 1.0 / FADE_FRAMES;

        private double phase;
        private double gain;
        private long renderedFrames;

        void render(short[] destination, int frameCount, long framesUntilTarget) {
            if (destination == null || frameCount < 0 || frameCount > destination.length) {
                throw new IllegalArgumentException("Invalid PCM destination or frame count");
            }
            if (framesUntilTarget < frameCount) {
                throw new IllegalArgumentException("Target must include every rendered frame");
            }

            for (int frame = 0; frame < frameCount; frame++) {
                double attackGain = Math.min(1.0,
                        (renderedFrames + frame + 1.0) / FADE_FRAMES);
                double releaseGain = Math.min(1.0,
                        (framesUntilTarget - frame) / (double) FADE_FRAMES);
                double desiredGain = Math.max(0.0, Math.min(attackGain, releaseGain));
                if (gain < desiredGain) {
                    gain = Math.min(desiredGain, gain + MAX_GAIN_STEP);
                } else if (gain > desiredGain) {
                    gain = Math.max(desiredGain, gain - MAX_GAIN_STEP);
                }

                destination[frame] = (short) Math.round(Math.sin(phase) * SCALE * gain);
                phase += RADIANS_PER_FRAME;
                if (phase >= 2.0 * Math.PI) {
                    phase -= 2.0 * Math.PI;
                }
            }
            renderedFrames += frameCount;
        }
    }
}
