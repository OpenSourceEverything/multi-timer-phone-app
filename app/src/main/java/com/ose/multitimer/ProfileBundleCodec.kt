package com.ose.multitimer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * Encodes and decodes the portable v1 profile bundle.
 *
 * This boundary handles data only: decoding never touches AppStore or creates
 * Android profile IDs. A later, explicitly confirmed import can call
 * SharedProfile.toNewProfile() to materialize fresh local identities.
 */
object ProfileBundleCodec {
    private const val FORMAT_NAME = "com.ose.multitimer.profiles"
    private const val FORMAT_VERSION = 1L

    private val ROOT_FIELDS = setOf("format", "version", "profiles")
    private val PROFILE_FIELDS = setOf(
        "name",
        "mode",
        "beepDurationMillis",
        "periodMillis",
        "events",
        "beepOnCue",
        "beepOnCompletion",
        "speakOnCompletion",
        "speakCurrentTime",
        "speakElapsedTime",
        "speakRemainingTime",
        "speakOnCue",
        "speakClockNameWithSpeech",
    )
    private val CUE_FIELDS = setOf("offsetMillis", "speechText", "beepDurationMillis")

    /**
     * Decode one bounded UTF-8 document into detached immutable profile values.
     *
     * The byte limit is checked before decoding or parsing. The decoder rejects
     * malformed byte sequences, and JsonSupport applies the format's container
     * depth limit before recursively entering a nested object or array.
     */
    @JvmStatic
    fun decode(bytes: ByteArray): List<SharedProfile> {
        require(bytes.size <= ProfileSharingLimits.MAX_DOCUMENT_BYTES) {
            "Profile bundle exceeds ${ProfileSharingLimits.MAX_DOCUMENT_BYTES} UTF-8 bytes"
        }

        val json = decodeUtf8(bytes)
        val root = try {
            JsonSupport.parseObject(json, ProfileSharingLimits.MAX_NESTING_DEPTH)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid profile bundle JSON: ${exception.message}", exception)
        }

        requireExactFields(root, ROOT_FIELDS, "bundle")
        require(stringValue(root["format"], "format") == FORMAT_NAME) {
            "format: expected '$FORMAT_NAME'"
        }
        require(longValue(root["version"], "version") == FORMAT_VERSION) {
            "version: unsupported profile bundle version"
        }

        val encodedProfiles = arrayValue(root["profiles"], "profiles")
        require(encodedProfiles.size in 1..ProfileSharingLimits.MAX_PROFILE_COUNT) {
            "profiles: expected 1..${ProfileSharingLimits.MAX_PROFILE_COUNT} entries"
        }

        val profiles = ArrayList<SharedProfile>(encodedProfiles.size)
        encodedProfiles.forEachIndexed { index, value ->
            profiles.add(decodeProfile(value, index))
        }
        return Collections.unmodifiableList(profiles)
    }

    /**
     * Encode validated portable models in a stable field order.
     *
     * The writer counts UTF-8 bytes as it appends each small piece, so an
     * oversized document stops growing at the interchange limit instead of
     * first allocating an arbitrarily large JSON string.
     */
    @JvmStatic
    fun encode(profiles: List<SharedProfile>): ByteArray {
        require(profiles.size in 1..ProfileSharingLimits.MAX_PROFILE_COUNT) {
            "profiles: expected 1..${ProfileSharingLimits.MAX_PROFILE_COUNT} entries"
        }

        val writer = BoundedJsonWriter()
        writer.append("{\"format\":")
        writer.appendQuoted(FORMAT_NAME)
        writer.append(",\"version\":$FORMAT_VERSION,\"profiles\":[")
        profiles.forEachIndexed { index, profile ->
            if (index > 0) writer.append(",")
            writer.appendProfile(profile)
        }
        writer.append("]}")
        return writer.finish()
    }

