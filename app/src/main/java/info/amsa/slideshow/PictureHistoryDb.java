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

    PictureHistoryDb(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }
    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_ENTRIES);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

    }

    void insertPicture(MainActivity.Picture picture) {
        ContentValues values = new ContentValues();
        values.put(COL_PATH_NAME, picture.file.getName());
        values.put(COL_LAST_DISPLAYED, System.currentTimeMillis());
        getWritableDatabase().insert(TABLE_NAME, null, values);
    }

    public long lookupPicture(MainActivity.Picture picture) {
        Cursor cursor = getReadableDatabase().query(TABLE_NAME,
                new String[]{COL_LAST_DISPLAYED}, COL_PATH_NAME + "= ?",
                new String[]{picture.file.getName()},
                null, null, null);
        return cursor.moveToNext() ? cursor.getLong(0) : 0;
    }
}
