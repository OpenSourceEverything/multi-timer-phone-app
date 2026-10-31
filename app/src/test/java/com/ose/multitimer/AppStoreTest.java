package com.ose.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Store contracts exercised with an in-memory SharedPreferences test double.
 *
 * <p>Rebuilding AppStore over the same preferences tests serialized state and
 * migration rules. It does not emulate Android process death, disk flushes or
 * foreground-service restart.</p>
 */
public final class AppStoreTest {
    private static final String CLOCKS_KEY = "clock_instances_json_v1";

    @Test
    public void freshStoreSeedsDefaultsExactlyOnce() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        AppStore first = new AppStore(preferences);

        List<BeepProfile> seeded = first.loadProfiles();
        assertEquals(6, seeded.size());
        assertEquals(BeepProfile.DEFAULT_EVERY_MINUTE_ID, seeded.get(0).id);

        first.saveProfiles(Collections.emptyList());
        AppStore reconstructed = new AppStore(preferences);
        assertTrue(reconstructed.loadProfiles().isEmpty());
    }

    @Test
    public void existingProfilesAreNotOverwrittenDuringInitialSeedCheck() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        BeepProfile custom = BeepProfile.periodic("Custom", 100L, 2_000L, false);
        preferences.edit()
                .putString("beep_profiles_json_v1", "[" + custom.toJson() + "]")
                .apply();

        AppStore store = new AppStore(preferences);
        assertEquals(4, store.loadProfiles().size());
        assertEquals(custom.id, store.loadProfiles().get(0).id);
        assertEquals(BeepProfile.DEFAULT_CURRENT_TIME_ID, store.loadProfiles().get(1).id);
        assertEquals(BeepProfile.DEFAULT_ELAPSED_TIME_ID, store.loadProfiles().get(2).id);
        assertEquals(BeepProfile.DEFAULT_REMAINING_TIME_ID, store.loadProfiles().get(3).id);
    }

    @Test
    public void deletedDefaultIsNotRestoredAfterStoreReconstruction() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        AppStore first = new AppStore(preferences);

        assertTrue(first.deleteProfile(BeepProfile.DEFAULT_ELAPSED_TIME_ID));

        AppStore reconstructed = new AppStore(preferences);

        assertNull(reconstructed.getProfile(BeepProfile.DEFAULT_ELAPSED_TIME_ID));
    }

    @Test
    public void clockLoadSaveAndCrudRoundTripInStableOrder() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        AppStore firstStore = new AppStore(preferences);
        ClockInstance first = ClockInstance.create(
                ClockInstance.Type.STOPWATCH, "First", 0L, null, 0L);
        ClockInstance second = ClockInstance.create(
                ClockInstance.Type.TIMER, "Second", 5_000L,
                BeepProfile.DEFAULT_EVERY_MINUTE_ID, 0L);

        firstStore.saveClockInstances(Arrays.asList(first, second));
        AppStore secondStore = new AppStore(preferences);
        List<ClockInstance> loaded = secondStore.loadClockInstances();
        assertEquals(Arrays.asList(first.id, second.id),
                Arrays.asList(loaded.get(0).id, loaded.get(1).id));
        assertEquals("Second", secondStore.getClockInstance(second.id).name);
        assertNull(secondStore.getClockInstance("missing"));

        ClockInstance replacement = secondStore.getClockInstance(first.id);
        assertNotNull(replacement);
        replacement.name = "Renamed";
        secondStore.putClockInstance(replacement);
        assertEquals("Renamed", secondStore.loadClockInstances().get(0).name);

        ClockInstance third = ClockInstance.create(
                ClockInstance.Type.COUNTDOWN, "Third", 50_000L, null, 0L);
        secondStore.putClockInstance(third);
        assertEquals(third.id, secondStore.loadClockInstances().get(2).id);
        assertTrue(secondStore.deleteClockInstance(second.id));
        assertFalse(secondStore.deleteClockInstance(second.id));
        assertEquals(Arrays.asList(first.id, third.id), Arrays.asList(
                secondStore.loadClockInstances().get(0).id,
                secondStore.loadClockInstances().get(1).id));
    }

    @Test
    public void runningTimerReconstructionPreservesProgressAcrossPauseAndResume() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        AppStore firstStore = new AppStore(preferences);
        ClockInstance timer = ClockInstance.create(
                ClockInstance.Type.TIMER, "Tea", 10_000L,
                null, 100_000L, 50_000L);
        timer.start(100_000L, 50_000L);
        firstStore.putClockInstance(timer);

        // Reconstruct both the store and its model, retaining only saved data.
        // Reading progress must include the three seconds since the saved anchor.
        AppStore reconstructed = new AppStore(preferences);
        ClockInstance restored = reconstructed.getClockInstance(timer.id);
        assertNotNull(restored);
        assertTrue(restored.running);
        assertEquals(7_000L, restored.valueAt(103_000L, 53_000L));
        assertEquals(3_000L, restored.cueElapsedAt(103_000L, 53_000L));

        restored.pause(103_000L, 53_000L);
        reconstructed.putClockInstance(restored);
        ClockInstance paused = new AppStore(preferences).getClockInstance(timer.id);
        assertNotNull(paused);
        assertFalse(paused.running);
        assertEquals(7_000L, paused.valueAt(200_000L, 90_000L));

        // The long paused interval is not active timer time. Only the 500 ms
        // following resume may reduce the remaining seven seconds.
        paused.start(200_000L, 90_000L);
        assertEquals(6_500L, paused.valueAt(200_500L, 90_500L));
        assertEquals(3_500L, paused.cueElapsedAt(200_500L, 90_500L));
    }

    @Test
    public void rejectedClockBatchLeavesPreviouslySavedClocksUnchanged() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        AppStore store = new AppStore(preferences);
        ClockInstance saved = ClockInstance.create(
                ClockInstance.Type.STOPWATCH, "Keep", 0L, null, 0L);
        store.putClockInstance(saved);
        String before = preferences.getString(CLOCKS_KEY, null);

        ClockInstance candidate = ClockInstance.create(
                ClockInstance.Type.TIMER, "New", 5_000L, null, 0L);
        ClockInstance invalid = ClockInstance.create(
                ClockInstance.Type.STOPWATCH, "Initially valid", 0L, null, 0L);
        invalid.name = " "; // Deliberate malformed input: names cannot be blank.

        // A valid first element must not be written before a later element fails.
        assertThrows(IllegalArgumentException.class,
                () -> store.saveClockInstances(Arrays.asList(candidate, invalid)));

        assertEquals(before, preferences.getString(CLOCKS_KEY, null));
        assertNotNull(store.getClockInstance(saved.id));
        assertNull(store.getClockInstance(candidate.id));
    }

    @Test
    public void rejectedProfileBatchLeavesPreviouslySavedProfilesUnchanged() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        AppStore store = new AppStore(preferences);
        BeepProfile saved = BeepProfile.periodic("Keep", 100L, 2_000L, false);
        store.saveProfiles(Collections.singletonList(saved));
        String before = preferences.getString("beep_profiles_json_v1", null);
        BeepProfile candidate = BeepProfile.periodic("New", 100L, 1_000L, false);
        BeepProfile invalid = saved.copyAs("Invalid");
        invalid.periodMillis = 0L; // Deliberate violation of the positive-period rule.

        assertThrows(IllegalArgumentException.class,
                () -> store.saveProfiles(Arrays.asList(candidate, invalid)));

        // Checking storage after the exception is essential: rejection alone
        // would not detect an implementation that partially replaced valid data.
        assertEquals(before, preferences.getString("beep_profiles_json_v1", null));
        assertEquals(saved.id, store.loadProfiles().get(0).id);
        assertNull(store.getProfile(candidate.id));
    }

    @Test
    public void importedProfileBatchAppendsToLatestListAndLeavesRunningClockUntouched() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        AppStore store = new AppStore(preferences);
        ClockInstance running = ClockInstance.create(
                ClockInstance.Type.TIMER, "Keep running", 60_000L,
                BeepProfile.DEFAULT_EVERY_MINUTE_ID, 0L);
        running.start(100_000L, 50_000L);
        store.putClockInstance(running);
        String clocksBeforeImport = preferences.getString(CLOCKS_KEY, null);

        // This profile is deliberately added after the import preview could
        // have been created. The batch operation must read the latest store list.
        BeepProfile addedWhilePreviewWasOpen = BeepProfile.periodic(
                "Added during preview", 100L, 2_000L, false);
        store.putProfile(addedWhilePreviewWasOpen);
        BeepProfile importedOne = BeepProfile.periodic("Imported one", 100L, 3_000L, false);
        BeepProfile importedTwo = BeepProfile.periodic("Imported two", 100L, 4_000L, false);

        store.appendReusableProfiles(Arrays.asList(importedOne, importedTwo));

        assertNotNull(store.getProfile(addedWhilePreviewWasOpen.id));
        assertNotNull(store.getProfile(importedOne.id));
        assertNotNull(store.getProfile(importedTwo.id));
        assertEquals(clocksBeforeImport, preferences.getString(CLOCKS_KEY, null));
        ClockInstance restored = store.getClockInstance(running.id);
        assertNotNull(restored);
        assertTrue(restored.running);
        assertEquals(Collections.singletonList(BeepProfile.DEFAULT_EVERY_MINUTE_ID),
                restored.attachedProfileIds());
    }

    @Test
    public void rejectedImportedBatchDoesNotWriteEarlierValidCandidate() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        AppStore store = new AppStore(preferences);
        String before = preferences.getString("beep_profiles_json_v1", null);
        BeepProfile validFirst = BeepProfile.periodic("Valid first", 100L, 1_000L, false);
        BeepProfile invalidSecond = BeepProfile.periodic(
                "Timer-only", 100L, 2_000L, false);
        invalidSecond.reusable = false;
        invalidSecond.ownerClockId = "timer-1";

        assertThrows(IllegalArgumentException.class,
                () -> store.appendReusableProfiles(Arrays.asList(validFirst, invalidSecond)));

        assertEquals(before, preferences.getString("beep_profiles_json_v1", null));
        assertNull(store.getProfile(validFirst.id));
    }

    @Test
    public void importedProfileIdCollisionDoesNotReplaceAnExistingTemplate() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        AppStore store = new AppStore(preferences);
        String before = preferences.getString("beep_profiles_json_v1", null);
        BeepProfile collision = BeepProfile.periodic("Impostor", 100L, 1_000L, false);
        collision.id = BeepProfile.DEFAULT_EVERY_MINUTE_ID;

        assertThrows(IllegalArgumentException.class,
                () -> store.appendReusableProfiles(Collections.singletonList(collision)));

        assertEquals(before, preferences.getString("beep_profiles_json_v1", null));
        assertEquals("Beep (Every Minute)",
                store.getProfile(BeepProfile.DEFAULT_EVERY_MINUTE_ID).name);
    }

    @Test
    public void importDoesNotReplaceUnreadableSavedProfileData() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        AppStore store = new AppStore(preferences);
        String damagedValue = "not a profile list";
        preferences.edit().putString("beep_profiles_json_v1", damagedValue).apply();
        BeepProfile imported = BeepProfile.periodic("Imported", 100L, 1_000L, false);

        assertThrows(IllegalStateException.class,
                () -> store.appendReusableProfiles(Collections.singletonList(imported)));

        assertEquals(damagedValue,
                preferences.getString("beep_profiles_json_v1", null));
    }

    @Test
    public void profileCrudReplacesInPlaceAndPersists() {
        AppStore store = new AppStore(new FakeSharedPreferences());
        store.saveProfiles(Collections.emptyList());
        BeepProfile first = BeepProfile.periodic("First", 100L, 1_000L, false);
        BeepProfile second = BeepProfile.scripted(
                "Second", 200L, Collections.singletonList(2_000L), true);
        store.saveProfiles(Arrays.asList(first, second));

        BeepProfile replacement = store.getProfile(first.id);
        assertNotNull(replacement);
        replacement.name = "Changed";
        store.putProfile(replacement);
        assertEquals("Changed", store.loadProfiles().get(0).name);
        assertEquals(second.id, store.loadProfiles().get(1).id);
        assertNull(store.getProfile("missing"));
        assertTrue(store.deleteProfile(second.id));
        assertFalse(store.deleteProfile(second.id));
    }

    @Test
    public void timerOwnedProfilesAreHiddenFromReusableTemplates() {
        AppStore store = new AppStore(new FakeSharedPreferences());
        BeepProfile owned = BeepProfile.periodic("Timer-only", 100L, 30_000L, false);
        owned.reusable = false;
        owned.ownerClockId = "clock-1";
        store.putProfile(owned);

        assertNotNull(store.getProfile(owned.id));
        assertEquals(1, store.loadProfilesOwnedBy("clock-1").size());
        assertFalse(store.loadReusableProfiles().stream()
                .anyMatch(profile -> owned.id.equals(profile.id)));
        assertEquals(1, store.deleteUnreferencedProfilesOwnedBy(
                "clock-1", Collections.emptyList()));
        assertNull(store.getProfile(owned.id));
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateClockIdsAreRejectedOnSave() {
        AppStore store = new AppStore(new FakeSharedPreferences());
        ClockInstance clock = ClockInstance.create(
                ClockInstance.Type.STOPWATCH, "One", 0L, null, 0L);
        store.saveClockInstances(Arrays.asList(clock, clock));
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateProfileIdsAreRejectedOnSave() {
        AppStore store = new AppStore(new FakeSharedPreferences());
        BeepProfile profile = BeepProfile.periodic("One", 100L, 1_000L, false);
        store.saveProfiles(Arrays.asList(profile, profile));
    }

    @Test
    public void malformedOrWrongRootClockJsonFallsBackToEmptyList() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        AppStore store = new AppStore(preferences);
        preferences.edit().putString(CLOCKS_KEY, "not-json").apply();
        assertTrue(store.loadClockInstances().isEmpty());
        preferences.edit().putString(CLOCKS_KEY, "{}").apply();
        assertTrue(store.loadClockInstances().isEmpty());
    }

    /** Shares values across store instances but deliberately performs no disk I/O. */
    private static final class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public synchronized Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public synchronized String getString(String key, String defaultValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defaultValue;
        }

        @Override
        public synchronized Set<String> getStringSet(String key, Set<String> defaultValues) {
            Object value = values.get(key);
            if (!(value instanceof Set)) {
                return defaultValues;
            }
            @SuppressWarnings("unchecked")
            Set<String> strings = (Set<String>) value;
            return new HashSet<>(strings);
        }

        @Override
        public synchronized int getInt(String key, int defaultValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defaultValue;
        }

        @Override
        public synchronized long getLong(String key, long defaultValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defaultValue;
        }

        @Override
        public synchronized float getFloat(String key, float defaultValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defaultValue;
        }

        @Override
        public synchronized boolean getBoolean(String key, boolean defaultValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defaultValue;
        }

        @Override
        public synchronized boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new FakeEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
            // Listener behavior is outside AppStore's contract.
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
            // Listener behavior is outside AppStore's contract.
        }

        private final class FakeEditor implements Editor {
            private final Map<String, Object> updates = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                updates.put(key, values == null ? null : new HashSet<>(values));
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor remove(String key) {
                updates.remove(key);
                removals.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                return this;
            }

            @Override
            public boolean commit() {
                synchronized (FakeSharedPreferences.this) {
                    if (clear) {
                        values.clear();
                    }
                    for (String key : removals) {
                        values.remove(key);
                    }
                    for (Map.Entry<String, Object> update : updates.entrySet()) {
                        if (update.getValue() == null) {
                            values.remove(update.getKey());
                        } else {
                            values.put(update.getKey(), update.getValue());
                        }
                    }
                }
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        }
    }
}
