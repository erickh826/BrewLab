package com.example.domain.measurement

import com.example.domain.model.Recipe
import com.example.domain.model.RecipeSnapshot
import com.example.domain.model.RecipeStep
import com.example.domain.model.RecipeValidationResult
import com.example.domain.model.StepType
import com.example.domain.model.TargetCurvePoint
import kotlin.math.abs

object RecipeValidator {
  fun validate(recipe: Recipe): RecipeValidationResult {
    val errors = mutableListOf<String>()
    if (recipe.name.isBlank()) {
      errors += "請輸入配方名稱。"
    }
    if (recipe.equipment.isBlank()) {
      errors += "請輸入沖煮器具。"
    }
    if (recipe.coffeeDoseG <= 0.0) {
      errors += "咖啡粉量必須大於 0 g。"
    }
    if (recipe.totalTargetWaterG <= 0.0) {
      errors += "目標總注水量必須大於 0 g。"
    }
    if (recipe.steps.isEmpty()) {
      errors += "配方至少需要一個步驟。"
    }

    var previousEndSec = 0
    recipe.steps.forEachIndexed { index, step ->
      val stepNum = index + 1
      if (step.label.isBlank()) {
        errors += "第 $stepNum 步需要名稱。"
      }
      if (step.startSec < 0) {
        errors += "第 $stepNum 步的開始時間不可為負數。"
      }
      if (step.durationSec <= 0) {
        errors += "第 $stepNum 步的持續時間必須大於 0 秒。"
      }
      if (index > 0 && step.startSec < previousEndSec) {
        errors += "第 $stepNum 步（${step.startSec} 秒）與前一步驟重疊或未按時間順序排列。"
      }
      if (step.type == StepType.POUR && step.addedWaterG <= 0.0) {
        errors += "第 $stepNum 步為注水階段，注水量必須大於 0 g。"
      }
      if (step.type == StepType.REST && abs(step.addedWaterG) > 0.001) {
        errors += "第 $stepNum 步為靜置階段，注水量必須為 0 g。"
      }
      if (step.waterToleranceG < 0.0 || step.rateToleranceGPerSec < 0.0) {
        errors += "第 $stepNum 步的容許誤差不可為負數。"
      }
      previousEndSec = step.endSec
    }

    if (recipe.steps.isNotEmpty() && recipe.totalTargetWaterG > 0.0) {
      val stepSum = recipe.steps.sumOf { it.addedWaterG }
      if (abs(stepSum - recipe.totalTargetWaterG) > 0.1) {
        errors += "各步驟注水量總和（${"%.1f".format(stepSum)} g）須與目標總注水量（${"%.1f".format(recipe.totalTargetWaterG)} g）一致。"
      }
    }

    return RecipeValidationResult(isValid = errors.isEmpty(), errors = errors)
  }
}

object TargetCurveGenerator {
  fun generateCurve(steps: List<RecipeStep>): List<TargetCurvePoint> {
    if (steps.isEmpty()) return emptyList()
    val sorted = steps.sortedBy { it.startSec }
    val points = mutableListOf<TargetCurvePoint>()
    var cumulative = 0.0

    val firstStartMs = sorted.first().startSec * 1000L
    if (firstStartMs > 0L) {
      points += TargetCurvePoint(0L, 0.0, 0.0)
    }

    sorted.forEachIndexed { index, step ->
      val startMs = step.startSec * 1000L
      val endMs = step.endSec * 1000L
      if (index > 0) {
        val prevEndMs = sorted[index - 1].endSec * 1000L
        if (startMs > prevEndMs) {
          points += TargetCurvePoint(startMs, cumulative, 0.0)
        }
      }
      val stepRate = step.plannedAverageRateGPerSec
      points += TargetCurvePoint(startMs, cumulative, stepRate)
      if (step.type == StepType.POUR) {
        cumulative += step.addedWaterG
      }
      points += TargetCurvePoint(endMs, cumulative, stepRate)
    }
    return points
  }

  fun targetWaterAt(steps: List<RecipeStep>, elapsedMs: Long): Double {
    if (steps.isEmpty() || elapsedMs <= 0L) return 0.0
    var cumulative = 0.0
    for (step in steps.sortedBy { it.startSec }) {
      val startMs = step.startSec * 1000L
      val endMs = step.endSec * 1000L
      if (elapsedMs <= startMs) {
        return cumulative
      }
      if (elapsedMs < endMs) {
        return if (step.type == StepType.POUR && endMs > startMs) {
          val fraction = (elapsedMs - startMs).toDouble() / (endMs - startMs).toDouble()
          cumulative + step.addedWaterG * fraction
        } else {
          cumulative
        }
      }
      if (step.type == StepType.POUR) {
        cumulative += step.addedWaterG
      }
    }
    return cumulative
  }

  fun activeStepAt(snapshot: RecipeSnapshot, elapsedMs: Long): ActivePlannedStep? {
    val sorted = snapshot.steps.sortedBy { it.startSec }
    if (sorted.isEmpty()) return null
    var cumulativeStart = 0.0
    for ((idx, step) in sorted.withIndex()) {
      val startMs = step.startSec * 1000L
      val endMs = step.endSec * 1000L
      if (elapsedMs in startMs until endMs) {
        val remainingSec = ((endMs - elapsedMs) / 1000.0)
        return ActivePlannedStep(
          stepIndex = idx + 1,
          totalSteps = sorted.size,
          step = step,
          targetWaterAtStepEnd = cumulativeStart + step.addedWaterG,
          remainingSec = remainingSec,
          isCompleted = false,
        )
      }
      if (step.type == StepType.POUR) {
        cumulativeStart += step.addedWaterG
      }
    }
    val last = sorted.last()
    return if (elapsedMs >= last.endSec * 1000L) {
      ActivePlannedStep(
        stepIndex = sorted.size,
        totalSteps = sorted.size,
        step = last,
        targetWaterAtStepEnd = cumulativeStart,
        remainingSec = 0.0,
        isCompleted = true,
      )
    } else {
      ActivePlannedStep(
        stepIndex = 1,
        totalSteps = sorted.size,
        step = sorted.first(),
        targetWaterAtStepEnd = sorted.first().addedWaterG,
        remainingSec = (sorted.first().startSec * 1000L - elapsedMs) / 1000.0,
        isCompleted = false,
      )
    }
  }
}

data class ActivePlannedStep(
  val stepIndex: Int,
  val totalSteps: Int,
  val step: RecipeStep,
  val targetWaterAtStepEnd: Double,
  val remainingSec: Double,
  val isCompleted: Boolean,
)
