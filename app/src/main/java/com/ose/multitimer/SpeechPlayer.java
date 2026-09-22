package com.ose.multitimer;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;
import java.util.UUID;

/** Small service-owned wrapper around Android's installed text-to-speech engine. */
final class SpeechPlayer implements AutoCloseable {
    private static final String TAG = "SpeechPlayer";

    private TextToSpeech textToSpeech;
    private volatile boolean ready;

    SpeechPlayer(Context context) {
        textToSpeech = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "Text-to-speech initialization failed: " + status);
                return;
            }
            int languageStatus = textToSpeech.setLanguage(Locale.getDefault());
            ready = languageStatus != TextToSpeech.LANG_MISSING_DATA
                    && languageStatus != TextToSpeech.LANG_NOT_SUPPORTED;
            if (!ready) {
                Log.w(TAG, "No installed voice supports locale " + Locale.getDefault());
            } else {
                Log.i(TAG, "Text-to-speech ready for " + Locale.getDefault());
            }
        });
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                Log.i(TAG, "speech started id=" + utteranceId);
            }

            @Override
            public void onDone(String utteranceId) {
                Log.i(TAG, "speech finished id=" + utteranceId);
            }

            @Override
            public void onError(String utteranceId) {
                Log.w(TAG, "speech failed id=" + utteranceId);
            }
        });
    }

    boolean isReady() {
        return ready;
    }

    void speak(String text) {
        if (!ready || text == null || text.trim().isEmpty()) {
            return;
        }
        String utteranceId = UUID.randomUUID().toString();
        int result = textToSpeech.speak(
                text.trim(), TextToSpeech.QUEUE_ADD, null, utteranceId);
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Text-to-speech rejected id=" + utteranceId + " result=" + result);
        } else {
            Log.i(TAG, "speech queued id=" + utteranceId + " text=" + text.trim());
        }
    }

    @Override
    public void close() {
        ready = false;
        textToSpeech.stop();
        textToSpeech.shutdown();
    }
}
