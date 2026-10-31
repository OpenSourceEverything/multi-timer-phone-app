package com.ose.multitimer;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a user-requested ZIP containing a readable summary and structured evidence.
 *
 * <p>The caller runs this on a worker thread. Export reads a bounded set of diagnostic rows,
 * never changes timer state, and exposes the resulting cache file only through the app's
 * read-only content provider and an explicit Android share grant.</p>
 */
final class DiagnosticExporter {
    private DiagnosticExporter() {
        // Static export helper.
    }

    /** Generates the archive and returns a URI that the share sheet can read. */
    static Uri export(Context context, AppStore appStore) throws IOException {
        DiagnosticStore.ReportData data;
        try (DiagnosticStore store = new DiagnosticStore(context)) {
            data = store.readReport();
        }
        Map<String, String> clockNames = new HashMap<>();
        for (ClockInstance clock : appStore.loadClockInstances()) {
            clockNames.put(clock.id, clock.name);
        }
        Map<String, String> profileNames = new HashMap<>();
        for (BeepProfile profile : appStore.loadProfiles()) {
            profileNames.put(profile.id, profile.name);
        }
        DiagnosticReport.Output output = DiagnosticReport.render(data.events, data.totalEvents,
                data.snapshots, data.totalSnapshots, System.currentTimeMillis(),
                clockNames, profileNames);
        File directory = new File(context.getCacheDir(), "diagnostic_exports");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create diagnostic export cache");
        }
        pruneOldArchives(directory, System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1_000L);
        File archive = File.createTempFile("diagnostics-", ".zip", directory);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            writeEntry(zip, "diagnostics.txt", output.text);
            writeEntry(zip, "diagnostics.json", output.json);
        } catch (IOException error) {
            // This is only the newly created, incomplete export; the retained ledger is untouched.
            archive.delete();
            throw error;
        }
        return new Uri.Builder().scheme("content")
                .authority(context.getPackageName() + ".diagnostics")
                .appendPath(archive.getName()).build();
    }

    /** Writes one UTF-8 archive member and closes only that member. */
    private static void writeEntry(ZipOutputStream zip, String name, String body)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Removes only expired archives generated in this dedicated cache directory. */
    private static void pruneOldArchives(File directory, long cutoffWallMillis) {
        File[] archives = directory.listFiles();
        if (archives == null) {
            return;
        }
        for (File archive : archives) {
            if (archive.isFile()
                    && archive.getName().matches("diagnostics-[0-9]+\\.zip")
                    && archive.lastModified() < cutoffWallMillis) {
                archive.delete();
            }
        }
    }
}
