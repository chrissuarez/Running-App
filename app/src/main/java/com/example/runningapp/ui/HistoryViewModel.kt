package com.example.runningapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.data.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _selectedSessionIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedSessionIds = _selectedSessionIds.asStateFlow()

    fun toggleSelection(sessionId: Long) {
        _selectedSessionIds.value = if (_selectedSessionIds.value.contains(sessionId)) {
            _selectedSessionIds.value - sessionId
        } else {
            _selectedSessionIds.value + sessionId
        }
    }

    fun clearSelection() {
        _selectedSessionIds.value = emptySet()
    }

    fun deleteSelectedSessions() {
        val ids = _selectedSessionIds.value.toList()
        if (ids.isEmpty()) return

        viewModelScope.launch {
            sessionRepository.deleteSessions(ids)
            clearSelection()
        }
    }

    fun deleteSessions(sessionIds: List<Long>) {
        if (sessionIds.isEmpty()) return

        viewModelScope.launch {
            sessionRepository.deleteSessions(sessionIds)
            _selectedSessionIds.value = _selectedSessionIds.value - sessionIds.toSet()
        }
    }
}

class HistoryViewModelFactory(
    private val sessionRepository: SessionRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HistoryViewModel(sessionRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
