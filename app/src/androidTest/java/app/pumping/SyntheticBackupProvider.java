package app.pumping;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Test APK only. Uses framework/Java APIs so its separate process needs no target APK runtime. */
public final class SyntheticBackupProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || !name.startsWith("synthetic-") || name.contains("/") || name.contains("..")) {
            throw new IllegalArgumentException("Invalid synthetic file");
        }
        File directory = getContext().getCacheDir();
        directory.mkdirs();
        return new File(directory, name);
    }
    @Override public String getType(Uri uri) {
        return uri.toString().endsWith(".json") ? "application/json" : "application/octet-stream";
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = file(uri);
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{f.getName(), f.length()});
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
}
