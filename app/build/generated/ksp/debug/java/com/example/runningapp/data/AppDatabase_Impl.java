package com.example.runningapp.data;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class AppDatabase_Impl extends AppDatabase {
  private volatile SessionDao _sessionDao;

  private volatile SampleDao _sampleDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(1) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, `avgBpm` INTEGER NOT NULL, `maxBpm` INTEGER NOT NULL, `timeInTargetZoneSeconds` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `hr_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `elapsedSeconds` INTEGER NOT NULL, `rawBpm` INTEGER NOT NULL, `smoothedBpm` INTEGER NOT NULL, `connectionState` TEXT NOT NULL, FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hr_samples_sessionId` ON `hr_samples` (`sessionId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ee9812f76707301dd842a1c631c42c77')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `sessions`");
        db.execSQL("DROP TABLE IF EXISTS `hr_samples`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        db.execSQL("PRAGMA foreign_keys = ON");
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsSessions = new HashMap<String, TableInfo.Column>(7);
        _columnsSessions.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSessions.put("startTime", new TableInfo.Column("startTime", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSessions.put("endTime", new TableInfo.Column("endTime", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSessions.put("durationSeconds", new TableInfo.Column("durationSeconds", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSessions.put("avgBpm", new TableInfo.Column("avgBpm", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSessions.put("maxBpm", new TableInfo.Column("maxBpm", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSessions.put("timeInTargetZoneSeconds", new TableInfo.Column("timeInTargetZoneSeconds", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysSessions = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesSessions = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoSessions = new TableInfo("sessions", _columnsSessions, _foreignKeysSessions, _indicesSessions);
        final TableInfo _existingSessions = TableInfo.read(db, "sessions");
        if (!_infoSessions.equals(_existingSessions)) {
          return new RoomOpenHelper.ValidationResult(false, "sessions(com.example.runningapp.data.RunnerSession).\n"
                  + " Expected:\n" + _infoSessions + "\n"
                  + " Found:\n" + _existingSessions);
        }
        final HashMap<String, TableInfo.Column> _columnsHrSamples = new HashMap<String, TableInfo.Column>(6);
        _columnsHrSamples.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsHrSamples.put("sessionId", new TableInfo.Column("sessionId", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsHrSamples.put("elapsedSeconds", new TableInfo.Column("elapsedSeconds", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsHrSamples.put("rawBpm", new TableInfo.Column("rawBpm", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsHrSamples.put("smoothedBpm", new TableInfo.Column("smoothedBpm", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsHrSamples.put("connectionState", new TableInfo.Column("connectionState", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysHrSamples = new HashSet<TableInfo.ForeignKey>(1);
        _foreignKeysHrSamples.add(new TableInfo.ForeignKey("sessions", "CASCADE", "NO ACTION", Arrays.asList("sessionId"), Arrays.asList("id")));
        final HashSet<TableInfo.Index> _indicesHrSamples = new HashSet<TableInfo.Index>(1);
        _indicesHrSamples.add(new TableInfo.Index("index_hr_samples_sessionId", false, Arrays.asList("sessionId"), Arrays.asList("ASC")));
        final TableInfo _infoHrSamples = new TableInfo("hr_samples", _columnsHrSamples, _foreignKeysHrSamples, _indicesHrSamples);
        final TableInfo _existingHrSamples = TableInfo.read(db, "hr_samples");
        if (!_infoHrSamples.equals(_existingHrSamples)) {
          return new RoomOpenHelper.ValidationResult(false, "hr_samples(com.example.runningapp.data.HrSample).\n"
                  + " Expected:\n" + _infoHrSamples + "\n"
                  + " Found:\n" + _existingHrSamples);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "ee9812f76707301dd842a1c631c42c77", "9c183e3fd53d13d111fe012a66cbc000");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "sessions","hr_samples");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    final boolean _supportsDeferForeignKeys = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP;
    try {
      if (!_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA foreign_keys = FALSE");
      }
      super.beginTransaction();
      if (_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA defer_foreign_keys = TRUE");
      }
      _db.execSQL("DELETE FROM `sessions`");
      _db.execSQL("DELETE FROM `hr_samples`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      if (!_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA foreign_keys = TRUE");
      }
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(SessionDao.class, SessionDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(SampleDao.class, SampleDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public SessionDao sessionDao() {
    if (_sessionDao != null) {
      return _sessionDao;
    } else {
      synchronized(this) {
        if(_sessionDao == null) {
          _sessionDao = new SessionDao_Impl(this);
        }
        return _sessionDao;
      }
    }
  }

  @Override
  public SampleDao sampleDao() {
    if (_sampleDao != null) {
      return _sampleDao;
    } else {
      synchronized(this) {
        if(_sampleDao == null) {
          _sampleDao = new SampleDao_Impl(this);
        }
        return _sampleDao;
      }
    }
  }
}
