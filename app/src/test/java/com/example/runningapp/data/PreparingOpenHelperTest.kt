package com.example.runningapp.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * When the history a launch may have to restore actually gets put in place (#121).
 *
 * The restore has to finish before Room reads the file, and it must not happen on the main thread.
 * Both of those are one question, not two: the preparation belongs to the *opening* of the database,
 * not to the building of it, because opening is the first thing Room will only ever do on a
 * background thread.
 */
class PreparingOpenHelperTest {

    @Test
    fun `building the helper prepares nothing`() {
        val delegate = mock<SupportSQLiteOpenHelper>()
        val preparations = AtomicInteger(0)

        PreparingOpenHelper(delegate) { preparations.incrementAndGet() }

        // The whole point: constructing this is what happens on the main thread, and it must cost
        // nothing. Nothing was prepared, and the database underneath was not so much as touched.
        assertEquals(0, preparations.get())
        verifyNoInteractions(delegate)
    }

    @Test
    fun `the database is prepared before it is opened`() {
        val order = mutableListOf<String>()
        val delegate = mock<SupportSQLiteOpenHelper>()
        whenever(delegate.writableDatabase) doAnswer {
            order += "open"
            mock<SupportSQLiteDatabase>()
        }
        val helper = PreparingOpenHelper(delegate) { order += "prepare" }

        helper.writableDatabase

        assertEquals(listOf("prepare", "open"), order)
    }

    @Test
    fun `a readable open prepares too`() {
        val delegate = mock<SupportSQLiteOpenHelper>()
        whenever(delegate.readableDatabase).thenReturn(mock())
        val preparations = AtomicInteger(0)
        val helper = PreparingOpenHelper(delegate) { preparations.incrementAndGet() }

        helper.readableDatabase

        assertEquals(1, preparations.get())
    }

    @Test
    fun `history is only ever put in place once`() {
        val delegate = mock<SupportSQLiteOpenHelper>()
        whenever(delegate.writableDatabase).thenReturn(mock())
        whenever(delegate.readableDatabase).thenReturn(mock())
        val preparations = AtomicInteger(0)
        val helper = PreparingOpenHelper(delegate) { preparations.incrementAndGet() }

        helper.writableDatabase
        helper.readableDatabase
        helper.writableDatabase

        assertEquals(1, preparations.get())
    }

    @Test
    fun `a second opener waits rather than opening a half-restored database`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val preparing = AtomicInteger(0)
        val openedWhilePreparing = AtomicInteger(0)
        val delegate = mock<SupportSQLiteOpenHelper>()
        whenever(delegate.writableDatabase) doAnswer {
            if (preparing.get() > 0) openedWhilePreparing.incrementAndGet()
            mock<SupportSQLiteDatabase>()
        }
        val helper = PreparingOpenHelper(delegate) {
            preparing.incrementAndGet()
            started.countDown()
            release.await()
            preparing.decrementAndGet()
        }

        val slow = Thread { helper.writableDatabase }.apply { start() }
        started.await()
        val racer = Thread { helper.writableDatabase }.apply { start() }
        // Long enough for a thread that was going to sail past the preparation to have done so.
        Thread.sleep(200)
        release.countDown()
        slow.join(5_000)
        racer.join(5_000)

        assertEquals(0, openedWhilePreparing.get())
    }

    @Test
    fun `a preparation that fails is tried again at the next open`() {
        val delegate = mock<SupportSQLiteOpenHelper>()
        whenever(delegate.writableDatabase).thenReturn(mock())
        val preparations = AtomicInteger(0)
        val helper = PreparingOpenHelper(delegate) {
            // Fails once, then comes good — the phone that died half way through a restore.
            if (preparations.incrementAndGet() == 1) {
                throw IllegalStateException("the restore could not be applied")
            }
        }

        // It throws where the caller can see it — the restore is best-effort in its own right, and
        // deciding to swallow a failure is that code's business, not this wrapper's. Nothing was
        // opened, either: a database nobody could prepare is not one to hand out.
        var threw = false
        try {
            helper.writableDatabase
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        verify(delegate, never()).writableDatabase

        // And the failure is not written off. Both restores are built to be picked up again at the
        // next launch, so the next open is the next chance to finish what this one could not.
        helper.writableDatabase
        assertEquals(2, preparations.get())
    }

    @Test
    fun `everything else is the database underneath`() {
        val delegate = mock<SupportSQLiteOpenHelper>()
        whenever(delegate.databaseName).thenReturn("running_app_db")
        val helper = PreparingOpenHelper(delegate) { }

        assertEquals("running_app_db", helper.databaseName)
        helper.close()

        verify(delegate).close()
    }
}
