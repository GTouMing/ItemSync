package github.gtouming.itemsync.android;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import github.gtouming.itemsync.store.metadata.MetadataMigrations;
import github.gtouming.itemsync.store.metadata.sql.SqlMetadataStore;

import java.util.Objects;

public final class AndroidSqliteMetadataStore extends SqlMetadataStore {

    private final SQLiteOpenHelper helper;

    public AndroidSqliteMetadataStore(Context context, String databaseName) {
        this(createHelper(context, databaseName));
    }

    private AndroidSqliteMetadataStore(SQLiteOpenHelper helper) {
        super(new AndroidSqlSession(helper.getWritableDatabase()));
        this.helper = helper;
    }

    @Override
    public void close() {
        helper.close();
    }

    private static SQLiteOpenHelper createHelper(Context context, String databaseName) {
        Objects.requireNonNull(context, "context");
        return new SQLiteOpenHelper(context, databaseName, null, MetadataMigrations.latestVersion()) {
            @Override
            public void onCreate(SQLiteDatabase database) {
                applyMigrations(database, 0);
            }

            @Override
            public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
                applyMigrations(database, oldVersion);
            }
        };
    }

    private static void applyMigrations(SQLiteDatabase database, int fromVersion) {
        database.execSQL("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        for (MetadataMigrations.Migration migration : MetadataMigrations.after(fromVersion)) {
            for (String sql : migration.statements()) {
                database.execSQL(sql);
            }
            database.execSQL("DELETE FROM schema_version");
            database.execSQL("INSERT INTO schema_version(version) VALUES (" + migration.version() + ")");
        }
    }
}
