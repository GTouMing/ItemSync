package github.gtouming.itemsync.android;

import android.database.Cursor;
import github.gtouming.itemsync.store.metadata.MetadataException;
import github.gtouming.itemsync.store.metadata.sql.RowCursor;

final class AndroidRowCursor implements RowCursor {

    private final Cursor cursor;
    private boolean wasNull;

    AndroidRowCursor(Cursor cursor) {
        this.cursor = cursor;
    }

    @Override
    public boolean next() {
        return cursor.moveToNext();
    }

    @Override
    public String getString(String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        if (cursor.isNull(index)) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        return cursor.getString(index);
    }

    @Override
    public long getLong(String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        if (cursor.isNull(index)) {
            wasNull = true;
            return 0L;
        }
        wasNull = false;
        return cursor.getLong(index);
    }

    @Override
    public int getInt(String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        if (cursor.isNull(index)) {
            wasNull = true;
            return 0;
        }
        wasNull = false;
        return cursor.getInt(index);
    }

    @Override
    public boolean wasNull() {
        return wasNull;
    }
}