    /** Decodes bytes without silently replacing malformed UTF-8 sequences. */
    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (exception: CharacterCodingException) {
            throw IllegalArgumentException("Profile bundle is not valid UTF-8", exception)
        }
    }

    /** Validates one profile and annotates model errors with its array location. */
    private fun decodeProfile(value: Any?, index: Int): SharedProfile {
        val location = "profiles[$index]"
        return atLocation(location) {
            val profile = objectValue(value, location)
            requireExactFields(profile, PROFILE_FIELDS, location)

            val name = stringValue(profile["name"], "$location.name")
            val mode = when (stringValue(profile["mode"], "$location.mode")) {
                "PERIODIC" -> BeepProfile.Mode.PERIODIC
                "SCRIPTED" -> BeepProfile.Mode.SCRIPTED
                else -> throw IllegalArgumentException("$location.mode: unsupported schedule mode")
            }
            val beepDuration = longValue(profile["beepDurationMillis"], "$location.beepDurationMillis")
            val period = longValue(profile["periodMillis"], "$location.periodMillis")
            val encodedEvents = arrayValue(profile["events"], "$location.events")
            require(encodedEvents.size <= ProfileSharingLimits.MAX_EVENT_COUNT) {
                "$location.events: maximum is ${ProfileSharingLimits.MAX_EVENT_COUNT} entries"
            }

            val events = ArrayList<SharedCue>(encodedEvents.size)
            var previousOffset = -1L
            encodedEvents.forEachIndexed { eventIndex, encodedEvent ->
                val eventLocation = "$location.events[$eventIndex]"
                val event = atLocation(eventLocation) {
                    val fields = objectValue(encodedEvent, eventLocation)
                    requireExactFields(fields, CUE_FIELDS, eventLocation)
                    val offset = longValue(fields["offsetMillis"], "$eventLocation.offsetMillis")
                    require(offset > previousOffset) {
                        "$eventLocation.offsetMillis: offsets must increase strictly"
                    }
                    val speech = nullableStringValue(fields["speechText"], "$eventLocation.speechText")
                    val eventBeep = longValue(
                        fields["beepDurationMillis"], "$eventLocation.beepDurationMillis",
                    )
                    SharedCue(offset, speech, eventBeep)
                }
                previousOffset = event.offsetMillis
                events.add(event)
            }

            SharedProfile(
                name = name,
                mode = mode,
                beepDurationMillis = beepDuration,
                periodMillis = period,
                events = events,
                beepOnCue = booleanValue(profile["beepOnCue"], "$location.beepOnCue"),
                beepOnCompletion = booleanValue(
                    profile["beepOnCompletion"], "$location.beepOnCompletion",
                ),
                speakOnCompletion = booleanValue(
                    profile["speakOnCompletion"], "$location.speakOnCompletion",
                ),
                speakCurrentTime = booleanValue(
                    profile["speakCurrentTime"], "$location.speakCurrentTime",
                ),
                speakElapsedTime = booleanValue(
                    profile["speakElapsedTime"], "$location.speakElapsedTime",
                ),
                speakRemainingTime = booleanValue(
                    profile["speakRemainingTime"], "$location.speakRemainingTime",
                ),
                speakOnCue = booleanValue(profile["speakOnCue"], "$location.speakOnCue"),
                speakClockNameWithSpeech = booleanValue(
                    profile["speakClockNameWithSpeech"], "$location.speakClockNameWithSpeech",
                ),
            )
        }
    }

    /** Adds one stable profile object to the bounded output writer. */
    private fun BoundedJsonWriter.appendProfile(profile: SharedProfile) {
        append("{\"name\":")
        appendQuoted(profile.name)
        append(",\"mode\":")
        appendQuoted(profile.mode.name)
        append(",\"beepDurationMillis\":${profile.beepDurationMillis}")
        append(",\"periodMillis\":${profile.periodMillis}")
        append(",\"events\":[")
        profile.events.forEachIndexed { index, event ->
            if (index > 0) append(",")
            append("{\"offsetMillis\":${event.offsetMillis},\"speechText\":")
            if (event.speechText == null) append("null") else appendQuoted(event.speechText)
            append(",\"beepDurationMillis\":${event.beepDurationMillis}}")
        }
        append("]")
        append(",\"beepOnCue\":${profile.beepOnCue}")
        append(",\"beepOnCompletion\":${profile.beepOnCompletion}")
        append(",\"speakOnCompletion\":${profile.speakOnCompletion}")
        append(",\"speakCurrentTime\":${profile.speakCurrentTime}")
        append(",\"speakElapsedTime\":${profile.speakElapsedTime}")
        append(",\"speakRemainingTime\":${profile.speakRemainingTime}")
        append(",\"speakOnCue\":${profile.speakOnCue}")
        append(",\"speakClockNameWithSpeech\":${profile.speakClockNameWithSpeech}}")
    }

    /** Rejects both missing required members and fields this format version does not define. */
    private fun requireExactFields(value: Map<String, Any?>, expected: Set<String>, location: String) {
        val missing = expected - value.keys
        val unknown = value.keys - expected
        require(missing.isEmpty() && unknown.isEmpty()) {
            buildString {
                append(location)
                if (missing.isNotEmpty()) append(": missing fields ${missing.sorted()}")
                if (unknown.isNotEmpty()) append(": unknown fields ${unknown.sorted()}")
            }
        }
    }

    /** Requires an object at the named location before any unchecked map use. */
    private fun objectValue(value: Any?, location: String): Map<String, Any?> {
        require(value is Map<*, *>) { "$location: expected an object" }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    /** Requires an array at the named location before iterating its members. */
    private fun arrayValue(value: Any?, location: String): List<Any?> {
        require(value is List<*>) { "$location: expected an array" }
        @Suppress("UNCHECKED_CAST")
        return value as List<Any?>
    }

    /** Requires a non-null JSON string and includes the field path on failure. */
    private fun stringValue(value: Any?, location: String): String {
        require(value is String) { "$location: expected a string" }
        return value
    }

    /** Accepts only JSON string or null, the two meanings allowed for cue speech. */
    private fun nullableStringValue(value: Any?, location: String): String? {
        require(value == null || value is String) { "$location: expected a string or null" }
        return value as String?
    }

    /** Accepts only the parser's integer-token representation, never decimal or text. */
    private fun longValue(value: Any?, location: String): Long {
        require(value is Long) { "$location: expected an integer token" }
        return value
    }

    /** Keeps action flags typed; strings such as "true" are not coerced. */
    private fun booleanValue(value: Any?, location: String): Boolean {
        require(value is Boolean) { "$location: expected a boolean" }
        return value
    }

    /** Adds structural context without hiding the parser/model's original reason. */
    private inline fun <T> atLocation(location: String, action: () -> T): T {
        return try {
            action()
        } catch (exception: IllegalArgumentException) {
            val message = exception.message.orEmpty()
            if (message.startsWith("$location:") || message.startsWith("$location.")) {
                throw exception
            }
            throw IllegalArgumentException("$location: $message", exception)
        }
    }

    /** Tracks encoded bytes incrementally so a large model cannot grow unbounded output. */
    private class BoundedJsonWriter {
        private val output = StringBuilder()
        private var utf8ByteCount = 0

        /** Adds one fragment only when its encoded size fits the remaining budget. */
        fun append(text: String) {
            val addedBytes = text.toByteArray(StandardCharsets.UTF_8).size
            require(addedBytes <= ProfileSharingLimits.MAX_DOCUMENT_BYTES - utf8ByteCount) {
                "Encoded profile bundle exceeds ${ProfileSharingLimits.MAX_DOCUMENT_BYTES} UTF-8 bytes"
            }
            utf8ByteCount += addedBytes
            output.append(text)
        }

        /** Escapes a validated model string with the shared JSON quoting routine. */
        fun appendQuoted(text: String) {
            append(JsonSupport.quote(text))
        }

        /** Produces UTF-8 bytes and repeats the size check as a final invariant. */
        fun finish(): ByteArray {
            val bytes = output.toString().toByteArray(StandardCharsets.UTF_8)
            // Keep the final check as a guard if writer accounting ever changes.
            require(bytes.size <= ProfileSharingLimits.MAX_DOCUMENT_BYTES) {
                "Encoded profile bundle exceeds ${ProfileSharingLimits.MAX_DOCUMENT_BYTES} UTF-8 bytes"
            }
            return bytes
        }
    }
}
