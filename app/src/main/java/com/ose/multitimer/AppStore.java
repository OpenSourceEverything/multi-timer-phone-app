package com.ose.multitimer;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Thread-safe JSON persistence for clocks and reusable beep profiles. */
public final class AppStore {
    public static final String PREFERENCES_NAME = "multi_timer_store";

    private static final String CLOCKS_KEY = "clock_instances_json_v1";
    private static final String PROFILES_KEY = "beep_profiles_json_v1";
    private static final String DEFAULT_PROFILES_SEEDED_KEY = "default_profiles_seeded_v1";
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;

    public AppStore(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        ensureDefaultProfiles();
    }

    public List<ClockInstance> loadClockInstances() {
        synchronized (LOCK) {
            return loadClockInstancesLocked();
        }
    }

    public void saveClockInstances(Collection<ClockInstance> clocks) {
        synchronized (LOCK) {
            saveClockInstancesLocked(clocks);
        }
    }

    /** Returns a detached persisted instance, or {@code null} when no ID matches. */
    public ClockInstance getClockInstance(String id) {
        String requiredId = requireId(id);
        synchronized (LOCK) {
            for (ClockInstance clock : loadClockInstancesLocked()) {
                if (requiredId.equals(clock.id)) {
                    return clock;
                }
            }
            return null;
        }
    }

