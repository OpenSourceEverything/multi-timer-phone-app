package com.ose.multitimer;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Captures Android TTS as PCM so speech can share the timer's AudioTrack timeline. */
final class TtsPcmSynthesizer implements AutoCloseable {
    private static final String TAG = "TimerTtsPcm";
    private static final long INIT_TIMEOUT_SECONDS = 15L;
    private static final long SYNTHESIS_TIMEOUT_SECONDS = 30L;
    /** Bounds temporary memory use if a profile asks TTS to produce an unusually long phrase. */
    private static final int MAX_CAPTURED_PCM_BYTES = 4 * 1024 * 1024;

    private final Context context;
    private final CountDownLatch initialized = new CountDownLatch(1);
    private final ConcurrentHashMap<String, PendingUtterance> pending =
            new ConcurrentHashMap<>();
    private TextToSpeech textToSpeech;
    private volatile boolean ready;
    private volatile boolean cancelled;
    private volatile String initializationError = "";

    /** Creates the platform TTS engine; synthesis callbacks are registered immediately. */
    TtsPcmSynthesizer(Context context) {
        this.context = context.getApplicationContext();
        textToSpeech = new TextToSpeech(this.context, status -> {
            if (TextToSpeech.SUCCESS != status) {
                initializationError = "TTS initialization failed with status " + status;
                initialized.countDown();
                return;
            }

            int languageStatus = textToSpeech.setLanguage(Locale.getDefault());
            ready = TextToSpeech.LANG_MISSING_DATA != languageStatus
                    && TextToSpeech.LANG_NOT_SUPPORTED != languageStatus;
            if (!ready) {
                initializationError = "No installed TTS voice supports " + Locale.getDefault();
            }
            initialized.countDown();
        });
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                // synthesizeToFile does not play through the TTS speaker route.
            }

            @Override
            public void onDone(String utteranceId) {
                PendingUtterance request = pending.remove(utteranceId);
                if (request != null) {
                    request.finish();
                }
            }

            /** The deprecated abstract callback remains required for API compatibility. */
            @SuppressWarnings("deprecation")
            @Override
            public void onError(String utteranceId) {
                fail(utteranceId, "TTS synthesis reported an error");
            }

            @Override
            public void onError(String utteranceId, int errorCode) {
                fail(utteranceId, "TTS synthesis failed with code " + errorCode);
            }

            @Override
            public void onBeginSynthesis(String utteranceId, int sampleRate,
                    int audioFormat, int channelCount) {
                PendingUtterance request = pending.get(utteranceId);
                if (request != null) {
                    request.setFormat(sampleRate, audioFormat, channelCount);
                }
            }

