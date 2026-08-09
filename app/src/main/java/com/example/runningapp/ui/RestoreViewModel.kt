package com.example.runningapp.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.SettingsRepository
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.restore.CurrentHistory
import com.example.runningapp.restore.PendingRestore
import com.example.runningapp.restore.RestorePlan
import com.example.runningapp.restore.RestoreReader
import com.example.runningapp.restore.RestoreRefusal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the "Restore history" flow has got to. */
sealed interface RestoreUiState {
    /** Nothing in progress — the ordinary state of the Settings row. */
    data object Idle : RestoreUiState

    /** A file has been picked and is being copied and read. Can take a moment on a big history. */
    data object Reading : RestoreUiState

    /** Read, understood, and waiting for the runner to agree. Nothing has been replaced yet. */
    data class Confirming(val plan: RestorePlan) : RestoreUiState

    /** The file cannot be restored, and this is why. Nothing was touched. */
    data class Refused(val reason: RestoreRefusal) : RestoreUiState

    /** Agreed. The restore is being armed on disk. Not yet safe to restart. */
    data object Applying : RestoreUiState

    /**
     * Armed and on disk. Only now may the app close and reopen — a restart raced ahead of the
     * arming would take the process down with nothing marked, and the runner would come back to
     * the history they were just told had been replaced.
     */
    data object Restarting : RestoreUiState
}

/**
 * Drives "Restore history" (#86, #198).
 *
 * A ViewModel rather than work launched from the screen for the same reason as [BackupViewModel]:
 * reading a picked file copies a whole database across another app's content provider, and the
 * runner may leave Settings while it happens. It also has to survive the file picker, which is
 * another app's screen and can take this process down with it — so what has actually been picked
 * lives on disk in staging (see [RestoreReader]) rather than in a field here, and this class only
 * remembers what to *show*.
 */
class RestoreViewModel(
    private val appContext: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<RestoreUiState>(RestoreUiState.Idle)
    val state = _state.asStateFlow()

    /**
     * What the picker came back with — null if the runner backed out of it, which is not a failure
     * and says nothing on screen.
     */
    fun fileChosen(uri: Uri?) {
        if (uri == null) return
        if (_state.value is RestoreUiState.Reading) return
        _state.value = RestoreUiState.Reading
        viewModelScope.launch {
            // This phone's settings, for the trial open's v12 → v13 recompute to fall back to when
            // the picked file brings no settings of its own. Read here rather than inside the
            // staging so the settings read is a suspending one; it is a single preference either
            // way. Handed over whole rather than as a profile, so which of the two maxima a restore
            // bands on stays a decision made in one place (#267).
            val phoneSettings = settingsRepository.userSettingsFlow.first()
            val outcome = withContext(Dispatchers.IO) {
                RestoreReader.stage(
                    context = appContext,
                    uri = uri,
                    // Asked of the open database rather than held as a constant beside Room's, so
                    // the two cannot drift and start disagreeing about which backups are too new.
                    currentDatabaseVersion = database.openHelper.readableDatabase.version,
                    phoneSettings = phoneSettings,
                )
            }
            _state.value = when (outcome) {
                is RestoreReader.Outcome.Refused -> RestoreUiState.Refused(outcome.reason)
                is RestoreReader.Outcome.Staged -> RestoreUiState.Confirming(
                    RestorePlan(summary = outcome.summary, current = currentHistory()),
                )
            }
        }
    }

    /**
     * The runner has read what they are about to lose and said yes.
     *
     * Nothing is replaced here — not the database, and deliberately not the settings either. Both
     * wait for the relaunch, where [PendingRestore.applyIfArmed] puts the history in place first and
     * only then writes the settings that came in the same archive. Writing settings at this point
     * would look safe, because they live outside the open database, but it splits the restore: if
     * arming failed, or the move failed at the next launch, the runner would be left with their old
     * history reinterpreted against another phone's heart-rate profile and training plan. The two
     * halves are one act, so they happen at one moment.
     */
    fun confirm() {
        if (_state.value !is RestoreUiState.Confirming) return
        _state.value = RestoreUiState.Applying
        viewModelScope.launch {
            val armed = withContext(Dispatchers.IO) { PendingRestore.arm(appContext) }
            // Restarting is announced only once the marker is on disk. Announced any earlier, the
            // screen watching for it could take the process down mid-arm, and the runner would come
            // back to the history they had just been told was replaced.
            //
            // Nothing staged to arm means the pick was lost to this process being killed behind the
            // picker: say so rather than restart into an unchanged app.
            _state.value = if (armed) {
                RestoreUiState.Restarting
            } else {
                RestoreUiState.Refused(RestoreRefusal.UNREADABLE)
            }
        }
    }

    /** Backed out, or a refusal acknowledged. The staged copy goes with it — it can be large. */
    fun dismiss() {
        val current = _state.value
        if (current is RestoreUiState.Applying || current is RestoreUiState.Restarting) return
        _state.value = RestoreUiState.Idle
        // Everything staged belongs to the pick being dismissed and goes with it — except when the
        // refusal was that a restore is still unfinished. Nothing was staged for that one: what is
        // sitting there is the *previous* restore's settings, waiting for the relaunch, and the only
        // copy of them. Tidying up after this refusal would be tidying away the thing it is about.
        if (current is RestoreUiState.Refused &&
            current.reason == RestoreRefusal.A_RESTORE_IS_UNFINISHED
        ) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) { RestoreReader.clear(appContext) }
    }

    private suspend fun currentHistory(): CurrentHistory {
        val dao = database.sessionDao()
        return CurrentHistory(
            runCount = dao.countSessions(),
            newestRunStartedAtEpochMillis = dao.newestSessionStartTime(),
        )
    }
}

class RestoreViewModelFactory(
    private val appContext: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RestoreViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RestoreViewModel(appContext, database, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
