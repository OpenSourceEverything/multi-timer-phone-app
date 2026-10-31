package com.ose.multitimer

import java.util.Collections

/** Bounds of the portable format, not new restrictions on existing local timers. */
object ProfileSharingLimits {
    const val MAX_DOCUMENT_BYTES = 262_144
    const val MAX_PROFILE_COUNT = 32
    const val MAX_EVENT_COUNT = 256
    const val MAX_NESTING_DEPTH = 16
    const val MAX_NAME_BYTES = 256
    const val MAX_SPEECH_BYTES = 2_000

    // JSON consumers sometimes use IEEE-754 doubles for numbers. Keeping time
    // within their exact integer range prevents a schedule changing on exchange.
    const val MAX_TIME_MILLIS = 9_007_199_254_740_991L
}

/**
 * A detached, immutable cue with no local event identity.
 *
 * The model describes data only: creating it never schedules sound. Text is
 * normalized exactly once so validation and later import use the same value.
 */
class SharedCue(
    val offsetMillis: Long,
    speechText: String?,
    val beepDurationMillis: Long,
) {
    val speechText: String? = speechText?.let {
        portableText(it, "speechText", ProfileSharingLimits.MAX_SPEECH_BYTES)
    }

    init {
        require(offsetMillis in 0..ProfileSharingLimits.MAX_TIME_MILLIS) {
            "offsetMillis is outside the portable integer range"
        }
        // Match CueEvent's existing range. Its event-specific beep permits
        // 1–49 ms even though a profile's default beep has a 50 ms minimum.
        require(beepDurationMillis in 0..BeepProfile.MAX_BEEP_DURATION_MILLIS) {
            "Event beepDurationMillis must be from 0 to 10,000"
        }
        require(this.speechText != null || beepDurationMillis > 0) {
            "An event needs speech or a beep"
        }
    }
}

/**
 * Portable configuration for one reusable profile, without mutable store state.
 *
 * A sharing screen can hold this snapshot while the original Android model is
 * edited elsewhere. The copied, unmodifiable event list and immutable cues keep
 * a preview stable. Clock ownership, IDs and scheduling checkpoints never enter
 * this model. JSON parsing and persistent storage are separate boundaries.
 */
class SharedProfile(
    name: String,
    val mode: BeepProfile.Mode,
    val beepDurationMillis: Long,
    val periodMillis: Long,
    events: List<SharedCue>,
    val beepOnCue: Boolean,
    val beepOnCompletion: Boolean,
    val speakOnCompletion: Boolean,
    val speakCurrentTime: Boolean,
    val speakElapsedTime: Boolean,
    val speakRemainingTime: Boolean,
    val speakOnCue: Boolean,
    val speakClockNameWithSpeech: Boolean,
) {
    val name: String = portableText(name, "name", ProfileSharingLimits.MAX_NAME_BYTES)
    val events: List<SharedCue> = Collections.unmodifiableList(ArrayList(events))

    init {
        require(beepDurationMillis in BeepProfile.MIN_BEEP_DURATION_MILLIS..
            BeepProfile.MAX_BEEP_DURATION_MILLIS) {
            "Profile beepDurationMillis must be from 50 to 10,000"
        }
        require(this.events.size <= ProfileSharingLimits.MAX_EVENT_COUNT) {
            "A profile can contain at most ${ProfileSharingLimits.MAX_EVENT_COUNT} events"
        }
        when (mode) {
            BeepProfile.Mode.PERIODIC -> {
                require(periodMillis in 1..ProfileSharingLimits.MAX_TIME_MILLIS) {
                    "A periodic profile requires a positive portable periodMillis"
                }
                require(this.events.isEmpty()) { "A periodic profile cannot contain events" }
            }
            BeepProfile.Mode.SCRIPTED -> {
                require(periodMillis == 0L) { "A scripted profile must have periodMillis 0" }
                var previous = -1L
                for (event in this.events) {
                    require(event.offsetMillis > previous) {
                        "Scripted event offsets must increase strictly"
                    }
                    previous = event.offsetMillis
                }
            }
        }
    }

    /**
     * Materialize a fresh Android template after a caller confirms an import.
     *
     * Factories generate new profile/event IDs on every call. No reference to a
     * mutable Android event is retained by the snapshot, and this method does
     * not write AppStore or change profiles attached to running clocks.
     */
    fun toNewProfile(): BeepProfile {
        val result = when (mode) {
            BeepProfile.Mode.PERIODIC -> BeepProfile.periodic(
                name, beepDurationMillis, periodMillis, beepOnCompletion,
            )
            BeepProfile.Mode.SCRIPTED -> BeepProfile.scriptedEvents(
                name, beepDurationMillis,
                events.map { CueEvent.create(it.offsetMillis, it.speechText, it.beepDurationMillis) },
                beepOnCompletion,
            )
        }
        // Preserve the flags independently. Combining them into one spoken-time
        // enum would silently lose configurations supported by the Java model.
        result.beepOnCue = beepOnCue
        result.beepOnCompletion = beepOnCompletion
        result.speakOnCompletion = speakOnCompletion
        result.speakCurrentTime = speakCurrentTime
        result.speakElapsedTime = speakElapsedTime
        result.speakRemainingTime = speakRemainingTime
        result.speakOnCue = speakOnCue
        result.speakClockNameWithSpeech = speakClockNameWithSpeech
        result.reusable = true
        result.ownerClockId = null
        result.validate()
        return result
    }

    companion object {
        /**
         * Snapshot a reusable, detached AppStore model without mutating it.
         *
         * Callers must supply a stable model, not one being modified concurrently.
         * Do not call source.validate()/toJson() here: those normalize mutable
         * fields and could alter the object while merely opening export preview.
         */
        @JvmStatic
        fun fromProfile(source: BeepProfile): SharedProfile {
            require(source.reusable && source.ownerClockId == null) {
                "Only reusable profiles without a clock owner can be shared"
            }
            val snapshot = SharedProfile(
                requireNotNull(source.name) { "Profile name is required" },
                requireNotNull(source.mode) { "Profile mode is required" },
                source.beepDurationMillis, source.periodMillis,
                requireNotNull(source.events) { "Profile events are required" }.map { event ->
                    val cue = requireNotNull(event) { "Profile events cannot contain null" }
                    SharedCue(cue.offsetMillis, cue.speechText, cue.beepDurationMillis)
                },
                source.beepOnCue, source.beepOnCompletion, source.speakOnCompletion,
                source.speakCurrentTime, source.speakElapsedTime, source.speakRemainingTime,
                source.speakOnCue, source.speakClockNameWithSpeech,
            )
            // Local storage has two representations of scripted offsets. Reject
            // an inconsistent source instead of choosing one representation and
            // concealing a corrupted profile during export.
            require(source.scriptOffsetsMillis == snapshot.events.map { it.offsetMillis }) {
                "Local script offsets and events disagree"
            }
            return snapshot
        }
    }
}

/** Validate normalized text using a byte limit shared with the Swift decoder. */
private fun portableText(raw: String, field: String, maximumBytes: Int): String {
    val text = raw.trim { it <= '\u0020' }
    require(text.isNotEmpty()) { "$field cannot be blank" }
    // A JVM String can contain an unpaired UTF-16 surrogate. Encoding it with
    // replacement would change user data, so reject it before producing UTF-8.
    require(Charsets.UTF_8.newEncoder().canEncode(text)) { "$field contains invalid Unicode" }
    require(text.toByteArray(Charsets.UTF_8).size <= maximumBytes) {
        "$field exceeds $maximumBytes UTF-8 bytes"
    }
    return text
}
