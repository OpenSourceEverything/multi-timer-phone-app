package com.ose.multitimer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/** Streams one alarm tone whose audible end can be extended by overlapping cue requests. */
final class BeepPlayer implements AutoCloseable {
    private static final String TAG = "BeepPlayer";
    private static final int BYTES_PER_FRAME = 2;
    private static final int BUFFER_CHUNKS = 4;
    private static final int MAX_ZERO_WRITES = 3;
    private static final long PLAYBACK_POLL_MILLIS = 5L;

    private final Object stateLock = new Object();

    private boolean released;
    private Thread workerThread;
    private long targetFrames;
    private long playedFrames;

    /**
     * Starts a tone, or extends the current tone so it remains audible for the requested time
     * from this call. Overlapping requests never create stacked audio tracks.
     */
    long play(long durationMillis) {
        long clampedDurationMillis = Math.max(
                BeepProfile.MIN_BEEP_DURATION_MILLIS,
                Math.min(BeepProfile.MAX_BEEP_DURATION_MILLIS, durationMillis)
        );
        long requestedFrames = PcmTone.framesForMillis(clampedDurationMillis);

        synchronized (stateLock) {
            if (released) {
                return 0L;
            }

            boolean starting = workerThread == null;
            long remainingBeforeMillis = PcmTone.millisForFramesCeiling(
                    Math.max(0L, targetFrames - playedFrames)
            );
            if (starting) {
                playedFrames = 0L;
                targetFrames = requestedFrames;
                Thread worker = new Thread(this::streamTone, "MultiTimer-beep");
                workerThread = worker;
                worker.start();
            } else {
                long requestedEnd = saturatingAdd(playedFrames, requestedFrames);
                targetFrames = Math.max(targetFrames, requestedEnd);
            }
            Log.i(TAG, "beep request durationMs=" + clampedDurationMillis
                    + " action=" + (starting ? "start" : "extend")
                    + " remainingBeforeMs=" + remainingBeforeMillis);
            return PcmTone.millisForFramesCeiling(
                    Math.max(0L, targetFrames - playedFrames)
            );
        }
    }

    long remainingMillis() {
        synchronized (stateLock) {
            if (released || workerThread == null) {
                return 0L;
            }
            return PcmTone.millisForFramesCeiling(
                    Math.max(0L, targetFrames - playedFrames)
            );
        }
    }

    private void streamTone() {
        Thread owner = Thread.currentThread();
        AudioTrack audioTrack = null;
        boolean completedNormally = false;
        try {
            audioTrack = createAudioTrack();
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Alarm AudioTrack was not initialized");
            }
            audioTrack.play();
            Log.i(TAG, "AudioTrack started");

            short[] samples = new short[PcmTone.CHUNK_FRAMES];
            PcmTone.Oscillator oscillator = new PcmTone.Oscillator();
            long writtenFrames = 0L;
            long playbackWrapBase = 0L;
            long previousRawPlaybackFrames = 0L;
            boolean hasPlaybackPosition = false;

            while (isCurrentWorker(owner)) {
                long rawPlaybackFrames = Integer.toUnsignedLong(
                        audioTrack.getPlaybackHeadPosition()
                );
                if (hasPlaybackPosition
                        && rawPlaybackFrames < previousRawPlaybackFrames
                        && previousRawPlaybackFrames - rawPlaybackFrames > 0x8000_0000L) {
                    playbackWrapBase = saturatingAdd(playbackWrapBase, 1L << 32);
                }
                hasPlaybackPosition = true;
                previousRawPlaybackFrames = rawPlaybackFrames;
                long consumedFrames = Math.min(
                        writtenFrames,
                        saturatingAdd(playbackWrapBase, rawPlaybackFrames)
                );

                long currentTarget;
                synchronized (stateLock) {
                    if (workerThread != owner || released) {
                        break;
                    }
                    playedFrames = Math.max(playedFrames, consumedFrames);
                    currentTarget = targetFrames;
                }

                if (writtenFrames < currentTarget) {
                    int framesToWrite = (int) Math.min(
                            samples.length,
                            currentTarget - writtenFrames
                    );
                    oscillator.render(
                            samples,
                            framesToWrite,
                            currentTarget - writtenFrames
                    );
                    writeFully(audioTrack, samples, framesToWrite);
                    writtenFrames += framesToWrite;
                    continue;
                }

                if (consumedFrames >= currentTarget) {
                    synchronized (stateLock) {
                        if (workerThread == owner && !released) {
                            playedFrames = Math.max(playedFrames, consumedFrames);
                            if (playedFrames >= targetFrames) {
                                workerThread = null;
                                targetFrames = 0L;
                                playedFrames = 0L;
                                completedNormally = true;
                                break;
                            }
                        }
                    }
                    continue;
                }

                Thread.sleep(PLAYBACK_POLL_MILLIS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            if (!isReleased()) {
                Log.w(TAG, "The platform could not stream alarm tone playback", error);
            }
        } finally {
            clearWorker(owner);
            releaseAudioTrack(audioTrack, !completedNormally);
        }
    }

    private static AudioTrack createAudioTrack() {
        int minimumBufferBytes = AudioTrack.getMinBufferSize(
                PcmTone.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minimumBufferBytes <= 0) {
            throw new IllegalStateException(
                    "No supported alarm audio buffer size: " + minimumBufferBytes
            );
        }
        int requestedBufferBytes = Math.max(
                minimumBufferBytes,
                PcmTone.CHUNK_FRAMES * BYTES_PER_FRAME * BUFFER_CHUNKS
        );

        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(PcmTone.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(requestedBufferBytes)
                .build();
    }

    private static void writeFully(AudioTrack audioTrack, short[] samples, int frameCount) {
        int offset = 0;
        int zeroWrites = 0;
        while (offset < frameCount) {
            int written = audioTrack.write(
                    samples,
                    offset,
                    frameCount - offset,
                    AudioTrack.WRITE_BLOCKING
            );
            if (written < 0) {
                throw new IllegalStateException("Alarm audio write failed: " + written);
            }
            if (written == 0) {
                if (++zeroWrites > MAX_ZERO_WRITES) {
                    throw new IllegalStateException("Alarm audio repeatedly accepted no samples");
                }
                Thread.yield();
                continue;
            }
            zeroWrites = 0;
            offset += written;
        }
    }

    private boolean isCurrentWorker(Thread owner) {
        synchronized (stateLock) {
            return !released && workerThread == owner;
        }
    }

    private boolean isReleased() {
        synchronized (stateLock) {
            return released;
        }
    }

    private void clearWorker(Thread owner) {
        synchronized (stateLock) {
            if (workerThread == owner) {
                workerThread = null;
                targetFrames = 0L;
                playedFrames = 0L;
            }
        }
    }

    private static void releaseAudioTrack(AudioTrack audioTrack, boolean discardQueuedAudio) {
        if (audioTrack == null) {
            return;
        }
        if (discardQueuedAudio) {
            try {
                audioTrack.pause();
            } catch (RuntimeException ignored) {
                // A partially initialized or already stopped track has nothing left to pause.
            }
            try {
                audioTrack.flush();
            } catch (RuntimeException ignored) {
                // Continue releasing even if the platform rejects a flush in this state.
            }
        }
        try {
            audioTrack.stop();
        } catch (RuntimeException ignored) {
            // Release is still required if playback never entered the started state.
        }
        audioTrack.release();
    }

    @Override
    public void close() {
        Thread worker;
        synchronized (stateLock) {
            if (released) {
                return;
            }
            released = true;
            worker = workerThread;
            workerThread = null;
            targetFrames = 0L;
            playedFrames = 0L;
        }

        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
