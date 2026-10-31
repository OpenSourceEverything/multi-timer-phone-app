package com.ose.multitimer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks that an import preview describes effective actions, not enabled gates. */
class SharedProfileDescriptionTest {
    @Test
    fun periodicBeepDoesNotClaimSpeechFromTheSpeechGateAlone() {
        val profile = profile(
            mode = BeepProfile.Mode.PERIODIC,
            beepOnCue = true,
            beepOnCompletion = true,
            speakOnCue = true,
        )

        assertEquals("beep • finish action", SharedProfileDescription.actions(profile))
    }

    @Test
    fun configuredTimeSpeechAppearsAlongsideTheCueBeep() {
        val profile = profile(
            mode = BeepProfile.Mode.PERIODIC,
            beepOnCue = true,
            speakOnCue = true,
            speakElapsedTime = true,
        )

        assertEquals("beep + speech", SharedProfileDescription.actions(profile))
    }

    @Test
    fun scriptedActionsDependOnTheirConfiguredEventsAndMasterSwitches() {
        val profile = profile(
            mode = BeepProfile.Mode.SCRIPTED,
            events = listOf(SharedCue(0L, "Begin", 500L)),
            beepOnCue = true,
            speakOnCue = true,
        )

        assertEquals("beep + speech", SharedProfileDescription.actions(profile))
    }

    @Test
    fun disabledSpeechMasterSwitchSuppressesSpeechAndSpokenFinishSummary() {
        val profile = profile(
            mode = BeepProfile.Mode.PERIODIC,
            speakOnCue = false,
            speakOnCompletion = true,
        )

        assertEquals("no active actions", SharedProfileDescription.actions(profile))
    }

    private fun profile(
        mode: BeepProfile.Mode,
        events: List<SharedCue> = emptyList(),
        beepOnCue: Boolean = false,
        beepOnCompletion: Boolean = false,
        speakOnCue: Boolean = false,
        speakOnCompletion: Boolean = false,
        speakCurrentTime: Boolean = false,
        speakElapsedTime: Boolean = false,
        speakRemainingTime: Boolean = false,
    ) = SharedProfile(
        name = "Preview test",
        mode = mode,
        beepDurationMillis = 500L,
        periodMillis = if (mode == BeepProfile.Mode.PERIODIC) 60_000L else 0L,
        events = events,
        beepOnCue = beepOnCue,
        beepOnCompletion = beepOnCompletion,
        speakOnCompletion = speakOnCompletion,
        speakCurrentTime = speakCurrentTime,
        speakElapsedTime = speakElapsedTime,
        speakRemainingTime = speakRemainingTime,
        speakOnCue = speakOnCue,
        speakClockNameWithSpeech = false,
    )
}
