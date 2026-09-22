package com.ose.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

public final class AppStoreTest {
    private static final String CLOCKS_KEY = "clock_instances_json_v1";

    @Test
    public void freshStoreSeedsDefaultsExactlyOnce() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        AppStore first = new AppStore(preferences);

        List<BeepProfile> seeded = first.loadProfiles();
        assertEquals(3, seeded.size());
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
        assertEquals(1, store.loadProfiles().size());
        assertEquals(custom.id, store.loadProfiles().get(0).id);
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