            @Override
            public void onAudioAvailable(String utteranceId, byte[] audio) {
                PendingUtterance request = pending.get(utteranceId);
                if (request != null && !request.append(audio, MAX_CAPTURED_PCM_BYTES)) {
                    fail(utteranceId, "TTS PCM exceeded the 4 MiB per-phrase safety limit");
                }
            }
        });
    }

    /**
     * Synthesizes a phrase to a temporary file while collecting its PCM callback chunks.
     *
     * <p>The temporary output satisfies the TTS file-synthesis API. Playback later uses only
     * the captured PCM written into the timer's shared AudioTrack.</p>
     *
     * @param text short phrase to synthesize before playback begins
     * @param startFrame output frame where the phrase should begin
     * @return normalized speech clip positioned on the output timeline
     */
    PcmSpeechAudio.Clip synthesize(String text, long startFrame) {
        awaitInitialization();
        if (!ready) {
            throw new IllegalStateException(initializationError);
        }
        if (cancelled) {
            throw new IllegalStateException("Speech preparation was cancelled");
        }

        String utteranceId = UUID.randomUUID().toString();
        PendingUtterance request = new PendingUtterance();
        pending.put(utteranceId, request);
        File outputFile;
        try {
            outputFile = File.createTempFile("timer-audio-tts-", ".wav",
                    context.getCacheDir());
        } catch (IOException error) {
            pending.remove(utteranceId);
            throw new IllegalStateException("Could not create temporary TTS output", error);
        }

        // The TTS service writes its own output file; remove the empty placeholder first.
        if (!outputFile.delete()) {
            pending.remove(utteranceId);
            throw new IllegalStateException("Could not prepare temporary TTS output");
        }

        try {
            int result = textToSpeech.synthesizeToFile(text, new Bundle(), outputFile, utteranceId);
            if (TextToSpeech.SUCCESS != result) {
                pending.remove(utteranceId);
                throw new IllegalStateException("TTS rejected synthesis with code " + result);
            }
            if (!awaitSynthesis(request)) {
                pending.remove(utteranceId);
                throw new IllegalStateException("TTS did not finish the prepared phrase in time");
            }

            SynthesisData data = request.result();
            short[] samples = PcmSpeechAudio.toOutputSamples(data.bytes,
                    data.sampleRate, data.encoding, data.channelCount);
            Log.i(TAG, "prepared phrase frames=" + samples.length
                    + " sourceRate=" + data.sampleRate + " channels=" + data.channelCount);
            return new PcmSpeechAudio.Clip(startFrame, samples);
        } finally {
            pending.remove(utteranceId);
            if (outputFile.exists() && !outputFile.delete()) {
                Log.w(TAG, "Could not remove temporary TTS output file");
            }
        }
    }

    /** Cancels an in-progress offline synthesis when the user stops the debug run. */
    void cancel() {
        cancelled = true;
        initializationError = "Speech preparation was cancelled";
        initialized.countDown();
        for (PendingUtterance request : pending.values()) {
            request.fail("Speech preparation was cancelled by the user");
        }
        pending.clear();
        textToSpeech.stop();
    }

    /** Waits off the main thread for Android to finish binding to its TTS service. */
    private void awaitInitialization() {
        try {
            if (!initialized.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("TTS initialization timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while initializing TTS", interrupted);
        }
    }

    /** Waits for one utterance and converts thread interruption into a clear failure. */
    private boolean awaitSynthesis(PendingUtterance request) {
        try {
            return request.await(SYNTHESIS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while synthesizing speech", interrupted);
        }
    }

    /** Completes a pending utterance with a descriptive synthesis failure. */
    private void fail(String utteranceId, String detail) {
        PendingUtterance request = pending.remove(utteranceId);
        if (request != null) {
            request.fail(detail);
        }
        Log.w(TAG, detail);
    }

    /** Stops and releases the TTS service when the debug timer service is destroyed. */
    @Override
    public void close() {
        ready = false;
        cancel();
        textToSpeech.shutdown();
    }

    /** Collects one TTS callback stream and publishes it only after onDone arrives. */
    private static final class PendingUtterance {
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private final CountDownLatch completed = new CountDownLatch(1);
        private int sampleRate;
        private int encoding;
        private int channelCount;
        private String error;

        /** Stores the PCM format that applies to all callback chunks for this phrase. */
        synchronized void setFormat(int rate, int format, int channels) {
            sampleRate = rate;
            encoding = format;
            channelCount = channels;
        }

        /** Copies one callback chunk before Android reuses its byte array. */
        synchronized boolean append(byte[] bytes, int maximumBytes) {
            if (bytes == null) {
                return true;
            }
            if (bytes.length > maximumBytes - audio.size()) {
                error = "TTS PCM exceeded its configured size limit";
                completed.countDown();
                return false;
            }
            audio.write(bytes, 0, bytes.length);
            return true;
        }

        /** Marks all callback chunks as received. */
        void finish() {
            completed.countDown();
        }

        /** Saves an asynchronous platform error and releases the waiting worker. */
        synchronized void fail(String detail) {
            error = detail;
            completed.countDown();
        }

        /** Waits for callback completion without blocking the service main thread. */
        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return completed.await(timeout, unit);
        }

        /** Builds an immutable view after synthesis has completed. */
        synchronized SynthesisData result() {
            if (error != null) {
                throw new IllegalStateException(error);
            }
            if (sampleRate <= 0 || channelCount <= 0 || audio.size() == 0) {
                throw new IllegalStateException("TTS completed without usable PCM callbacks");
            }
            return new SynthesisData(audio.toByteArray(), sampleRate, encoding, channelCount);
        }
    }

    /** Immutable callback audio and the format needed to decode it. */
    private static final class SynthesisData {
        final byte[] bytes;
        final int sampleRate;
        final int encoding;
        final int channelCount;

        SynthesisData(byte[] bytes, int sampleRate, int encoding, int channelCount) {
            this.bytes = bytes;
            this.sampleRate = sampleRate;
            this.encoding = encoding;
            this.channelCount = channelCount;
        }
    }
}
