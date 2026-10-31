package com.ose.multitimer

/**
 * Produces the concise action summary shown before a portable profile import.
 *
 * Profiles retain independent switches for speech and beeps. A switch such as
 * speakOnCue only permits speech; it does not prove that a scheduled speech
 * payload exists. This summary therefore checks both the switch and the actual
 * time-spoken flags or scripted text before telling a person that speech occurs.
 */
object SharedProfileDescription {
    /**
     * Describes actions that can actually occur at scheduled cues or completion.
     *
     * @param profile immutable imported profile being previewed
     * @return a short phrase such as "beep + speech • finish action"
     */
    @JvmStatic
    fun actions(profile: SharedProfile): String {
        val cueBeepExists = profile.beepOnCue && when (profile.mode) {
            BeepProfile.Mode.PERIODIC -> true
            BeepProfile.Mode.SCRIPTED -> profile.events.any { cue ->
                cue.beepDurationMillis > 0L
            }
        }
        val cueSpeechExists = profile.speakOnCue && (
            profile.speakCurrentTime
                || profile.speakElapsedTime
                || profile.speakRemainingTime
                || profile.events.any { cue -> cue.speechText != null }
            )

        val actions = mutableListOf<String>()
        if (cueBeepExists) {
            actions.add("beep")
        }
        if (cueSpeechExists) {
            actions.add("speech")
        }

        // The runtime requires the speech master switch as well as the
        // completion-speech option. Reporting the inactive option as an action
        // would make the preview disagree with what the timer can play.
        val completionActionExists = profile.beepOnCompletion
            || (profile.speakOnCue && profile.speakOnCompletion)
        if (completionActionExists) {
            actions.add("finish action")
        }

        return if (actions.isEmpty()) {
            "no active actions"
        } else {
            actions.joinToString(" + ").replace(" + finish action", " • finish action")
        }
    }
}
