package com.dream.ambientrecorder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public final class UpdateApkProvider extends ContentProvider {
    static final String MIME = "application/vnd.android.package-archive";

    static Uri uri(Context context, UpdateRelease release) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".updates")
                .appendPath(release.apkName()).build();
    }

    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || name == null || !name.endsWith(".apk")
                || !UpdateRelease.SHA256.matcher(name.substring(0, name.length() - 4)).matches()) {
            return null;
        }
        return new File(new File(getContext().getCacheDir(), "updates"), name);
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return file(uri) == null ? null : MIME; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File target = file(uri);
        if (!"r".equals(mode) || target == null || !target.isFile()) {
            throw new FileNotFoundException();
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        File target = file(uri);
        if (target == null || !target.isFile()) {
            return null;
        }
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            row.add(switch (column) {
                case OpenableColumns.DISPLAY_NAME -> "Ambient-Recorder.apk";
                case OpenableColumns.SIZE -> target.length();
                default -> null;
            });
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
