package com.example.domain.model

enum class StepType(val displayName: String) {
  POUR("注水"),
  REST("靜置"),
}

data class RecipeStep(
  val id: String,
  val type: StepType,
  val label: String,
  val startSec: Int,
  val durationSec: Int,
  val addedWaterG: Double,
  val waterToleranceG: Double = 5.0,
  val rateToleranceGPerSec: Double = 1.5,
) {
  val endSec: Int
    get() = startSec + durationSec

  val plannedAverageRateGPerSec: Double
    get() = if (durationSec > 0 && type == StepType.POUR) addedWaterG / durationSec else 0.0
}

data class Recipe(
  val id: String,
  val name: String,
  val version: Int,
  val equipment: String,
  val coffeeDoseG: Double,
  val totalTargetWaterG: Double,
  val beanNotes: String = "",
  val roastNotes: String = "",
  val grinderNotes: String = "",
  val isDemoTemplate: Boolean = false,
  val updatedAtEpochMs: Long = System.currentTimeMillis(),
  val steps: List<RecipeStep>,
) {
  val totalDurationSec: Int
    get() = steps.maxOfOrNull { it.endSec } ?: 0

  fun toSnapshot(capturedAtEpochMs: Long = System.currentTimeMillis()): RecipeSnapshot =
    RecipeSnapshot(
      recipeId = id,
      name = name,
      version = version,
      equipment = equipment,
      coffeeDoseG = coffeeDoseG,
      totalTargetWaterG = totalTargetWaterG,
      beanNotes = beanNotes,
      roastNotes = roastNotes,
      grinderNotes = grinderNotes,
      isDemoTemplate = isDemoTemplate,
      capturedAtEpochMs = capturedAtEpochMs,
      steps = steps,
    )
}

data class RecipeSnapshot(
  val recipeId: String,
  val name: String,
  val version: Int,
  val equipment: String,
  val coffeeDoseG: Double,
  val totalTargetWaterG: Double,
  val beanNotes: String,
  val roastNotes: String,
  val grinderNotes: String,
  val isDemoTemplate: Boolean,
  val capturedAtEpochMs: Long,
  val steps: List<RecipeStep>,
) {
  val totalDurationSec: Int
    get() = steps.maxOfOrNull { it.endSec } ?: 0
}

data class TargetCurvePoint(
  val elapsedMs: Long,
  val targetWaterG: Double,
  val targetRateGPerSec: Double,
)

data class RecipeValidationResult(
  val isValid: Boolean,
  val errors: List<String>,
)
