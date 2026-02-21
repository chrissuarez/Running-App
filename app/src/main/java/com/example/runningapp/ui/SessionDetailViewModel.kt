package com.example.runningapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.data.SessionRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class SessionDetailViewModel(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _deleteCompleted = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val deleteCompleted = _deleteCompleted.asSharedFlow()

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            sessionRepository.deleteSession(sessionId)
            _deleteCompleted.emit(sessionId)
        }
    }
}

class SessionDetailViewModelFactory(
    private val sessionRepository: SessionRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SessionDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SessionDetailViewModel(sessionRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
