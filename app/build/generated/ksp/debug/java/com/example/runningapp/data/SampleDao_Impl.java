package com.example.runningapp.data;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class SampleDao_Impl implements SampleDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<HrSample> __insertionAdapterOfHrSample;

  public SampleDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfHrSample = new EntityInsertionAdapter<HrSample>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `hr_samples` (`id`,`sessionId`,`elapsedSeconds`,`rawBpm`,`smoothedBpm`,`connectionState`) VALUES (nullif(?, 0),?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final HrSample entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getSessionId());
        statement.bindLong(3, entity.getElapsedSeconds());
        statement.bindLong(4, entity.getRawBpm());
        statement.bindLong(5, entity.getSmoothedBpm());
        statement.bindString(6, entity.getConnectionState());
      }
    };
  }

  @Override
  public Object insertSample(final HrSample sample, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfHrSample.insert(sample);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<HrSample>> getSamplesForSession(final long sessionId) {
    final String _sql = "SELECT * FROM hr_samples WHERE sessionId = ? ORDER BY elapsedSeconds ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, sessionId);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"hr_samples"}, new Callable<List<HrSample>>() {
      @Override
      @NonNull
      public List<HrSample> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
          final int _cursorIndexOfElapsedSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "elapsedSeconds");
          final int _cursorIndexOfRawBpm = CursorUtil.getColumnIndexOrThrow(_cursor, "rawBpm");
          final int _cursorIndexOfSmoothedBpm = CursorUtil.getColumnIndexOrThrow(_cursor, "smoothedBpm");
          final int _cursorIndexOfConnectionState = CursorUtil.getColumnIndexOrThrow(_cursor, "connectionState");
          final List<HrSample> _result = new ArrayList<HrSample>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final HrSample _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpSessionId;
            _tmpSessionId = _cursor.getLong(_cursorIndexOfSessionId);
            final long _tmpElapsedSeconds;
            _tmpElapsedSeconds = _cursor.getLong(_cursorIndexOfElapsedSeconds);
            final int _tmpRawBpm;
            _tmpRawBpm = _cursor.getInt(_cursorIndexOfRawBpm);
            final int _tmpSmoothedBpm;
            _tmpSmoothedBpm = _cursor.getInt(_cursorIndexOfSmoothedBpm);
            final String _tmpConnectionState;
            _tmpConnectionState = _cursor.getString(_cursorIndexOfConnectionState);
            _item = new HrSample(_tmpId,_tmpSessionId,_tmpElapsedSeconds,_tmpRawBpm,_tmpSmoothedBpm,_tmpConnectionState);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
