package com.ose.multitimer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Grants temporary read access to one diagnostics archive selected by the user.
 *
 * <p>The provider is not exported. Android's share intent grants the chosen recipient access to
 * the exact content URI; callers cannot use it to browse app databases or other cache files.</p>
 */
public final class DiagnosticExportProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/zip";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File archive = resolve(uri);
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) {
                row[index] = archive.getName();
            } else if (OpenableColumns.SIZE.equals(columns[index])) {
                row[index] = archive.length();
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Diagnostic exports are read-only");
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Diagnostic exports are read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("Diagnostic exports are read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Diagnostic exports are read-only");
    }

    /** Accepts only a generated ZIP in the dedicated cache directory. */
    private File resolve(Uri uri) {
        if (getContext() == null
                || !getContext().getPackageName().concat(".diagnostics").equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1) {
            throw new IllegalArgumentException("Unknown diagnostic export URI");
        }
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("diagnostics-[0-9]+\\.zip")) {
            throw new IllegalArgumentException("Unknown diagnostic export filename");
        }
        File directory = new File(getContext().getCacheDir(), "diagnostic_exports");
        try {
            File file = new File(directory, name).getCanonicalFile();
            if (!directory.getCanonicalFile().equals(file.getParentFile()) || !file.isFile()) {
                throw new IllegalArgumentException("Missing diagnostic export");
            }
            return file;
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot resolve diagnostic export", error);
        }
    }
}
