package info.amsa.slideshow;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

class PictureHistoryDb extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "pictureHistory.db";
    private static final int DATABASE_VERSION = 1;

    private static final String TABLE_NAME = "HISTORY";
    private static final String COL_PATH_NAME = "PATH_NAME";
    private static final String COL_LAST_DISPLAYED = "LAST_DISPLAYED";
    private static final String SQL_CREATE_ENTRIES =
            "CREATE TABLE " + TABLE_NAME + " (" +
                    COL_PATH_NAME + " TEXT PRIMARY KEY," +
                    COL_LAST_DISPLAYED + " INTEGER"
            + ")";

    PictureHistoryDb(final Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }
    @Override
    public void onCreate(final SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_ENTRIES);
    }

    @Override
    public void onUpgrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {

    }

    void insertPicture(final MainFragment.Picture picture) {
        final ContentValues values = new ContentValues();
        values.put(COL_PATH_NAME, picture.file.getName());
        values.put(COL_LAST_DISPLAYED, System.currentTimeMillis());
        try (final SQLiteDatabase db = getWritableDatabase()) {
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    public long lookupPicture(final MainFragment.Picture picture) {
        try (final SQLiteDatabase readableDatabase = getReadableDatabase();
             final Cursor cursor = readableDatabase.query(TABLE_NAME,
                new String[]{COL_LAST_DISPLAYED}, COL_PATH_NAME + "= ?",
                new String[]{picture.file.getName()},
                null, null, null))
        {
            return cursor.moveToNext() ? cursor.getLong(0) : 0;
        }
    }
}
