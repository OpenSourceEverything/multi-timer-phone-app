package com.ose.multitimer;

/**
 * Receives the three pieces of evidence that an audio player can provide.
 *
 * <p>The timer engine decides that an action should happen.  The audio player
 * is the component that can later say whether playback actually started,
 * reached its natural end, or failed.  Keeping this interface small lets the
 * diagnostic ledger observe both generated beeps and Android text-to-speech
 * without making the timer scheduler know about AudioTrack or TextToSpeech
 * details.</p>
 */
interface AudioPlaybackListener {
    /** Reports that the platform began audible playback for this action. */
    void onStarted(String detail);

    /** Reports that the platform reached the normal end of this action. */
    void onCompleted(String detail);

    /** Reports that the platform could not start or finish this action. */
    void onFailed(String detail);
}
