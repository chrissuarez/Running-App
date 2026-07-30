package com.example.runningapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.SettingsRepository
import com.example.runningapp.archive.ArchiveOutcome
import com.example.runningapp.archive.Archiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the "Back up now" button (#85).
 *
 * A ViewModel rather than a coroutine launched from the screen because a full archive takes as long
 * as it takes — a file per run plus a copy of the database, written across a content provider — and
 * the runner may well leave Settings while it runs. Held here, the backup finishes anyway and its
 * result is still there to be read when they come back.
 */
class BackupViewModel(
    private val archiver: Archiver,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _backingUp = MutableStateFlow(false)
    val backingUp = _backingUp.asStateFlow()

    private val _lastOutcome = MutableStateFlow<ArchiveOutcome?>(null)
    val lastOutcome = _lastOutcome.asStateFlow()

    fun backUpNow() {
        // A second tap while one is running would write a second archive over the first's name from
        // a database read at a different moment. One backup at a time; the button says so too.
        if (_backingUp.value) return
        _backingUp.value = true
        _lastOutcome.value = null
        viewModelScope.launch {
            try {
                _lastOutcome.value = archiver.archiveNow()
            } finally {
                _backingUp.value = false
            }
        }
    }

    /**
     * Whether the folder picker now open was opened by a "Back up now" that had nowhere to write.
     *
     * Kept here rather than beside the launcher, because the picker is another app's screen: this
     * one can be recreated while it is up — a rotation is enough — and an intention remembered in
     * the composition would be gone by the time the folder came back, leaving the runner staring at
     * a button they had already pressed.
     */
    private var backUpOnceFolderChosen = false

    fun folderPickerOpened(thenBackUp: Boolean) {
        backUpOnceFolderChosen = thenBackUp
    }

    /**
     * What the folder picker came back with — null if the runner backed out of it.
     *
     * The persistable grant is taken by the Activity that received the result, before this is
     * called; the Uri stored here is only the address.
     *
     * A backup that was waiting on the answer carries on from here, in the same coroutine as the
     * write that names the folder. Started separately, it would read the folder before that write
     * had landed and report there being none.
     */
    fun folderChosen(treeUri: String?) {
        val thenBackUp = backUpOnceFolderChosen
        backUpOnceFolderChosen = false
        if (treeUri == null) return
        viewModelScope.launch {
            settingsRepository.setBackupFolderUri(treeUri)
            if (thenBackUp) backUpNow()
        }
    }

    /** The result has been shown; the section goes back to reporting only the last backup time. */
    fun resultShown() {
        _lastOutcome.value = null
    }
}

class BackupViewModelFactory(
    private val archiver: Archiver,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BackupViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BackupViewModel(archiver, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
