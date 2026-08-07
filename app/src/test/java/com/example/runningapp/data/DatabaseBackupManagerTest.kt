package com.example.runningapp.data

import android.content.ContentResolver
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * What a failed snapshot costs (#191).
 *
 * The Downloads backup ends by retiring every copy but the one it just wrote, so the order it does
 * things in is the difference between a failure costing nothing and a failure costing the runner
 * their last restorable backup.
 */
class DatabaseBackupManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a snapshot that cannot be taken publishes nothing and touches no existing backup`() {
        val resolver = mock<ContentResolver>()
        val cacheDir = folder.newFolder("cache")
        val liveDatabase = File(folder.newFolder("databases"), DatabaseBackupManager.DATABASE_NAME)
            .apply { writeBytes(ByteArray(4096)) }
        val sqlite = mock<SupportSQLiteDatabase>()
        whenever(sqlite.execSQL(any())).thenThrow(IllegalStateException("database is locked"))
        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(context.cacheDir).thenReturn(cacheDir)
        whenever(context.getDatabasePath(DatabaseBackupManager.DATABASE_NAME))
            .thenReturn(liveDatabase)

        // Swallowed, not thrown: losing a backup must never crash a run.
        DatabaseBackupManager.backup(context, databaseWhose(sqlite))

        // The attempt really did get as far as trying to take a snapshot, and failed there.
        verify(sqlite).execSQL(any())
        // Nothing was asked of MediaStore at all — so nothing was inserted, and crucially the sweep
        // that retires superseded copies never ran. Whatever backup was in the folder before this
        // attempt is still there, and is still the newest restorable one.
        verifyNoInteractions(resolver)
        // And no half-made snapshot is left in the cache for the next attempt to trip over.
        assertEquals(emptyList<File>(), cacheDir.listFiles().orEmpty().toList())
    }

    private fun databaseWhose(sqlite: SupportSQLiteDatabase): AppDatabase {
        val helper = mock<SupportSQLiteOpenHelper>()
        whenever(helper.writableDatabase).thenReturn(sqlite)
        val database = mock<AppDatabase>()
        whenever(database.openHelper).thenReturn(helper)
        return database
    }
}
