package com.example.runningapp.ui

import com.example.runningapp.archive.ArchiveOutcome
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupModelsTest {

    private val london = ZoneId.of("Europe/London")

    @Test
    fun `a folder on the phone is named by its path`() {
        assertEquals(
            "Backups/Running App",
            backupFolderLabel("content://com.android.externalstorage.documents/tree/primary%3ABackups%2FRunning%20App")
        )
    }

    @Test
    fun `the root of phone storage is named, not left blank`() {
        assertEquals(
            "Phone storage",
            backupFolderLabel("content://com.android.externalstorage.documents/tree/primary%3A")
        )
    }

    @Test
    fun `a cloud folder's opaque id is not shown as if it were a path`() {
        assertEquals(
            "Chosen folder",
            backupFolderLabel("content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dencoded%3D")
        )
    }

    @Test
    fun `no folder picked says so`() {
        assertEquals("Not set", backupFolderLabel(null))
        assertEquals("Not set", backupFolderLabel(""))
    }

    @Test
    fun `never backed up is stated rather than left blank`() {
        assertEquals("No backup yet", lastBackupLine(null, london))
    }

    @Test
    fun `the last backup is given in local time`() {
        assertEquals("Last backup 30 Jul 2026, 07:12", lastBackupLine(1_785_391_920_000L, london))
    }

    @Test
    fun `a finished backup says so and a failure says why`() {
        assertNull(backupResultMessage(null))
        assertEquals(
            "Backed up",
            backupResultMessage(ArchiveOutcome.Archived("running-app-archive-2026-07-30-0712.zip", 1))
        )
        assertEquals(
            "Choose a backup folder first",
            backupResultMessage(ArchiveOutcome.NoFolderChosen)
        )
        assertEquals(
            "Backup failed: no space left",
            backupResultMessage(ArchiveOutcome.Failed("no space left"))
        )
    }
}
