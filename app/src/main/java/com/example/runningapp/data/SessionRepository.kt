package com.example.runningapp.data

class SessionRepository(
    private val sessionDao: SessionDao
) {
    suspend fun deleteSession(sessionId: Long) {
        sessionDao.deleteSessionById(sessionId)
    }
}