    /** Inserts or replaces a clock by ID while retaining its list position. */
    public void putClockInstance(ClockInstance clock) {
        Objects.requireNonNull(clock, "clock");
        // Serialization performs complete model validation before a read-modify-write.
        clock.toJson();
        synchronized (LOCK) {
            List<ClockInstance> clocks = loadClockInstancesLocked();
            boolean replaced = false;
            for (int i = 0; i < clocks.size(); i++) {
                if (clock.id.equals(clocks.get(i).id)) {
                    clocks.set(i, clock);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                clocks.add(clock);
            }
            saveClockInstancesLocked(clocks);
        }
    }

    public boolean deleteClockInstance(String id) {
        String requiredId = requireId(id);
        synchronized (LOCK) {
            List<ClockInstance> clocks = loadClockInstancesLocked();
            boolean removed = clocks.removeIf(clock -> requiredId.equals(clock.id));
            if (removed) {
                saveClockInstancesLocked(clocks);
            }
            return removed;
        }
    }

    public List<BeepProfile> loadProfiles() {
        synchronized (LOCK) {
            return loadProfilesLocked();
        }
    }

    public void saveProfiles(Collection<BeepProfile> profiles) {
        synchronized (LOCK) {
            saveProfilesLocked(profiles);
        }
    }

    /** Returns a detached persisted profile, or {@code null} when no ID matches. */
    public BeepProfile getProfile(String id) {
        String requiredId = requireId(id);
        synchronized (LOCK) {
            for (BeepProfile profile : loadProfilesLocked()) {
                if (requiredId.equals(profile.id)) {
                    return profile;
                }
            }
            return null;
        }
    }

    /** Inserts or replaces a profile by ID while retaining its list position. */
    public void putProfile(BeepProfile profile) {
        Objects.requireNonNull(profile, "profile");
        profile.toJson();
        synchronized (LOCK) {
            List<BeepProfile> profiles = loadProfilesLocked();
            boolean replaced = false;
            for (int i = 0; i < profiles.size(); i++) {
                if (profile.id.equals(profiles.get(i).id)) {
                    profiles.set(i, profile);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                profiles.add(profile);
            }
            saveProfilesLocked(profiles);
        }
    }

    public boolean deleteProfile(String id) {
        String requiredId = requireId(id);
        synchronized (LOCK) {
            List<BeepProfile> profiles = loadProfilesLocked();
            boolean removed = profiles.removeIf(profile -> requiredId.equals(profile.id));
            if (removed) {
                saveProfilesLocked(profiles);
            }
            return removed;
        }
    }

    /** Seeds defaults exactly once, without overwriting profiles already in the store. */
    public void ensureDefaultProfiles() {
        synchronized (LOCK) {
            if (preferences.getBoolean(DEFAULT_PROFILES_SEEDED_KEY, false)) {
                return;
            }
            List<BeepProfile> existing = loadProfilesLocked();
            SharedPreferences.Editor editor = preferences.edit();
            if (existing.isEmpty()) {
                editor.putString(PROFILES_KEY, encodeProfiles(BeepProfile.defaultProfiles()));
            }
            editor.putBoolean(DEFAULT_PROFILES_SEEDED_KEY, true).apply();
        }
    }

    private List<ClockInstance> loadClockInstancesLocked() {
        String json;
        try {
            json = preferences.getString(CLOCKS_KEY, "[]");
            return decodeClocks(json == null ? "[]" : json);
        } catch (RuntimeException invalidStoredValue) {
            return new ArrayList<>();
        }
    }

    private void saveClockInstancesLocked(Collection<ClockInstance> clocks) {
        preferences.edit().putString(CLOCKS_KEY, encodeClocks(clocks)).apply();
    }

    private List<BeepProfile> loadProfilesLocked() {
        String json;
        try {
            json = preferences.getString(PROFILES_KEY, "[]");
            return decodeProfiles(json == null ? "[]" : json);
        } catch (RuntimeException invalidStoredValue) {
            return new ArrayList<>();
        }
    }

    private void saveProfilesLocked(Collection<BeepProfile> profiles) {
        preferences.edit().putString(PROFILES_KEY, encodeProfiles(profiles)).apply();
    }

    private static String encodeClocks(Collection<ClockInstance> clocks) {
        Objects.requireNonNull(clocks, "clocks");
        Set<String> ids = new HashSet<>();
        StringBuilder json = new StringBuilder("[");
        int index = 0;
        for (ClockInstance clock : clocks) {
            Objects.requireNonNull(clock, "clock");
            String encoded = clock.toJson();
            if (!ids.add(clock.id)) {
                throw new IllegalArgumentException("Duplicate clock ID: " + clock.id);
            }
            if (index++ > 0) {
                json.append(',');
            }
            json.append(encoded);
        }
        return json.append(']').toString();
    }

    private static List<ClockInstance> decodeClocks(String json) {
        List<ClockInstance> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object entry : JsonSupport.parseArray(json)) {
            if (!(entry instanceof Map)) {
                throw new IllegalArgumentException("Clock list entries must be objects");
            }
            @SuppressWarnings("unchecked")
            ClockInstance clock = ClockInstance.fromJsonObject((Map<String, Object>) entry);
            if (!ids.add(clock.id)) {
                throw new IllegalArgumentException("Duplicate clock ID: " + clock.id);
            }
            result.add(clock);
        }
        return result;
    }

    private static String encodeProfiles(Collection<BeepProfile> profiles) {
        Objects.requireNonNull(profiles, "profiles");
        Set<String> ids = new HashSet<>();
        StringBuilder json = new StringBuilder("[");
        int index = 0;
        for (BeepProfile profile : profiles) {
            Objects.requireNonNull(profile, "profile");
            String encoded = profile.toJson();
            if (!ids.add(profile.id)) {
                throw new IllegalArgumentException("Duplicate profile ID: " + profile.id);
            }
            if (index++ > 0) {
                json.append(',');
            }
            json.append(encoded);
        }
        return json.append(']').toString();
    }

    private static List<BeepProfile> decodeProfiles(String json) {
        List<BeepProfile> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object entry : JsonSupport.parseArray(json)) {
            if (!(entry instanceof Map)) {
                throw new IllegalArgumentException("Profile list entries must be objects");
            }
            @SuppressWarnings("unchecked")
            BeepProfile profile = BeepProfile.fromJsonObject((Map<String, Object>) entry);
            if (!ids.add(profile.id)) {
                throw new IllegalArgumentException("Duplicate profile ID: " + profile.id);
            }
            result.add(profile);
        }
        return result;
    }

    private static String requireId(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("id cannot be blank");
        }
        return id.trim();
    }
}
