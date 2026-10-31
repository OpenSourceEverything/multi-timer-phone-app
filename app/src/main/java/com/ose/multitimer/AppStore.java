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
    /** Marks the one-time migration that stopped absent defaults being resurrected. */
    private static final String DEFAULT_PROFILE_MIGRATION_V2_KEY =
            "default_profile_migration_v2";
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

    /** Returns only profiles intended to be offered as reusable templates. */
    public List<BeepProfile> loadReusableProfiles() {
        synchronized (LOCK) {
            List<BeepProfile> reusable = new ArrayList<>();
            for (BeepProfile profile : loadProfilesLocked()) {
                if (profile.reusable) {
                    reusable.add(profile);
                }
            }
            return reusable;
        }
    }

    /** Returns timer-owned profiles for one clock, including only exact owner matches. */
    public List<BeepProfile> loadProfilesOwnedBy(String clockId) {
        String requiredId = requireId(clockId);
        synchronized (LOCK) {
            List<BeepProfile> owned = new ArrayList<>();
            for (BeepProfile profile : loadProfilesLocked()) {
                if (!profile.reusable && requiredId.equals(profile.ownerClockId)) {
                    owned.add(profile);
                }
            }
            return owned;
        }
    }

    /** Deletes every timer-owned profile belonging to one clock or draft owner. */
    public int deleteProfilesOwnedBy(String clockId) {
        String requiredId = requireId(clockId);
        synchronized (LOCK) {
            List<BeepProfile> profiles = loadProfilesLocked();
            int before = profiles.size();
            profiles.removeIf(profile -> !profile.reusable
                    && requiredId.equals(profile.ownerClockId));
            if (profiles.size() != before) {
                saveProfilesLocked(profiles);
            }
            return before - profiles.size();
        }
    }

    /** Removes owned profiles not retained by a saved clock configuration. */
    public int deleteUnreferencedProfilesOwnedBy(
            String clockId, Collection<String> retainedProfileIds) {
        String requiredId = requireId(clockId);
        Objects.requireNonNull(retainedProfileIds, "retainedProfileIds");
        synchronized (LOCK) {
            List<BeepProfile> profiles = loadProfilesLocked();
            int before = profiles.size();
            profiles.removeIf(profile -> !profile.reusable
                    && requiredId.equals(profile.ownerClockId)
                    && !retainedProfileIds.contains(profile.id));
            if (profiles.size() != before) {
                saveProfilesLocked(profiles);
            }
            return before - profiles.size();
        }
    }

    /** Removes hidden profiles that are no longer referenced by any saved clock. */
    public int pruneOrphanedOwnedProfiles(Collection<ClockInstance> clocks) {
        Objects.requireNonNull(clocks, "clocks");
        Set<String> referencedProfileIds = new HashSet<>();
        for (ClockInstance clock : clocks) {
            referencedProfileIds.addAll(clock.attachedProfileIds());
        }
        synchronized (LOCK) {
            List<BeepProfile> profiles = loadProfilesLocked();
            int before = profiles.size();
            profiles.removeIf(profile -> !profile.reusable
                    && !referencedProfileIds.contains(profile.id));
            if (profiles.size() != before) {
                saveProfilesLocked(profiles);
            }
            return before - profiles.size();
        }
    }

    public void saveProfiles(Collection<BeepProfile> profiles) {
        synchronized (LOCK) {
            saveProfilesLocked(profiles);
        }
    }

    /**
     * Appends one validated group of reusable profiles without replacing entries
     * that may have been saved since an import preview was opened.
     *
     * <p>All incoming values are copied and validated before the stored list is
     * read or changed. The read, identity checks, append and single preference
     * write then happen under {@link #LOCK}, so another AppStore operation in
     * this process cannot be lost between the read and write. SharedPreferences
     * {@code apply()} updates its in-memory view immediately and writes disk
     * asynchronously; this is one logical update, not a crash-durable database
     * transaction.</p>
     *
     * @param additions non-empty imported profiles with unique IDs, all marked
     *                  reusable and not owned by a timer
     * @throws IllegalArgumentException if any value is invalid or an ID already
     *                                  exists; no profile-list write is issued
     * @throws IllegalStateException if the existing saved profile list cannot
     *                               be decoded safely
     */
    public void appendReusableProfiles(Collection<BeepProfile> additions) {
        Objects.requireNonNull(additions, "additions");
        List<BeepProfile> candidates = new ArrayList<>(additions);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("At least one profile is required");
        }

        // Make detached copies before entering the critical section. Serializing
        // each candidate invokes the model's full validation; deserializing that
        // exact value prevents the caller from later mutating the stored object.
        List<BeepProfile> validatedAdditions = new ArrayList<>(candidates.size());
        Set<String> additionIds = new HashSet<>();
        for (BeepProfile addition : candidates) {
            Objects.requireNonNull(addition, "profile");
            BeepProfile snapshot = BeepProfile.fromJson(addition.toJson());
            if (!snapshot.reusable || snapshot.ownerClockId != null) {
                throw new IllegalArgumentException(
                        "Imported profiles must be reusable and have no clock owner");
            }
            if (!additionIds.add(snapshot.id)) {
                throw new IllegalArgumentException(
                        "Duplicate imported profile ID: " + snapshot.id);
            }
            validatedAdditions.add(snapshot);
        }

        synchronized (LOCK) {
            // Read the latest list while holding the same lock used by every
            // profile mutation. In particular, do not save the stale list that
            // existed when a file picker or preview first opened.
            List<BeepProfile> latestProfiles = loadProfilesForAppendLocked();
            Set<String> existingIds = new HashSet<>();
            for (BeepProfile existing : latestProfiles) {
                existingIds.add(existing.id);
            }
            for (BeepProfile addition : validatedAdditions) {
                if (existingIds.contains(addition.id)) {
                    throw new IllegalArgumentException(
                            "Imported profile ID already exists: " + addition.id);
                }
            }

            latestProfiles.addAll(validatedAdditions);
            // encodeProfiles validates the complete result before this one
            // SharedPreferences editor is applied. No partial batch is written.
            saveProfilesLocked(latestProfiles);
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

    /** Seeds defaults without overwriting profiles or resurrecting user deletions. */
    public void ensureDefaultProfiles() {
        synchronized (LOCK) {
            if (preferences.getBoolean(DEFAULT_PROFILES_SEEDED_KEY, false)) {
                // Older builds attempted to add absent speech defaults on every startup.
                // The first fixed build records the current set as authoritative and
                // deliberately does not add an absent ID: its absence may be a deletion.
                if (!preferences.getBoolean(DEFAULT_PROFILE_MIGRATION_V2_KEY, false)) {
                    List<BeepProfile> existing = loadProfilesLocked();
                    boolean changed = refreshDefaultNames(existing);
                    SharedPreferences.Editor editor = preferences.edit()
                            .putBoolean(DEFAULT_PROFILE_MIGRATION_V2_KEY, true);
                    if (changed) {
                        editor.putString(PROFILES_KEY, encodeProfiles(existing));
                    }
                    editor.apply();
                }
                return;
            }
            List<BeepProfile> existing = loadProfilesLocked();
            SharedPreferences.Editor editor = preferences.edit();
            if (existing.isEmpty()) {
                existing = new ArrayList<>(BeepProfile.defaultProfiles());
            } else {
                // This is the initial migration from a pre-profile version. The three
                // spoken-time defaults are introduced here once; later absence means
                // the user removed the profile and must be respected.
                addMissingDefault(existing, BeepProfile.DEFAULT_CURRENT_TIME_ID);
                addMissingDefault(existing, BeepProfile.DEFAULT_ELAPSED_TIME_ID);
                addMissingDefault(existing, BeepProfile.DEFAULT_REMAINING_TIME_ID);
                refreshDefaultNames(existing);
            }
            editor.putString(PROFILES_KEY, encodeProfiles(existing));
            editor.putBoolean(DEFAULT_PROFILES_SEEDED_KEY, true);
            editor.putBoolean(DEFAULT_PROFILE_MIGRATION_V2_KEY, true).apply();
        }
    }

    private static BeepProfile findDefaultProfile(String id) {
        for (BeepProfile profile : BeepProfile.defaultProfiles()) {
            if (id.equals(profile.id)) {
                return profile;
            }
        }
        throw new IllegalStateException("Missing built-in profile: " + id);
    }

    private static boolean addMissingDefault(List<BeepProfile> profiles, String id) {
        if (profiles.stream().anyMatch(profile -> id.equals(profile.id))) {
            return false;
        }
        profiles.add(findDefaultProfile(id));
        return true;
    }

    /** Renames only untouched legacy built-ins, preserving deliberate user renames. */
    private static boolean refreshDefaultNames(List<BeepProfile> profiles) {
        boolean changed = false;
        changed |= renameDefault(profiles, BeepProfile.DEFAULT_EVERY_MINUTE_ID,
                "Every minute", "Beep (Every Minute)");
        changed |= renameDefault(profiles, BeepProfile.DEFAULT_COMPLETION_ONLY_ID,
                "Completion only", "Beep At Completion");
        changed |= renameDefault(profiles, BeepProfile.DEFAULT_CURRENT_TIME_ID,
                "Current time", "Speak Current Time (Every Minute)");
        changed |= renameDefault(profiles, BeepProfile.DEFAULT_ELAPSED_TIME_ID,
                "Time elapsed", "Time Elapsed (Spoken Aloud)");
        changed |= renameDefault(profiles, BeepProfile.DEFAULT_REMAINING_TIME_ID,
                "Time remaining", "Time Remaining (Spoken Aloud)");
        return changed;
    }

    private static boolean renameDefault(
            List<BeepProfile> profiles, String id, String oldName, String newName) {
        for (BeepProfile profile : profiles) {
            if (id.equals(profile.id) && oldName.equals(profile.name)) {
                profile.name = newName;
                return true;
            }
        }
        return false;
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

    /**
     * Reads profiles strictly for an import append.
     *
     * <p>Ordinary display reads intentionally fall back to an empty list when
     * stored JSON is damaged. An import must not reuse that recovery behavior:
     * doing so could replace profiles that were present but unreadable. Reject
     * the append and leave the original preference value untouched instead.</p>
     */
    private List<BeepProfile> loadProfilesForAppendLocked() {
        try {
            String json = preferences.getString(PROFILES_KEY, "[]");
            return decodeProfiles(json == null ? "[]" : json);
        } catch (RuntimeException invalidStoredValue) {
            throw new IllegalStateException(
                    "Cannot import profiles because saved profiles are invalid",
                    invalidStoredValue);
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
