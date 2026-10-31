package com.ose.multitimer

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the portable interchange boundary without Android UI or storage. */
class ProfileBundleCodecTest {
    @Test
    fun everyValidSharedFixtureDecodesAndReencodesStably() {
        val fixtures = File(fixtureDirectory(), "valid").listFiles()!!.sortedBy { it.name }
        assertEquals("The shared contract has four positive fixtures", 4, fixtures.size)

        fixtures.forEach { fixture ->
            val document = fixture.readBytes()
            val decoded = ProfileBundleCodec.decode(document)
            assertEquals("${fixture.name} contains one profile", 1, decoded.size)
            assertFixtureValues(fixture.name, document, decoded.single())
            val encoded = ProfileBundleCodec.encode(decoded)
            assertProfilesEquivalent(
                fixture.name,
                decoded,
                ProfileBundleCodec.decode(encoded),
            )
            assertArrayEquals("${fixture.name} has stable semantic encoding", encoded,
                ProfileBundleCodec.encode(ProfileBundleCodec.decode(encoded)))
        }
    }

    @Test
    fun scriptedFixturePreservesEveryCueAndIndependentFlag() {
        val profiles = ProfileBundleCodec.decode(fixture("valid/scripted.json"))
        val profile = profiles.single()

        assertEquals("Focus – تمرين 🍵", profile.name)
        assertEquals(BeepProfile.Mode.SCRIPTED, profile.mode)
        assertEquals(listOf(0L, 30_000L, 60_000L), profile.events.map { it.offsetMillis })
        assertEquals("Start", profile.events[0].speechText)
        assertEquals(0L, profile.events[0].beepDurationMillis)
        assertNull(profile.events[2].speechText)
        assertEquals(750L, profile.events[2].beepDurationMillis)
        assertTrue(profile.beepOnCue)
        assertTrue(profile.beepOnCompletion)
        assertFalse(profile.speakOnCompletion)
        assertFalse(profile.speakCurrentTime)
        assertFalse(profile.speakElapsedTime)
        assertFalse(profile.speakRemainingTime)
        assertTrue(profile.speakOnCue)
        assertTrue(profile.speakClockNameWithSpeech)
    }

    @Test
    fun everySharedInvalidFixtureIsRejected() {
        val fixtures = File(fixtureDirectory(), "invalid").listFiles()!!.sortedBy { it.name }
        assertEquals("The shared contract has eight negative fixtures", 8, fixtures.size)
        val expectedReasons = mapOf(
            "duplicate-member.json" to "Duplicate object key",
            "empty-profiles.json" to "profiles: expected",
            "fractional-period.json" to "Only integer JSON numbers",
            "local-identity.json" to "unknown fields [id]",
            "mixed-schedule.json" to "periodic profile cannot contain events",
            "silent-cue.json" to "An event needs speech or a beep",
            "unsorted-cues.json" to "offsets must increase strictly",
            "unsupported-version.json" to "version: unsupported",
        )
        assertEquals("Every negative fixture has one expected rejection reason",
            fixtures.map { it.name }.toSet(), expectedReasons.keys)

        fixtures.forEach { fixture ->
            val exception = assertThrows(
                "${fixture.name} must be rejected",
                IllegalArgumentException::class.java,
            ) {
                ProfileBundleCodec.decode(fixture.readBytes())
            }
            assertTrue(
                "${fixture.name} must fail for its documented reason; got: ${exception.message}",
                exception.message.orEmpty().contains(expectedReasons.getValue(fixture.name)),
            )
        }
    }

    @Test
    fun exactFieldSetsAndJsonTypesAreRequired() {
        val validProfile = profileJson()
        assertDecodeFails("[]")
        assertDecodeFails(bundle(validProfile).replace("\"version\":1", "\"version\":1.0"))
        assertDecodeFails(bundle(validProfile).replace("\"version\":1", "\"version\":\"1\""))
        assertDecodeFails(bundle(validProfile).replace("\"speakOnCue\":true", "\"speakOnCue\":\"true\""))
        assertDecodeFails(bundle(validProfile).replace("\"speakOnCue\":true,", ""))
        assertDecodeFails(bundle(validProfile.dropLast(1) + ",\"extra\":false}"))
        assertDecodeFails(bundle(validProfile.replace("\"events\":[]", "\"events\":[${cueJson(0L, "\\\"Go\\\"", 0L)}]")))
        assertDecodeFails(bundle(profileJson(mode = "UNKNOWN")))
    }

