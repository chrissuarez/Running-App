package com.example.runningapp.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class RunnerSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long = 0,
    val durationSeconds: Long = 0,
    val avgBpm: Int = 0,
    val maxBpm: Int = 0,
    val timeInTargetZoneSeconds: Long = 0
)

@Entity(
    tableName = "hr_samples",
    foreignKeys = [
        ForeignKey(
            entity = RunnerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class HrSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val elapsedSeconds: Long,
    val rawBpm: Int,
    val smoothedBpm: Int,
    val connectionState: String
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: RunnerSession): Long

    @Update
    suspend fun updateSession(session: RunnerSession)

    @Query("SELECT * FROM sessions ORDER BY startTime DESC LIMIT 20")
    fun getLast20Sessions(): Flow<List<RunnerSession>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): RunnerSession?
}

@Dao
interface SampleDao {
    @Insert
    suspend fun insertSample(sample: HrSample)

    @Query("SELECT * FROM hr_samples WHERE sessionId = :sessionId ORDER BY elapsedSeconds ASC")
    fun getSamplesForSession(sessionId: Long): Flow<List<HrSample>>
}

@Database(entities = [RunnerSession::class, HrSample::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sampleDao(): SampleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "running_app_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
