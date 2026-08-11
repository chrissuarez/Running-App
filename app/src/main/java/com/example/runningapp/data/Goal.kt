package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.runningapp.training.Goal
import com.example.runningapp.training.GoalMetric
import com.example.runningapp.training.GoalPeriod
import kotlinx.coroutines.flow.Flow

/**
 * A Goal as it is kept: the row behind [com.example.runningapp.training.Goal] (#82).
 *
 * A table of targets and nothing else. There is no row per week here and never will be: a Goal
 * recurs, so what is stored is the standing intent — 40 km a week — and where the runner is against
 * it is worked out from the Runs on read ([com.example.runningapp.training.goalProgressOf]). That is
 * what makes a goal renew at each period boundary with nothing written, and what keeps a deleted Run
 * or a corrected treadmill distance from leaving a stale total behind.
 *
 * No key into `sessions` in either direction, like the Route library: setting or clearing goals can
 * never cost a runner a Run, and deleting a Run can never take a goal with it.
 *
 * [period] and [metric] together are unique — one goal per pair, which is what makes "weekly
 * distance" a thing the runner edits rather than a thing they can accumulate three of.
 */
@Entity(
    tableName = "goals",
    indices = [Index(value = ["period", "metric"], unique = true)],
)
data class GoalRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** A [GoalPeriod] — stored as its own name, so a row still reads plainly in a backup. */
    val period: GoalPeriod,
    /** A [GoalMetric], stored the same way. */
    val metric: GoalMetric,
    /** In the metric's own unit: kilometres, hours, or Runs. */
    val target: Double,
    val createdAtMillis: Long,
) {
    fun toGoal(): Goal = Goal(id = id, period = period, metric = metric, target = target)
}

@Dao
interface GoalDao {

    /**
     * Every goal the runner has set, oldest first.
     *
     * The order they were set in, and not by period or metric: the enums are stored as text, so any
     * ordering the database could do would be alphabetical — "Monthly, Weekly, Annual" — which is an
     * order nobody means. Their own order is at least one they chose.
     */
    @Query("SELECT * FROM goals ORDER BY createdAtMillis ASC, id ASC")
    fun getAllGoalsFlow(): Flow<List<GoalRow>>

    /**
     * Sets a goal, or rewrites the one already standing for that period and metric.
     *
     * Replacing rather than a separate update, because they are the same act to the runner: a weekly
     * distance goal is one thing, and stating it again is editing it. The unique index is what makes
     * the two indistinguishable here.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setGoal(goal: GoalRow): Long

    @Query("DELETE FROM goals WHERE id = :goalId")
    suspend fun deleteGoal(goalId: Long)
}