    @Test
    fun documentAndEncodedOutputByteLimitsAreEnforced() {
        val valid = bundle(profileJson()).toByteArray(StandardCharsets.UTF_8)
        val atLimit = valid + ByteArray(ProfileSharingLimits.MAX_DOCUMENT_BYTES - valid.size) { ' '.code.toByte() }
        assertEquals(1, ProfileBundleCodec.decode(atLimit).size)
        assertThrows(IllegalArgumentException::class.java) {
            ProfileBundleCodec.decode(atLimit + byteArrayOf(' '.code.toByte()))
        }

        val largeText = "x".repeat(ProfileSharingLimits.MAX_SPEECH_BYTES)
        val largeEvents = List(ProfileSharingLimits.MAX_EVENT_COUNT) { index ->
            SharedCue(index.toLong(), largeText, 0L)
        }
        val largeProfile = sharedProfile(BeepProfile.Mode.SCRIPTED, 0L, largeEvents)
        assertThrows(IllegalArgumentException::class.java) {
            ProfileBundleCodec.encode(listOf(largeProfile))
        }
    }

    @Test
    fun profileAndCueCountLimitsAreEnforcedAtTheirBoundaries() {
        val profile = sharedProfile()
        val maxProfiles = List(ProfileSharingLimits.MAX_PROFILE_COUNT) { profile }
        assertEquals(ProfileSharingLimits.MAX_PROFILE_COUNT,
            ProfileBundleCodec.decode(ProfileBundleCodec.encode(maxProfiles)).size)
        assertThrows(IllegalArgumentException::class.java) {
            ProfileBundleCodec.encode(maxProfiles + profile)
        }
        assertDecodeFails(bundle(List(ProfileSharingLimits.MAX_PROFILE_COUNT + 1) {
            profileJson()
        }.joinToString(",")))

        val maxEvents = List(ProfileSharingLimits.MAX_EVENT_COUNT) { index ->
            SharedCue(index.toLong(), "Cue", 0L)
        }
        val scripted = sharedProfile(BeepProfile.Mode.SCRIPTED, 0L, maxEvents)
        assertEquals(ProfileSharingLimits.MAX_EVENT_COUNT,
            ProfileBundleCodec.decode(ProfileBundleCodec.encode(listOf(scripted))).single().events.size)

        val eventsOverLimit = List(ProfileSharingLimits.MAX_EVENT_COUNT + 1) { index ->
            cueJson(index.toLong(), "\"Cue\"", 0L)
        }
        assertDecodeFails(bundle(profileJson(
            mode = "SCRIPTED", period = "0", events = eventsOverLimit.joinToString(","),
        )))
    }

    @Test
    fun normalizedUtf8TextLimitsAndSafeIntegerBoundaryAreEnforced() {
        val maxName = "é".repeat(128)
        assertEquals(maxName, ProfileBundleCodec.decode(bundle(profileJson(
            nameJson = JsonSupport.quote(maxName),
        )).toByteArray(StandardCharsets.UTF_8)).single().name)
        assertDecodeFails(bundle(profileJson(nameJson = JsonSupport.quote("é".repeat(129)))))

        val maxSpeech = "é".repeat(1_000)
        val acceptedSpeech = cueJson(0L, JsonSupport.quote(maxSpeech), 0L)
        assertEquals(maxSpeech, ProfileBundleCodec.decode(bundle(profileJson(
            mode = "SCRIPTED", period = "0", events = acceptedSpeech,
        )).toByteArray(StandardCharsets.UTF_8)).single().events.single().speechText)
        val longSpeech = cueJson(0L, JsonSupport.quote("é".repeat(1_001)), 0L)
        assertDecodeFails(bundle(profileJson(mode = "SCRIPTED", period = "0", events = longSpeech)))

        val maximum = ProfileSharingLimits.MAX_TIME_MILLIS
        assertEquals(maximum, ProfileBundleCodec.decode(bundle(profileJson(period = maximum.toString()))
            .toByteArray(StandardCharsets.UTF_8)).single().periodMillis)
        assertDecodeFails(bundle(profileJson(period = (maximum + 1L).toString())))
        assertDecodeFails(bundle(profileJson(
            mode = "SCRIPTED", period = "0", events = cueJson(maximum + 1L, "\"Cue\"", 0L),
        )))
    }

    @Test
    fun decoderRequiresValidUtf8AndPairedUnicodeSurrogates() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileBundleCodec.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertDecodeFails(bundle(profileJson(nameJson = "\"\\uD800\"")))
        assertDecodeFails(bundle(profileJson(nameJson = "\"\\uDC00\"")))
        // JSON's four hexadecimal escape digits are ASCII, not locale digits.
        assertDecodeFails(bundle(profileJson(nameJson = "\"\\u٠000\"")))

