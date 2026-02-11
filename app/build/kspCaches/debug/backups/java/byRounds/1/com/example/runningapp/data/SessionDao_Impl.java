package com.example.runningapp.data;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Long;
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
public final class SessionDao_Impl implements SessionDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<RunnerSession> __insertionAdapterOfRunnerSession;

  private final EntityDeletionOrUpdateAdapter<RunnerSession> __updateAdapterOfRunnerSession;

  public SessionDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfRunnerSession = new EntityInsertionAdapter<RunnerSession>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `sessions` (`id`,`startTime`,`endTime`,`durationSeconds`,`avgBpm`,`maxBpm`,`timeInTargetZoneSeconds`) VALUES (nullif(?, 0),?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final RunnerSession entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getStartTime());
        statement.bindLong(3, entity.getEndTime());
        statement.bindLong(4, entity.getDurationSeconds());
        statement.bindLong(5, entity.getAvgBpm());
        statement.bindLong(6, entity.getMaxBpm());
        statement.bindLong(7, entity.getTimeInTargetZoneSeconds());
      }
    };
    this.__updateAdapterOfRunnerSession = new EntityDeletionOrUpdateAdapter<RunnerSession>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `sessions` SET `id` = ?,`startTime` = ?,`endTime` = ?,`durationSeconds` = ?,`avgBpm` = ?,`maxBpm` = ?,`timeInTargetZoneSeconds` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final RunnerSession entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getStartTime());
        statement.bindLong(3, entity.getEndTime());
        statement.bindLong(4, entity.getDurationSeconds());
        statement.bindLong(5, entity.getAvgBpm());
        statement.bindLong(6, entity.getMaxBpm());
        statement.bindLong(7, entity.getTimeInTargetZoneSeconds());
        statement.bindLong(8, entity.getId());
      }
    };
  }

  @Override
  public Object insertSession(final RunnerSession session,
      final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfRunnerSession.insertAndReturnId(session);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object updateSession(final RunnerSession session,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfRunnerSession.handle(session);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<RunnerSession>> getLast20Sessions() {
    final String _sql = "SELECT * FROM sessions ORDER BY startTime DESC LIMIT 20";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"sessions"}, new Callable<List<RunnerSession>>() {
      @Override
      @NonNull
      public List<RunnerSession> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfStartTime = CursorUtil.getColumnIndexOrThrow(_cursor, "startTime");
          final int _cursorIndexOfEndTime = CursorUtil.getColumnIndexOrThrow(_cursor, "endTime");
          final int _cursorIndexOfDurationSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSeconds");
          final int _cursorIndexOfAvgBpm = CursorUtil.getColumnIndexOrThrow(_cursor, "avgBpm");
          final int _cursorIndexOfMaxBpm = CursorUtil.getColumnIndexOrThrow(_cursor, "maxBpm");
          final int _cursorIndexOfTimeInTargetZoneSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "timeInTargetZoneSeconds");
          final List<RunnerSession> _result = new ArrayList<RunnerSession>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final RunnerSession _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpStartTime;
            _tmpStartTime = _cursor.getLong(_cursorIndexOfStartTime);
            final long _tmpEndTime;
            _tmpEndTime = _cursor.getLong(_cursorIndexOfEndTime);
            final long _tmpDurationSeconds;
            _tmpDurationSeconds = _cursor.getLong(_cursorIndexOfDurationSeconds);
            final int _tmpAvgBpm;
            _tmpAvgBpm = _cursor.getInt(_cursorIndexOfAvgBpm);
            final int _tmpMaxBpm;
            _tmpMaxBpm = _cursor.getInt(_cursorIndexOfMaxBpm);
            final long _tmpTimeInTargetZoneSeconds;
            _tmpTimeInTargetZoneSeconds = _cursor.getLong(_cursorIndexOfTimeInTargetZoneSeconds);
            _item = new RunnerSession(_tmpId,_tmpStartTime,_tmpEndTime,_tmpDurationSeconds,_tmpAvgBpm,_tmpMaxBpm,_tmpTimeInTargetZoneSeconds);
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

  @Override
  public Object getSessionById(final long sessionId,
      final Continuation<? super RunnerSession> $completion) {
    final String _sql = "SELECT * FROM sessions WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, sessionId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<RunnerSession>() {
      @Override
      @Nullable
      public RunnerSession call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfStartTime = CursorUtil.getColumnIndexOrThrow(_cursor, "startTime");
          final int _cursorIndexOfEndTime = CursorUtil.getColumnIndexOrThrow(_cursor, "endTime");
          final int _cursorIndexOfDurationSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSeconds");
          final int _cursorIndexOfAvgBpm = CursorUtil.getColumnIndexOrThrow(_cursor, "avgBpm");
          final int _cursorIndexOfMaxBpm = CursorUtil.getColumnIndexOrThrow(_cursor, "maxBpm");
          final int _cursorIndexOfTimeInTargetZoneSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "timeInTargetZoneSeconds");
          final RunnerSession _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpStartTime;
            _tmpStartTime = _cursor.getLong(_cursorIndexOfStartTime);
            final long _tmpEndTime;
            _tmpEndTime = _cursor.getLong(_cursorIndexOfEndTime);
            final long _tmpDurationSeconds;
            _tmpDurationSeconds = _cursor.getLong(_cursorIndexOfDurationSeconds);
            final int _tmpAvgBpm;
            _tmpAvgBpm = _cursor.getInt(_cursorIndexOfAvgBpm);
            final int _tmpMaxBpm;
            _tmpMaxBpm = _cursor.getInt(_cursorIndexOfMaxBpm);
            final long _tmpTimeInTargetZoneSeconds;
            _tmpTimeInTargetZoneSeconds = _cursor.getLong(_cursorIndexOfTimeInTargetZoneSeconds);
            _result = new RunnerSession(_tmpId,_tmpStartTime,_tmpEndTime,_tmpDurationSeconds,_tmpAvgBpm,_tmpMaxBpm,_tmpTimeInTargetZoneSeconds);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
