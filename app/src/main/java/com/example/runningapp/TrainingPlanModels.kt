package com.example.runningapp

data class TrainingPlan(
    val id: String,
    val name: String,
    val description: String,
    val stages: List<PlanStage>
)

data class PlanStage(
    val id: String,
    val title: String,
    val description: String,
    val graduationRequirementText: String,
    val isLocked: Boolean = true,
    val workouts: List<WorkoutTemplate>
)

data class WorkoutTemplate(
    val id: String,
    val title: String,
    val targetZone: Int, // e.g., 2
    val runDurationSeconds: Int,
    val walkDurationSeconds: Int,
    val totalRepeats: Int
)

object TrainingPlanProvider {
    val plans = listOf(
        TrainingPlan(
            id = "5k_sub_25",
            name = "5K to Sub-25 Progressive Plan",
            description = "A progressive plan designed to take you from completing a 5K to breaking the 25-minute barrier through zone-based conditioning.",
            stages = listOf(
                PlanStage(
                    id = "base_builder",
                    title = "Stage 1: Base Builder",
                    description = "Focus on building aerobic capacity and consistency.",
                    graduationRequirementText = "Complete 4 weeks of consistent Zone 2 training.",
                    isLocked = false,
                    workouts = listOf(
                        WorkoutTemplate("w1_s1", "Intro Intervals", 2, 180, 60, 6),
                        WorkoutTemplate("w1_s2", "Aerobic Foundation", 2, 300, 60, 5),
                        WorkoutTemplate("w1_s3", "Endurance Walk-Run", 2, 600, 120, 3)
                    )
                ),
                PlanStage(
                    id = "sub_30_bridge",
                    title = "Stage 2: Sub-30 Bridge",
                    description = "Bridging the gap to sustained running at higher intensities.",
                    graduationRequirementText = "Successfully complete a 5K under 30 minutes.",
                    isLocked = true,
                    workouts = listOf(
                        WorkoutTemplate("w2_s1", "Pace Stabilization", 2, 600, 60, 4),
                        WorkoutTemplate("w2_s2", "The 30-Minute Run", 2, 1800, 0, 1)
                    )
                ),
                PlanStage(
                    id = "sub_25_peak",
                    title = "Stage 3: Sub-25 Peak",
                    description = "Fine-tuning threshold and speed for performance.",
                    graduationRequirementText = "Run a 5K in 24:59 or faster.",
                    isLocked = true,
                    workouts = listOf(
                        WorkoutTemplate("w3_s1", "Threshold Intervals", 4, 300, 120, 5),
                        WorkoutTemplate("w3_s2", "5K Peak Test", 4, 1500, 0, 1)
                    )
                )
            )
        )
    )

    fun getPlanById(id: String) = plans.find { it.id == id }
}
