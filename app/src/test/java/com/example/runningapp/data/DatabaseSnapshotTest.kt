package com.example.runningapp.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The snapshot, against a real SQLite database in the same write-ahead-log mode Room runs in.
 *
 * The condition being defended against is not hypothetical and is what #191 was about: a reader
 * holding a read snapshot open blocks the fold of the log back into the main file, and the old
 * copy-the-file snapshot then quietly shipped the database as of some earlier minute. Every test
 * here that matters holds exactly that reader open.
 */
class DatabaseSnapshotTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var live: File
    private lateinit var writer: Connection
    private val readers = mutableListOf<Connection>()

    @Before
    fun setUp() {
        live = File(folder.newFolder("databases"), "running_app_db")
        writer = connect(live)
        writer.exec("PRAGMA journal_mode=WAL")
        writer.exec("PRAGMA user_version=21")
        writer.exec("CREATE TABLE sessions (id INTEGER PRIMARY KEY, startTime INTEGER NOT NULL)")
        writer.exec("INSERT INTO sessions VALUES (1, 1000)")
    }

    @After
    fun tearDown() {
        readers.forEach { it.close() }
        writer.close()
    }

    @Test
    fun `a run committed with a reader holding the log open is in the snapshot`() {
        holdReadSnapshot()
        writer.exec("INSERT INTO sessions VALUES (2, 2000)")

        val snapshot = snapshot()

        assertEquals(listOf(1L, 2L), open(snapshot).runIds())
    }

    @Test
    fun `the snapshot carries the live database's version`() {
        // The restore reads user_version to decide whether a snapshot came from an app it can open,
        // so a snapshot that lost it would be refused as coming from an older app.
        holdReadSnapshot()
        writer.exec("PRAGMA user_version=22")

        val snapshot = snapshot()

        assertEquals(22, writer.userVersion()) // not the 0 an unversioned database would report
        assertEquals(writer.userVersion(), open(snapshot).userVersion())
    }

    @Test
    fun `the snapshot stands alone, with no log left beside it to be separated from`() {
        holdReadSnapshot()
        writer.exec("INSERT INTO sessions VALUES (2, 2000)")

        val snapshot = snapshot()

        assertFalse(File("${snapshot.path}-wal").exists())
        assertFalse(File("${snapshot.path}-shm").exists())
    }

    @Test
    fun `a snapshot left behind by an earlier backup is replaced, not refused`() {
        val destination = folder.newFile("snapshot.db").apply { writeBytes(ByteArray(64)) }
        writer.exec("INSERT INTO sessions VALUES (2, 2000)")

        DatabaseSnapshot.writeTo(destination) { writer.exec(it) }

        assertEquals(listOf(1L, 2L), open(destination).runIds())
    }

    @Test
    fun `a snapshot that cannot be taken throws and leaves no file behind`() {
        val destination = File(folder.newFolder(), "snapshot.db")
        val failure = IllegalStateException("database is locked")

        val thrown = runCatching {
            DatabaseSnapshot.writeTo(destination) { throw failure }
        }.exceptionOrNull()

        assertEquals(failure, thrown)
        assertFalse(destination.exists())
    }

    @Test
    fun `a statement that quietly writes nothing is a failure, not an empty snapshot`() {
        val destination = File(folder.newFolder(), "snapshot.db")

        val thrown = runCatching { DatabaseSnapshot.writeTo(destination) { } }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertFalse(destination.exists())
    }

    /**
     * Opens a read transaction and leaves it open — the state that used to block the fold. Holding
     * it through the snapshot is the whole point, so it is closed only in [tearDown].
     */
    private fun holdReadSnapshot() {
        val reader = connect(live)
        readers += reader
        reader.exec("BEGIN")
        reader.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM sessions").close() }
    }

    private fun snapshot(): File =
        File(folder.newFolder(), "snapshot.db").also { destination ->
            DatabaseSnapshot.writeTo(destination) { writer.exec(it) }
        }

    private fun connect(file: File): Connection =
        DriverManager.getConnection("jdbc:sqlite:${file.path}")

    private fun open(file: File): Connection = connect(file).also { readers += it }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.runIds(): List<Long> =
        createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM sessions ORDER BY id").use { rows ->
                buildList { while (rows.next()) add(rows.getLong(1)) }
            }
        }

    private fun Connection.userVersion(): Int =
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
}
