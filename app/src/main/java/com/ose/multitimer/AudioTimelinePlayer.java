package com.ose.multitimer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays timer beeps and synthesized speech through one continuously running AudioTrack.
 *
 * <p>The timer service chooses when an event is due and calls {@link #dispatch}. This class
 * owns only audio: it prepares speech away from the service's timing thread, positions the
 * resulting PCM at a future sample frame, writes silence between cues, and reports when the
 * device playback head crosses each action's start and end frames.</p>
 *
 * <p>AudioTrack work runs on one writer thread. TextToSpeech preparation and diagnostic
 * callbacks use separate single-thread executors, so neither TTS nor a database write can
 * block the PCM writer.</p>
 */
final class AudioTimelinePlayer implements AutoCloseable {
    private static final String TAG = "AudioTimelinePlayer";
    /** Keep each blocking write short so newly prepared cues can be placed promptly. */
    private static final int CHUNK_FRAMES = PcmTone.SAMPLE_RATE / 100;
    /** The output stream keeps four 10 ms chunks buffered, matching the prototype. */
    private static final int BUFFER_CHUNKS = 4;
    /** Place new actions beyond the block currently being rendered and one extra block. */
    private static final int SCHEDULING_LEAD_FRAMES = CHUNK_FRAMES * 2;
    /** Leave a small audible pause between multiple speech actions from one service tick. */
    private static final long SPEECH_GAP_FRAMES = PcmTone.SAMPLE_RATE / 10L;
    /** Prevents a broken output device from spinning forever on empty writes. */
    private static final int MAX_ZERO_WRITES = 25;

    private final Object lock = new Object();
    private final Context applicationContext;
    private final Observer observer;
    private final ExecutorService preparationExecutor = newSingleThreadExecutor(
            "MultiTimer-tts-prepare");
    private final ExecutorService callbackExecutor = newSingleThreadExecutor(
            "MultiTimer-audio-callbacks");
    private final Set<TrackedAction> outstanding = ConcurrentHashMap.newKeySet();
    private final AtomicInteger outstandingCount = new AtomicInteger();
    private final List<TonePlayback> tones = new ArrayList<>();
    private final List<SpeechPlayback> speech = new ArrayList<>();
    private final List<PlaybackSpan> playbackSpans = new ArrayList<>();
    /** Immutable input snapshots keep the 10 ms audio loop free from per-block collection copies. */
    private volatile List<AudioTimelineMixer.Tone> toneRenderSnapshot = Collections.emptyList();
    private volatile List<PcmSpeechAudio.Clip> speechRenderSnapshot = Collections.emptyList();

    private volatile boolean closed;
    private volatile String streamFailure;
    private boolean startRequested;
    private Thread writerThread;
    private AudioTrack audioTrack;
    private volatile TtsPcmSynthesizer synthesizer;
    private long framesWritten;
    private long playbackHeadFrames;
    private long playbackWrapBase;
    private long previousRawPlaybackHead;
    private boolean hasPlaybackHead;
    private int lastUnderrunCount;

    /** Receives stream-level events without making the audio writer perform disk I/O. */
    interface Observer {
        /** Called after AudioTrack has initialized and entered its playing state. */
        void onStreamStarted(int audioSessionId);

        /** Called when Android reports that the output buffer ran empty. */
        void onUnderrun(int underrunCount, long playbackFrame);

        /** Called after track creation, startup, or writing fails. */
        void onStreamFailed(String detail);
    }

    /** One spoken phrase and its event-specific completion listener. */
    static final class SpeechCue {
        final String text;
        final AudioPlaybackListener listener;

        SpeechCue(String text, AudioPlaybackListener listener) {
            this.text = text;
            this.listener = listener;
        }
    }

    /** Creates a player that is started only after the service enters the foreground. */
    AudioTimelinePlayer(Context context, Observer observer) {
        applicationContext = context.getApplicationContext();
        this.observer = observer;
    }

    /**
     * Starts one long-lived stream. The stream writes zero samples while there are no cues.
     *
     * <p>Calling this method repeatedly is safe. The AudioTrack writer begins on a worker
     * thread; the TTS engine is initialized lazily only when a speech action is requested.</p>
     */
    void start() {
        synchronized (lock) {
            if (closed || startRequested) {
                return;
            }
            startRequested = true;
            writerThread = new Thread(this::writeAudioContinuously,
                    "MultiTimer-audio-track");
            try {
                writerThread.start();
            } catch (RuntimeException error) {
                failStream("Could not start the AudioTrack writer: " + readableError(error));
            }
        }
    }

    /** Returns true while an action is synthesizing, queued, playing, or awaiting its callback. */
    boolean hasPendingAudio() {
        return outstandingCount.get() > 0;
    }

    /** Returns true after media playback has been requested for this service instance. */
    boolean isStarted() {
        synchronized (lock) {
            return startRequested && !closed;
        }
    }

    /**
     * Schedules one Handler tick's coalesced beep and ordered speech actions on the PCM stream.
     *
     * <p>If speech is present, the beep waits for that batch's PCM conversion so both can be
     * mixed at the same start frame. This may make the event late; the cue's durable audio
     * callback records the actual playback-head frame. TTS never blocks the timing thread.</p>
     *
     * @param beepDurationMillis longest beep requested by this timing tick, or zero
     * @param beepListener diagnostic listener for the merged beep, if any
     * @param speechCues speech actions in the order the timer service collected them
     */
    void dispatch(long beepDurationMillis, AudioPlaybackListener beepListener,
            List<SpeechCue> speechCues) {
        if (closed) {
            failListener(beepListener, "Audio timeline is closed");
            if (speechCues != null) {
                for (SpeechCue cue : speechCues) {
                    failListener(cue.listener, "Audio timeline is closed");
                }
            }
            return;
        }

        long clampedBeepMillis = clampBeepDuration(beepDurationMillis);
        TrackedAction beepAction = clampedBeepMillis == 0L
                ? null : new TrackedAction(beepListener);
        List<SpeechRequest> requests = new ArrayList<>();
        if (speechCues != null) {
            for (SpeechCue cue : speechCues) {
                if (cue != null && cue.text != null && !cue.text.trim().isEmpty()) {
                    requests.add(new SpeechRequest(cue.text.trim(),
                            new TrackedAction(cue.listener)));
                } else if (cue != null) {
                    failListener(cue.listener, "Text-to-speech text was empty");
                }
            }
        }

        if (beepAction == null && requests.isEmpty()) {
            return;
        }
        if (!startRequested) {
            failBatch(beepAction, requests, "Audio timeline was not started");
            return;
        }
        if (streamFailure != null) {
            failBatch(beepAction, requests, streamFailure);
            return;
        }

        if (beepAction != null) {
            track(beepAction);
        }
        for (SpeechRequest request : requests) {
            track(request.action);
        }

        if (requests.isEmpty()) {
            schedulePreparedBatch(beepAction, clampedBeepMillis,
                    Collections.emptyList());
            return;
        }

        try {
            ensureSynthesizer();
        } catch (RuntimeException error) {
            for (SpeechRequest request : requests) {
                request.action.fail("Could not initialize TTS: " + readableError(error));
            }
            requests.clear();
            if (beepAction != null) {
                schedulePreparedBatch(beepAction, clampedBeepMillis,
                        Collections.emptyList());
            }
            return;
        }

        try {
            preparationExecutor.execute(() -> prepareSpeechBatch(
                    beepAction, clampedBeepMillis, requests));
        } catch (RejectedExecutionException error) {
            failBatch(beepAction, requests, "Speech preparation executor is unavailable");
        }
    }

    /** Converts phrases sequentially off the timing thread and leaves individual failures visible. */
    private void prepareSpeechBatch(TrackedAction beepAction, long beepDurationMillis,
            List<SpeechRequest> requests) {
        List<PreparedSpeech> prepared = new ArrayList<>();
        for (SpeechRequest request : requests) {
            if (closed) {
                request.action.fail("Audio timeline closed during speech preparation");
                continue;
            }
            try {
                PcmSpeechAudio.Clip clip = synthesizer.synthesize(request.text, 0L);
                prepared.add(new PreparedSpeech(clip.samples, request.action));
            } catch (RuntimeException error) {
                request.action.fail("TTS preparation failed: " + readableError(error));
            }
        }
        schedulePreparedBatch(beepAction, beepDurationMillis, prepared);
    }

    /** Creates Android's TTS client only if a timer actually requests speech. */
    private void ensureSynthesizer() {
        if (synthesizer != null) {
            return;
        }
        synchronized (lock) {
            if (synthesizer == null) {
                synthesizer = new TtsPcmSynthesizer(applicationContext);
            }
        }
    }

    /** Places a ready batch after the writer's current block, preserving phrase order. */
    private void schedulePreparedBatch(TrackedAction beepAction, long beepDurationMillis,
            List<PreparedSpeech> preparedSpeech) {
        if (closed || streamFailure != null) {
            String detail = closed ? "Audio timeline closed before playback" : streamFailure;
            if (beepAction != null) {
                beepAction.fail(detail);
            }
            for (PreparedSpeech item : preparedSpeech) {
                item.action.fail(detail);
            }
            return;
        }

        synchronized (lock) {
            if (closed || streamFailure != null) {
                String detail = closed ? "Audio timeline closed before playback" : streamFailure;
                if (beepAction != null) {
                    beepAction.fail(detail);
                }
                for (PreparedSpeech item : preparedSpeech) {
                    item.action.fail(detail);
                }
                return;
            }

            long firstActionFrame = safeStartFrame();
            if (beepAction != null) {
                long durationFrames = PcmTone.framesForMillis(beepDurationMillis);
                AudioTimelineMixer.Tone tone = new AudioTimelineMixer.Tone(
                        firstActionFrame, durationFrames);
                TonePlayback playback = new TonePlayback(tone, beepAction);
                tones.add(playback);
                playbackSpans.add(playback);
            }

            long nextSpeechFrame = firstActionFrame;
            for (PreparedSpeech item : preparedSpeech) {
                PcmSpeechAudio.Clip clip = new PcmSpeechAudio.Clip(
                        nextSpeechFrame, item.samples);
                SpeechPlayback playback = new SpeechPlayback(clip, item.action);
                speech.add(playback);
                playbackSpans.add(playback);
                nextSpeechFrame = saturatingAdd(clip.startFrame,
                        clip.samples.length + SPEECH_GAP_FRAMES);
            }
            refreshRenderSnapshots();
            Log.i(TAG, "batch scheduled frame=" + firstActionFrame
                    + " beepMs=" + beepDurationMillis
                    + " speechCount=" + preparedSpeech.size()
                    + " writerFrame=" + framesWritten
                    + " playbackHeadFrame=" + playbackHeadFrames);
        }
    }

    /**
     * Writes silence and scheduled cues until the service closes this player or Android rejects
     * audio output. The writer owns AudioTrack and never invokes SQLite-backed callbacks directly.
     */
    private void writeAudioContinuously() {
        AudioTrack track = null;
        try {
            // AudioTrack needs a promptly scheduled producer while the device is screen-off.
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            } catch (RuntimeException error) {
                // Priority is an optimization; failure must not disable the timer's audio path.
                Log.w(TAG, "Could not raise AudioTrack writer priority", error);
            }
            track = createAudioTrack();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioTrack did not initialize");
            }
            synchronized (lock) {
                if (closed) {
                    return;
                }
                audioTrack = track;
            }
            track.play();
            int audioSessionId = track.getAudioSessionId();
            postObserver(() -> observer.onStreamStarted(audioSessionId));
            Log.i(TAG, "AudioTrack started session=" + audioSessionId
                    + " sampleRate=" + AudioTimelineMixer.SAMPLE_RATE);

            short[] block = new short[CHUNK_FRAMES];
            while (!closed) {
                long firstFrame;
                List<AudioTimelineMixer.Tone> toneSnapshot;
                List<PcmSpeechAudio.Clip> speechSnapshot;
                synchronized (lock) {
                    firstFrame = framesWritten;
                    toneSnapshot = toneRenderSnapshot;
                    speechSnapshot = speechRenderSnapshot;
                }

                AudioTimelineMixer.renderPrepared(block, 0, block.length, firstFrame,
                        toneSnapshot, speechSnapshot);
                writeFully(track, block);
                observePlaybackHead(track);
            }
        } catch (RuntimeException error) {
            if (!closed) {
                failStream("AudioTrack failed: " + readableError(error));
            }
        } finally {
            synchronized (lock) {
                if (audioTrack == track) {
                    audioTrack = null;
                }
            }
            if (track != null) {
                releaseTrack(track);
            }
        }
    }

    /** Builds alarm-usage PCM output so the system routes timer cues as alerts. */
    private static AudioTrack createAudioTrack() {
        int minimumBufferBytes = AudioTrack.getMinBufferSize(
                AudioTimelineMixer.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimumBufferBytes <= 0) {
            throw new IllegalStateException("No supported AudioTrack buffer size: "
                    + minimumBufferBytes);
        }
        int requestedBufferBytes = Math.max(minimumBufferBytes,
                CHUNK_FRAMES * Short.BYTES * BUFFER_CHUNKS);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioTimelineMixer.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(requestedBufferBytes)
                .build();
    }

    /** Writes a complete block and records each accepted frame before the next render. */
    private void writeFully(AudioTrack track, short[] block) {
        int written = 0;
        int zeroWrites = 0;
        while (written < block.length && !closed) {
            int accepted = track.write(block, written, block.length - written,
                    AudioTrack.WRITE_BLOCKING);
            if (accepted < 0) {
                throw new IllegalStateException("AudioTrack.write returned " + accepted);
            }
            if (accepted == 0) {
                zeroWrites++;
                if (zeroWrites > MAX_ZERO_WRITES) {
                    throw new IllegalStateException("AudioTrack repeatedly accepted zero frames");
                }
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    if (!closed) {
                        throw new IllegalStateException("Audio writer was interrupted", interrupted);
                    }
                    return;
                }
                continue;
            }
            zeroWrites = 0;
            written += accepted;
            synchronized (lock) {
                framesWritten += accepted;
            }
        }
    }

    /** Samples the hardware frame head and triggers action callbacks at their actual PCM frames. */
    private void observePlaybackHead(AudioTrack track) {
        long rawFrame = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
        List<Runnable> transitions = null;
        boolean renderInputsChanged = false;
        int currentUnderruns = track.getUnderrunCount();
        long observedHead;
        synchronized (lock) {
            if (hasPlaybackHead && rawFrame < previousRawPlaybackHead
                    && previousRawPlaybackHead - rawFrame > 0x8000_0000L) {
                playbackWrapBase = saturatingAdd(playbackWrapBase, 1L << 32);
            }
            hasPlaybackHead = true;
            previousRawPlaybackHead = rawFrame;
            playbackHeadFrames = saturatingAdd(playbackWrapBase, rawFrame);
            observedHead = playbackHeadFrames;

            if (currentUnderruns > lastUnderrunCount) {
                int newCount = currentUnderruns;
                transitions = new ArrayList<>();
                transitions.add(() -> observer.onUnderrun(newCount, observedHead));
                lastUnderrunCount = currentUnderruns;
            }

            for (int index = playbackSpans.size() - 1; index >= 0; index--) {
                PlaybackSpan span = playbackSpans.get(index);
                if (!span.action.hasStarted() && observedHead >= span.startFrame) {
                    if (transitions == null) {
                        transitions = new ArrayList<>();
                    }
                    span.action.started(playbackDetail("AudioTrack reached action start",
                            span.startFrame, observedHead));
                }
                if (observedHead >= span.endFrame) {
                    if (span instanceof TonePlayback) {
                        tones.remove(span);
                    } else {
                        speech.remove(span);
                    }
                    renderInputsChanged = true;
                    playbackSpans.remove(index);
                    if (transitions == null) {
                        transitions = new ArrayList<>();
                    }
                    span.action.completed(playbackDetail("AudioTrack consumed action",
                            span.endFrame, observedHead));
                }
            }
            if (renderInputsChanged) {
                refreshRenderSnapshots();
            }
        }
        if (transitions != null) {
            for (Runnable transition : transitions) {
                postObserver(transition);
            }
        }
    }

    /** Rebuilds immutable renderer inputs only when an action is added or completed. */
    private void refreshRenderSnapshots() {
        List<AudioTimelineMixer.Tone> toneInputs = new ArrayList<>(tones.size());
        for (TonePlayback tone : tones) {
            toneInputs.add(tone.tone);
        }
        toneRenderSnapshot = Collections.unmodifiableList(
                AudioTimelineMixer.mergeOverlappingTones(toneInputs));

        List<PcmSpeechAudio.Clip> speechInputs = new ArrayList<>(speech.size());
        for (SpeechPlayback item : speech) {
            speechInputs.add(item.clip);
        }
        speechRenderSnapshot = Collections.unmodifiableList(speechInputs);
    }

    /** Returns the first frame that has not been rendered and is outside the small write lead. */
    private long safeStartFrame() {
        long current = Math.max(framesWritten, playbackHeadFrames);
        return saturatingAdd(current, SCHEDULING_LEAD_FRAMES);
    }

    /** Gives the event ledger enough frame evidence to measure playback delay. */
    private static String playbackDetail(String action, long targetFrame, long actualHead) {
        return action + "; targetFrame=" + targetFrame
                + "; playbackHeadFrame=" + actualHead
                + "; lateFrames=" + Math.max(0L, actualHead - targetFrame);
    }

    /** Reports one stream failure once and fails all scheduled or synthesizing timer actions. */
    private void failStream(String detail) {
        if (streamFailure != null) {
            return;
        }
        streamFailure = detail;
        Log.e(TAG, detail);
        postObserver(() -> observer.onStreamFailed(detail));
        for (TrackedAction action : new ArrayList<>(outstanding)) {
            action.fail(detail);
        }
    }

    /** Invokes an observer outside the PCM thread so durable diagnostics cannot cause underruns. */
    private void postObserver(Runnable callback) {
        if (observer == null) {
            return;
        }
        try {
            callbackExecutor.execute(() -> {
                try {
                    callback.run();
                } catch (RuntimeException error) {
                    Log.w(TAG, "Audio observer callback failed", error);
                }
            });
        } catch (RejectedExecutionException error) {
            Log.w(TAG, "Audio observer executor is closed");
        }
    }

    /** Adds one action to the lifecycle set before its asynchronous work can complete. */
    private void track(TrackedAction action) {
        outstanding.add(action);
        outstandingCount.incrementAndGet();
    }

    /** Fails a not-yet-tracked batch after setup or executor rejection. */
    private void failBatch(TrackedAction beepAction, List<SpeechRequest> requests, String detail) {
        if (beepAction != null) {
            beepAction.fail(detail);
        }
        for (SpeechRequest request : requests) {
            request.action.fail(detail);
        }
    }

    /** Fails a listener that could not be associated with a scheduled output action. */
    private static void failListener(AudioPlaybackListener listener, String detail) {
        if (listener != null) {
            listener.onFailed(detail);
        }
    }

    /** Creates and names daemon workers so they cannot keep a stopped Android process alive. */
    private static ExecutorService newSingleThreadExecutor(String name) {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    /** Bounds beep duration to the same limits as the existing user profile model. */
    private static long clampBeepDuration(long durationMillis) {
        if (durationMillis <= 0L) {
            return 0L;
        }
        return Math.max(BeepProfile.MIN_BEEP_DURATION_MILLIS,
                Math.min(BeepProfile.MAX_BEEP_DURATION_MILLIS, durationMillis));
    }

    /** Avoids overflowing a long frame counter when adding a phrase length and its gap. */
    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    /** Keeps asynchronous platform failures legible in logcat and the event ledger. */
    private static String readableError(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    /** Pauses a failed or closed track before releasing its native audio resources. */
    private static void releaseTrack(AudioTrack track) {
        try {
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause();
            }
            track.flush();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not stop AudioTrack cleanly", error);
        } finally {
            track.release();
        }
    }

    /** Stops TTS, writer and callback workers when the timer service has no work left. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (TrackedAction action : new ArrayList<>(outstanding)) {
            action.failDirect("Audio timeline closed before action completed");
        }
        preparationExecutor.shutdownNow();
        if (synthesizer != null) {
            synthesizer.close();
        }
        AudioTrack currentTrack = audioTrack;
        if (currentTrack != null) {
            try {
                currentTrack.pause();
                currentTrack.flush();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not interrupt the AudioTrack during close", error);
            }
        }
        Thread writer = writerThread;
        if (writer != null) {
            writer.interrupt();
        }
        callbackExecutor.shutdown();
    }

    /** An immutable request that keeps its originating action callback beside the text. */
    private static final class SpeechRequest {
        final String text;
        final TrackedAction action;

        SpeechRequest(String text, TrackedAction action) {
            this.text = text;
            this.action = action;
        }
    }

    /** A converted phrase plus its independent timer-event diagnostic action. */
    private static final class PreparedSpeech {
        final short[] samples;
        final TrackedAction action;

        PreparedSpeech(short[] samples, TrackedAction action) {
            this.samples = samples;
            this.action = action;
        }
    }

    /** A scheduled action interval on the AudioTrack frame axis. */
    private abstract static class PlaybackSpan {
        final long startFrame;
        final long endFrame;
        final TrackedAction action;

        PlaybackSpan(long startFrame, long endFrame, TrackedAction action) {
            this.startFrame = startFrame;
            this.endFrame = endFrame;
            this.action = action;
        }
    }

    /** Beep samples and diagnostics share the same scheduled interval. */
    private static final class TonePlayback extends PlaybackSpan {
        final AudioTimelineMixer.Tone tone;

        TonePlayback(AudioTimelineMixer.Tone tone, TrackedAction action) {
            super(tone.startFrame, tone.endFrame(), action);
            this.tone = tone;
        }
    }

    /** Speech samples and diagnostics share the same scheduled interval. */
    private static final class SpeechPlayback extends PlaybackSpan {
        final PcmSpeechAudio.Clip clip;

        SpeechPlayback(PcmSpeechAudio.Clip clip, TrackedAction action) {
            super(clip.startFrame, saturatingAdd(clip.startFrame, clip.samples.length), action);
            this.clip = clip;
        }
    }

    /** Guarantees exactly one terminal callback for each timer audio action. */
    private final class TrackedAction {
        private final AudioPlaybackListener listener;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();

        TrackedAction(AudioPlaybackListener listener) {
            this.listener = listener;
        }

        boolean hasStarted() {
            return started.get();
        }

        void started(String detail) {
            if (terminal.get() || !started.compareAndSet(false, true)) {
                return;
            }
            submit(() -> {
                if (listener != null && !terminal.get()) {
                    listener.onStarted(detail);
                }
            });
        }

        void completed(String detail) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            submitTerminal(() -> {
                if (listener != null) {
                    listener.onCompleted(detail);
                }
            });
        }

        void fail(String detail) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            submitTerminal(() -> {
                if (listener != null) {
                    listener.onFailed(detail);
                }
            });
        }

        void failDirect(String detail) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            try {
                if (listener != null) {
                    listener.onFailed(detail);
                }
            } finally {
                forget();
            }
        }

        private void submit(Runnable callback) {
            try {
                callbackExecutor.execute(callback);
            } catch (RejectedExecutionException error) {
                Log.w(TAG, "Could not deliver audio-start callback");
            }
        }

        private void submitTerminal(Runnable callback) {
            try {
                callbackExecutor.execute(() -> {
                    try {
                        callback.run();
                    } finally {
                        forget();
                    }
                });
            } catch (RejectedExecutionException error) {
                try {
                    callback.run();
                } finally {
                    forget();
                }
            }
        }

        private void forget() {
            if (outstanding.remove(this)) {
                outstandingCount.updateAndGet(value -> Math.max(0, value - 1));
            }
        }
    }
}
