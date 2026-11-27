package com.dream.ambientrecorder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashSet;
import java.util.Set;

final class SyncJournal extends SQLiteOpenHelper {
    private static final String TABLE = "sent";
    private final String destination;

    SyncJournal(Context context, SyncSettings.Target target) {
        super(context, "backup_status.db", null, 1);
        destination = target.username() + "@" + target.host() + ":" + target.directory() + " " + target.fingerprint();
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE sent (destination TEXT NOT NULL, name TEXT NOT NULL, "
                + "PRIMARY KEY(destination, name)) WITHOUT ROWID");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int previous, int next) {
        throw new IllegalStateException("Unexpected backup journal version");
    }

    Set<String> sent() {
        Set<String> sent = new HashSet<>();
        try (Cursor records = getReadableDatabase().query(TABLE, new String[]{"name"}, "destination = ?",
                new String[]{destination}, null, null, null)) {
            while (records.moveToNext()) {
                sent.add(records.getString(0));
            }
        }
        return sent;
    }

    void record(String name) {
        ContentValues values = new ContentValues();
        values.put("destination", destination);
        values.put("name", name);
        getWritableDatabase().insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }
}
