package com.ose.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/** Proves existing Java callers can use the Kotlin boundary without migration. */
public final class SharedProfileInteropTest {
    @Test
    public void javaCallerCanSnapshotAndMaterializeTheKotlinModel() {
        BeepProfile original = BeepProfile.periodic("Minute", 500L, 60_000L, true);

        SharedProfile snapshot = SharedProfile.fromProfile(original);
        BeepProfile imported = snapshot.toNewProfile();

        assertEquals("Minute", snapshot.getName());
        assertEquals(original.periodMillis, imported.periodMillis);
        assertNotEquals(original.id, imported.id);
    }
}
