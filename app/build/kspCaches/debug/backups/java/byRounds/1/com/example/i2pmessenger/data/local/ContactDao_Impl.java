package com.example.i2pmessenger.data.local;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
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
public final class ContactDao_Impl implements ContactDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<ContactEntity> __insertionAdapterOfContactEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeleteContact;

  private final SharedSQLiteStatement __preparedStmtOfUpdateContact;

  public ContactDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfContactEntity = new EntityInsertionAdapter<ContactEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `contacts` (`id`,`name`,`address`,`isOnline`,`lastMessage`,`lastMessageTime`) VALUES (nullif(?, 0),?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final ContactEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getName());
        statement.bindString(3, entity.getAddress());
        final int _tmp = entity.isOnline() ? 1 : 0;
        statement.bindLong(4, _tmp);
        if (entity.getLastMessage() == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.getLastMessage());
        }
        if (entity.getLastMessageTime() == null) {
          statement.bindNull(6);
        } else {
          statement.bindLong(6, entity.getLastMessageTime());
        }
      }
    };
    this.__preparedStmtOfDeleteContact = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM contacts WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateContact = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE contacts SET name = ?, address = ? WHERE id = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insertContact(final ContactEntity contact,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfContactEntity.insert(contact);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteContact(final int id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteContact.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteContact.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateContact(final int id, final String name, final String address,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateContact.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, name);
        _argIndex = 2;
        _stmt.bindString(_argIndex, address);
        _argIndex = 3;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateContact.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<ContactEntity>> getAllContacts() {
    final String _sql = "SELECT * FROM contacts ORDER BY name ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"contacts"}, new Callable<List<ContactEntity>>() {
      @Override
      @NonNull
      public List<ContactEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfAddress = CursorUtil.getColumnIndexOrThrow(_cursor, "address");
          final int _cursorIndexOfIsOnline = CursorUtil.getColumnIndexOrThrow(_cursor, "isOnline");
          final int _cursorIndexOfLastMessage = CursorUtil.getColumnIndexOrThrow(_cursor, "lastMessage");
          final int _cursorIndexOfLastMessageTime = CursorUtil.getColumnIndexOrThrow(_cursor, "lastMessageTime");
          final List<ContactEntity> _result = new ArrayList<ContactEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final ContactEntity _item;
            final int _tmpId;
            _tmpId = _cursor.getInt(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpAddress;
            _tmpAddress = _cursor.getString(_cursorIndexOfAddress);
            final boolean _tmpIsOnline;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsOnline);
            _tmpIsOnline = _tmp != 0;
            final String _tmpLastMessage;
            if (_cursor.isNull(_cursorIndexOfLastMessage)) {
              _tmpLastMessage = null;
            } else {
              _tmpLastMessage = _cursor.getString(_cursorIndexOfLastMessage);
            }
            final Long _tmpLastMessageTime;
            if (_cursor.isNull(_cursorIndexOfLastMessageTime)) {
              _tmpLastMessageTime = null;
            } else {
              _tmpLastMessageTime = _cursor.getLong(_cursorIndexOfLastMessageTime);
            }
            _item = new ContactEntity(_tmpId,_tmpName,_tmpAddress,_tmpIsOnline,_tmpLastMessage,_tmpLastMessageTime);
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
  public Object getContactByAddress(final String address,
      final Continuation<? super ContactEntity> $completion) {
    final String _sql = "SELECT * FROM contacts WHERE address = ? LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, address);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<ContactEntity>() {
      @Override
      @Nullable
      public ContactEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfAddress = CursorUtil.getColumnIndexOrThrow(_cursor, "address");
          final int _cursorIndexOfIsOnline = CursorUtil.getColumnIndexOrThrow(_cursor, "isOnline");
          final int _cursorIndexOfLastMessage = CursorUtil.getColumnIndexOrThrow(_cursor, "lastMessage");
          final int _cursorIndexOfLastMessageTime = CursorUtil.getColumnIndexOrThrow(_cursor, "lastMessageTime");
          final ContactEntity _result;
          if (_cursor.moveToFirst()) {
            final int _tmpId;
            _tmpId = _cursor.getInt(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpAddress;
            _tmpAddress = _cursor.getString(_cursorIndexOfAddress);
            final boolean _tmpIsOnline;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsOnline);
            _tmpIsOnline = _tmp != 0;
            final String _tmpLastMessage;
            if (_cursor.isNull(_cursorIndexOfLastMessage)) {
              _tmpLastMessage = null;
            } else {
              _tmpLastMessage = _cursor.getString(_cursorIndexOfLastMessage);
            }
            final Long _tmpLastMessageTime;
            if (_cursor.isNull(_cursorIndexOfLastMessageTime)) {
              _tmpLastMessageTime = null;
            } else {
              _tmpLastMessageTime = _cursor.getLong(_cursorIndexOfLastMessageTime);
            }
            _result = new ContactEntity(_tmpId,_tmpName,_tmpAddress,_tmpIsOnline,_tmpLastMessage,_tmpLastMessageTime);
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
