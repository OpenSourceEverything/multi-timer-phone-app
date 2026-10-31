package com.ose.multitimer;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * Renders timer beeps and prepared speech into one deterministic PCM block.
 *
 * <p>The stream writer asks for consecutive blocks using absolute frame numbers. A cue's
 * position therefore does not depend on how large an AudioTrack write happens to be. The
 * class contains no Android playback objects, which keeps frame placement and mixing
 * independently testable on the JVM.</p>
 */
final class AudioTimelineMixer {
    /** One-channel output uses the same signed-16-bit sample rate as the existing beep player. */
    static final int SAMPLE_RATE = PcmTone.SAMPLE_RATE;

    /** A short ramp prevents a sudden waveform edge from clicking in the speaker. */
    private static final int RAMP_FRAMES = SAMPLE_RATE / 100;

    /** The timer keeps the existing one-kilohertz, 35-percent-volume beep sound. */
    private static final double BEEP_FREQUENCY_HZ = 1_000.0;
    private static final double BEEP_PEAK = Short.MAX_VALUE * 0.35;
    private static final double TWO_PI = Math.PI * 2.0;

    /** No instance is needed because rendering depends only on its input samples and cues. */
    private AudioTimelineMixer() {
    }

    /**
     * Fills a PCM output block with tones, then mixes any speech clips over those tones.
     *
     * @param destination sample array owned by the AudioTrack writer
     * @param offset first array element to fill
     * @param frameCount number of mono samples to fill
     * @param firstStreamFrame absolute frame position of destination[offset]
     * @param tones scheduled beep intervals that may overlap this output block
     * @param speech scheduled speech clips that may overlap this output block
     */
    static void render(short[] destination, int offset, int frameCount, long firstStreamFrame,
            List<Tone> tones, List<PcmSpeechAudio.Clip> speech) {
        if (destination == null || tones == null || speech == null || offset < 0
                || frameCount < 0 || offset > destination.length - frameCount
                || firstStreamFrame < 0L) {
            throw new IllegalArgumentException("Invalid audio output buffer or frame range");
        }

        List<Tone> mergedTones = mergeOverlappingTones(tones);
        renderPrepared(destination, offset, frameCount, firstStreamFrame, mergedTones, speech);
    }

    /** Renders already merged, immutable cue snapshots without sorting on the audio thread. */
    static void renderPrepared(short[] destination, int offset, int frameCount,
            long firstStreamFrame, List<Tone> tones, List<PcmSpeechAudio.Clip> speech) {
        if (destination == null || tones == null || speech == null || offset < 0
                || frameCount < 0 || offset > destination.length - frameCount
                || firstStreamFrame < 0L) {
            throw new IllegalArgumentException("Invalid audio output buffer or frame range");
        }
        for (int outputFrame = 0; outputFrame < frameCount; outputFrame++) {
            long streamFrame = firstStreamFrame + outputFrame;
            double mixedTone = 0.0;
            for (Tone tone : tones) {
                long toneFrame = streamFrame - tone.startFrame;
                if (0L <= toneFrame && toneFrame < tone.durationFrames) {
                    mixedTone += toneSample(toneFrame, tone.durationFrames);
                }
            }
            destination[offset + outputFrame] = saturate(mixedTone);
        }

        // Speech mixing ducks a coincident beep while retaining a quiet audible beep under it.
        PcmSpeechAudio.mixInPlace(destination, offset, frameCount, firstStreamFrame, speech);
    }

    /** Merges touching beep intervals so overlapping timers do not stack identical tones. */
    static List<Tone> mergeOverlappingTones(List<Tone> tones) {
        if (tones.size() < 2) {
            return tones;
        }
        List<Tone> sorted = new ArrayList<>(tones);
        sorted.sort(Comparator.comparingLong(tone -> tone.startFrame));
        List<Tone> merged = new ArrayList<>(sorted.size());
        for (Tone tone : sorted) {
            if (merged.isEmpty()) {
                merged.add(tone);
                continue;
            }
            Tone previous = merged.get(merged.size() - 1);
            if (previous.endFrame() < tone.startFrame) {
                merged.add(tone);
                continue;
            }
            long endFrame = Math.max(previous.endFrame(), tone.endFrame());
            merged.set(merged.size() - 1,
                    new Tone(previous.startFrame, endFrame - previous.startFrame));
        }
        return merged;
    }

    /** Creates a finite beep interval at an absolute output frame. */
    static final class Tone {
        final long startFrame;
        final long durationFrames;

        Tone(long startFrame, long durationFrames) {
            if (startFrame < 0L || durationFrames <= 0L
                    || startFrame > Long.MAX_VALUE - durationFrames) {
                throw new IllegalArgumentException("A beep needs a valid start and duration");
            }
            this.startFrame = startFrame;
            this.durationFrames = durationFrames;
        }

        long endFrame() {
            return startFrame + durationFrames;
        }
    }

    /** Generates one ramped sine-wave sample at a cue-relative frame. */
    private static double toneSample(long toneFrame, long durationFrames) {
        double attack = Math.min(1.0, (toneFrame + 1.0) / RAMP_FRAMES);
        double release = Math.min(1.0, (durationFrames - toneFrame) / (double) RAMP_FRAMES);
        double envelope = Math.max(0.0, Math.min(attack, release));
        double phase = TWO_PI * BEEP_FREQUENCY_HZ * toneFrame / SAMPLE_RATE;
        return Math.sin(phase) * BEEP_PEAK * envelope;
    }

    /** Clips a mixed sample to the range that a signed-16-bit AudioTrack can represent. */
    private static short saturate(double sample) {
        return (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, Math.round(sample)));
    }
}
