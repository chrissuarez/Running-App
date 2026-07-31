package com.example.runningapp.ui

import androidx.lifecycle.SavedStateHandle
import com.example.runningapp.SettingsRepository
import com.example.runningapp.archive.ArchiveEntry
import com.example.runningapp.archive.ArchiveFolder
import com.example.runningapp.archive.ArchiveJson
import com.example.runningapp.archive.Archiver
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Counts the archives written, which is the only question these tests ask of a folder. */
    private class CountingFolder : ArchiveFolder {
        val written = mutableListOf<String>()
        override suspend fun list(): List<String> = written
        override suspend fun write(fileName: String, contents: suspend (OutputStream) -> Unit) {
            written += fileName
            contents(OutputStream.nullOutputStream())
        }

        override suspend fun rename(fileName: String, newName: String) {
            written -= fileName
            written += newName
        }

        override suspend fun delete(fileName: String) {
            written -= fileName
        }
    }

    private fun archiver(folder: ArchiveFolder) = Archiver(
        folder = { folder },
        contents = { listOf(ArchiveEntry.ofText(ArchiveJson.FILE_NAME, "{}")) },
        onArchived = {},
        now = { 1_785_391_920_000L }
    )

    /**
     * The picker is another app's screen and this one can be killed while it is up. Android brings
     * the activity back and hands it the folder, so the intention has to come back with it — or the
     * runner's "Back up now" quietly becomes "set a folder".
     */
    @Test
    fun `a backup waiting on a folder survives the app being killed`() = runTest(dispatcher) {
        val folder = CountingFolder()
        val settings = mock<SettingsRepository>()
        val savedState = SavedStateHandle()

        // The tap that had nowhere to write, and then the process dies with the picker open.
        BackupViewModel(archiver(folder), settings, savedState).folderPickerOpened(thenBackUp = true)

        // Android rebuilds the activity, and the view model with it, from the state it kept.
        val rebuilt = BackupViewModel(archiver(folder), settings, savedState)
        rebuilt.folderChosen("content://com.android.externalstorage.documents/tree/primary%3ABackups")
        advanceUntilIdle()

        assertEquals(listOf("running-app-archive-2026-07-30-071200.zip"), folder.written)
    }

    /** Picking a folder from the row itself is not a request to back up, before or after a death. */
    @Test
    fun `choosing a folder on its own does not start a backup`() = runTest(dispatcher) {
        val folder = CountingFolder()
        val settings = mock<SettingsRepository>()
        val savedState = SavedStateHandle()

        BackupViewModel(archiver(folder), settings, savedState).folderPickerOpened(thenBackUp = false)

        val rebuilt = BackupViewModel(archiver(folder), settings, savedState)
        rebuilt.folderChosen("content://com.android.externalstorage.documents/tree/primary%3ABackups")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), folder.written)
    }
}