        val paired = bundle(profileJson(nameJson = "\"\\uD83D\\uDE0A\""))
        assertEquals("😊", ProfileBundleCodec.decode(paired.toByteArray(StandardCharsets.UTF_8)).single().name)
    }

    @Test
    fun depthSixteenIsAcceptedAndDepthSeventeenRejectedBeforeDescent() {
        fun nestedArray(depth: Int) = "[".repeat(depth) + "0" + "]".repeat(depth)
        fun rootWith(depth: Int) = "{\"nested\":${nestedArray(depth)}}"

        // The root object counts as one container; fifteen child arrays make 16.
        assertEquals(1, JsonSupport.parseObject(
            rootWith(ProfileSharingLimits.MAX_NESTING_DEPTH - 1),
            ProfileSharingLimits.MAX_NESTING_DEPTH,
        ).size)
        assertThrows(IllegalArgumentException::class.java) {
            JsonSupport.parseObject(
                rootWith(ProfileSharingLimits.MAX_NESTING_DEPTH),
                ProfileSharingLimits.MAX_NESTING_DEPTH,
            )
        }

        // Exercise the actual import boundary too: the extra member is harmless
        // at depth 16 (then rejected as unknown), but depth 17 fails in parsing.
        fun bundleWithNestedUnknown(depth: Int) =
            "{\"format\":\"com.ose.multitimer.profiles\",\"version\":1,\"profiles\":[${profileJson()}],\"extra\":${nestedArray(depth)}}"
        val depthSixteenError = assertThrows(IllegalArgumentException::class.java) {
            ProfileBundleCodec.decode(
                bundleWithNestedUnknown(ProfileSharingLimits.MAX_NESTING_DEPTH - 1)
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }
        assertTrue(depthSixteenError.message.orEmpty().contains("unknown fields [extra]"))

        val depthSeventeenError = assertThrows(IllegalArgumentException::class.java) {
            ProfileBundleCodec.decode(
                bundleWithNestedUnknown(ProfileSharingLimits.MAX_NESTING_DEPTH)
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }
        assertTrue(depthSeventeenError.message.orEmpty().contains("nesting exceeds maximum depth 16"))
    }

    @Test
    fun encoderOmitsLocalIdentityAndDecoderReturnsAnUnmodifiableList() {
        val source = BeepProfile.scriptedEvents(
            "Portable", 500L, listOf(CueEvent.create(0L, "Start", 0L)), true,
        )
        val json = String(ProfileBundleCodec.encode(listOf(SharedProfile.fromProfile(source))), StandardCharsets.UTF_8)
        assertFalse(json.contains("\"id\""))
        assertFalse(json.contains("ownerClockId"))
        assertFalse(json.contains("scriptOffsetsMillis"))

        val decoded = ProfileBundleCodec.decode(json.toByteArray(StandardCharsets.UTF_8))
        assertThrows(UnsupportedOperationException::class.java) {
            (decoded as MutableList<SharedProfile>).clear()
        }
    }

    private fun fixtureDirectory(): String = requireNotNull(System.getProperty("profileFixturesDir")) {
        "Gradle must set profileFixturesDir to the shared fixture directory"
    }

    private fun fixture(relativePath: String): ByteArray = File(fixtureDirectory(), relativePath).readBytes()

    private fun assertDecodeFails(json: String) {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileBundleCodec.decode(json.toByteArray(StandardCharsets.UTF_8))
        }
    }

    /** Compares every portable field so a stable but lossy codec cannot pass. */
    private fun assertProfilesEquivalent(
        label: String,
        expected: List<SharedProfile>,
        actual: List<SharedProfile>,
    ) {
        assertEquals("$label profile count", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { index, (left, right) ->
            val item = "$label profiles[$index]"
            assertEquals("$item name", left.name, right.name)
            assertEquals("$item mode", left.mode, right.mode)
            assertEquals("$item default beep", left.beepDurationMillis, right.beepDurationMillis)
            assertEquals("$item period", left.periodMillis, right.periodMillis)
            assertEquals("$item events", left.events.size, right.events.size)
            left.events.zip(right.events).forEachIndexed { eventIndex, (leftEvent, rightEvent) ->
                assertEquals("$item.events[$eventIndex] offset", leftEvent.offsetMillis,
                    rightEvent.offsetMillis)
                assertEquals("$item.events[$eventIndex] speech", leftEvent.speechText,
                    rightEvent.speechText)
                assertEquals("$item.events[$eventIndex] beep", leftEvent.beepDurationMillis,
                    rightEvent.beepDurationMillis)
            }
            assertEquals("$item beepOnCue", left.beepOnCue, right.beepOnCue)
            assertEquals("$item beepOnCompletion", left.beepOnCompletion, right.beepOnCompletion)
            assertEquals("$item speakOnCompletion", left.speakOnCompletion, right.speakOnCompletion)
            assertEquals("$item speakCurrentTime", left.speakCurrentTime, right.speakCurrentTime)
            assertEquals("$item speakElapsedTime", left.speakElapsedTime, right.speakElapsedTime)
            assertEquals("$item speakRemainingTime", left.speakRemainingTime, right.speakRemainingTime)
            assertEquals("$item speakOnCue", left.speakOnCue, right.speakOnCue)
            assertEquals("$item speakClockNameWithSpeech", left.speakClockNameWithSpeech,
                right.speakClockNameWithSpeech)
        }
    }

    /** Compares decoded values with fixture JSON so matching codec bugs cannot cancel out. */
    private fun assertFixtureValues(label: String, bytes: ByteArray, actual: SharedProfile) {
        val root = JsonSupport.parseObject(String(bytes, StandardCharsets.UTF_8))
        val profiles = root["profiles"] as List<*>
        val expected = profiles.single() as Map<*, *>
        assertEquals("$label name", expected["name"], actual.name)
        assertEquals("$label mode", expected["mode"], actual.mode.name)
        assertEquals("$label default beep", expected["beepDurationMillis"], actual.beepDurationMillis)
        assertEquals("$label period", expected["periodMillis"], actual.periodMillis)

        val expectedEvents = expected["events"] as List<*>
        assertEquals("$label event count", expectedEvents.size, actual.events.size)
        expectedEvents.zip(actual.events).forEachIndexed { index, (rawEvent, actualEvent) ->
            val event = rawEvent as Map<*, *>
            assertEquals("$label events[$index] offset", event["offsetMillis"],
                actualEvent.offsetMillis)
            assertEquals("$label events[$index] speech", event["speechText"],
                actualEvent.speechText)
            assertEquals("$label events[$index] beep", event["beepDurationMillis"],
                actualEvent.beepDurationMillis)
        }

        assertEquals("$label beepOnCue", expected["beepOnCue"], actual.beepOnCue)
        assertEquals("$label beepOnCompletion", expected["beepOnCompletion"],
            actual.beepOnCompletion)
        assertEquals("$label speakOnCompletion", expected["speakOnCompletion"],
            actual.speakOnCompletion)
        assertEquals("$label speakCurrentTime", expected["speakCurrentTime"],
            actual.speakCurrentTime)
        assertEquals("$label speakElapsedTime", expected["speakElapsedTime"],
            actual.speakElapsedTime)
        assertEquals("$label speakRemainingTime", expected["speakRemainingTime"],
            actual.speakRemainingTime)
        assertEquals("$label speakOnCue", expected["speakOnCue"], actual.speakOnCue)
        assertEquals("$label speakClockNameWithSpeech", expected["speakClockNameWithSpeech"],
            actual.speakClockNameWithSpeech)
    }

    private fun bundle(profiles: String): String =
        "{\"format\":\"com.ose.multitimer.profiles\",\"version\":1,\"profiles\":[$profiles]}"

    private fun profileJson(
        nameJson: String = "\"Profile\"",
        mode: String = "PERIODIC",
        period: String = "1000",
        events: String = "",
    ): String = """{"name":$nameJson,"mode":"$mode","beepDurationMillis":500,"periodMillis":$period,"events":[$events],"beepOnCue":true,"beepOnCompletion":false,"speakOnCompletion":false,"speakCurrentTime":false,"speakElapsedTime":false,"speakRemainingTime":false,"speakOnCue":true,"speakClockNameWithSpeech":false}"""

    private fun cueJson(offset: Long, speechJson: String, beep: Long): String =
        "{\"offsetMillis\":$offset,\"speechText\":$speechJson,\"beepDurationMillis\":$beep}"

    private fun sharedProfile(
        mode: BeepProfile.Mode = BeepProfile.Mode.PERIODIC,
        period: Long = 1_000L,
        events: List<SharedCue> = emptyList(),
    ) = SharedProfile("Profile", mode, 500L, period, events,
        true, false, false, false, false, false, true, false)
}
