package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.runningapp.analysis.RecordType
import kotlinx.coroutines.flow.Flow

/**
 * A Best Effort a treadmill Run was told it holds: a record distance, and the time the console
 * showed for it (#282).
 *
 * A table rather than a column, which is the one place this parts company with a Stated Distance. A
 * Run has exactly one distance, so [RunnerSession.distanceKm] could carry a stated one with no
 * migration at all ([ADR 0008](docs/adr/0008-a-stated-distance-is-a-real-distance.md)). A console
 * shows lap times, so a Run can honestly report a 1 km *and* a 5 km — two claims about two stretches,
 * neither derived from the other — and "up to five, each naming its distance" does not fit in a
 * column ([ADR 0015](docs/adr/0015-a-stated-best-effort-is-read-off-a-console-not-off-an-average.md)).
 *
 * [sessionId] and [type] together are unique: **one statement per record distance per Run**, so
 * stating a time again is correcting the one that is there rather than a second claim about the same
 * stretch. Only the five fastest-\* [RecordType]s are ever stored — the two totals are the Run's own
 * numbers and are never stated this way — and only a treadmill Run has rows at all, which is the
 * whole of the provenance: an outdoor Run's efforts are measured, and no Run ever holds one of each
 * at the same record.
 *
 * Deleted with its Run, like every other recording of one.
 */
@Entity(
    tableName = "stated_best_efforts",
    foreignKeys = [
        ForeignKey(
            entity = RunnerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId", "type"], unique = true)]
)
data class StatedBestEffort(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** One of the five fastest-\* [RecordType]s, stored as its own name so a backup reads plainly. */
    val type: RecordType,
    /**
     * Whole seconds, because that is what a console shows. The record book ranks in [Double]
     * seconds and this is widened at the boundary; kept narrow here so a typed `25:00` is stored as
     * the 1500 it is rather than as something that has been through floating point.
     */
    val seconds: Int,
)

@Dao
interface StatedBestEffortDao {

    /** What one Run has been told it holds, for its own page. */
    @Query("SELECT * FROM stated_best_efforts WHERE sessionId = :sessionId")
    fun getForSessionFlow(sessionId: Long): Flow<List<StatedBestEffort>>

    /** The same, read once — for scoring, which is not watching anything. */
    @Query("SELECT * FROM stated_best_efforts WHERE sessionId = :sessionId")
    suspend fun getForSession(sessionId: Long): List<StatedBestEffort>

    /**
     * Every statement in history, asked once (#282).
     *
     * Read whole rather than a query per Run, because the caller is the rebuild that measures all of
     * history ([com.example.runningapp.data.SessionRepository]) and a round trip per Run there is
     * one per Run in the runner's life. At most five rows per treadmill Run, so the whole table is
     * smaller than a single Run's track.
     */
    @Query("SELECT * FROM stated_best_efforts")
    suspend fun getAll(): List<StatedBestEffort>

    /**
     * States a time, or corrects one already stated.
     *
     * Replacing rather than a separate update, because they are the same act to the runner: a Run's
     * 5 km is one thing, and stating it again is editing it. The unique index over (sessionId, type)
     * is what makes the two indistinguishable here. The row's id changes under a correction, which
     * costs nothing — no medal, no backup and no screen holds one.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun state(effort: StatedBestEffort)

    /** Takes a statement back, leaving the Run as one nobody stated that distance for. */
    @Query("DELETE FROM stated_best_efforts WHERE sessionId = :sessionId AND type = :type")
    suspend fun withdraw(sessionId: Long, type: RecordType)
}

/**
 * How much shorter than a record distance a Stated Distance may be and still be allowed to contain
 * a claim at it (#282).
 *
 * The resolution the runner types a distance at, and nothing more generous: the field takes
 * kilometres to two places, so the most a genuine half marathon can be under-reported by the format
 * itself is ten metres. Anything beyond that is a Run that really is too short to hold the claim.
 */
const val STATED_DISTANCE_ROUNDING_METERS: Double = 10.0

/**
 * Whether a Run of [statedDistanceKm] is long enough to contain a claim at this record distance
 * (#282).
 *
 * The one place the question is asked, because it is asked in three: the repository refuses a claim
 * the Run could not contain, the same repository withdraws claims a corrected distance has just made
 * impossible, and the screen offers only the distances that would survive both. Written out at each
 * site — once inverted — it is one polarity flip away from a record book that holds a claim no
 * screen would have accepted.
 *
 * A Run nobody stated a distance for contains everything: the two statements are independent, and
 * only a distance that is actually there can be too short.
 */
fun RecordType.fitsWithin(statedDistanceKm: Double): Boolean {
    val meters = statedDistanceKm * 1_000.0
    if (meters <= 0.0) return true
    return distanceMeters!! <= meters + STATED_DISTANCE_ROUNDING_METERS
}

/** What a Run claims, in the shape the record book ranks: seconds, by record. */
fun List<StatedBestEffort>.byType(): Map<RecordType, Double> =
    associate { it.type to it.seconds.toDouble() }
