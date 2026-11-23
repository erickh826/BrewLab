package com.example.data

import com.example.domain.model.AlgorithmConfig
import com.example.domain.model.BrewEvent
import com.example.domain.model.BrewEventType
import com.example.domain.model.BrewSession
import com.example.domain.model.Recipe
import com.example.domain.model.RecipeStep
import com.example.domain.model.SampleQuality
import com.example.domain.model.ScaleSample
import com.example.domain.model.SourceType
import com.example.domain.model.StepType
import com.example.domain.model.TimeBasis
import kotlin.math.round
import kotlin.random.Random

object SeedData {

  const val DEMO_RECIPE_ID = "demo-v60-three-pour"

  fun defaultDemoRecipe(): Recipe = Recipe(
    id = DEMO_RECIPE_ID,
    name = "V60 三段注水示範配方",
    version = 1,
    equipment = "Hario V60 02 濾杯 + 玻璃分享壺",
    coffeeDoseG = 20.0,
    totalTargetWaterG = 300.0,
    beanNotes = "水洗中淺焙（示範欄位，可自行修改）",
    roastNotes = "中淺焙",
    grinderNotes = "手沖中細研磨（僅記錄用途）",
    isDemoTemplate = true,
    updatedAtEpochMs = 1_760_000_000_000L,
    steps = listOf(
      RecipeStep(
        id = "step-1",
        type = StepType.POUR,
        label = "第一段悶蒸注水",
        startSec = 0,
        durationSec = 15,
        addedWaterG = 60.0,
        waterToleranceG = 4.0,
        rateToleranceGPerSec = 1.2,
      ),
      RecipeStep(
        id = "step-2",
        type = StepType.REST,
        label = "悶蒸靜置",
        startSec = 15,
        durationSec = 30,
        addedWaterG = 0.0,
        waterToleranceG = 2.0,
        rateToleranceGPerSec = 0.5,
      ),
      RecipeStep(
        id = "step-3",
        type = StepType.POUR,
        label = "第二段主注水",
        startSec = 45,
        durationSec = 30,
        addedWaterG = 120.0,
        waterToleranceG = 6.0,
        rateToleranceGPerSec = 1.5,
      ),
      RecipeStep(
        id = "step-4",
        type = StepType.REST,
        label = "段間靜置",
        startSec = 75,
        durationSec = 30,
        addedWaterG = 0.0,
        waterToleranceG = 2.0,
        rateToleranceGPerSec = 0.5,
      ),
      RecipeStep(
        id = "step-5",
        type = StepType.POUR,
        label = "第三段尾段注水",
        startSec = 105,
        durationSec = 30,
        addedWaterG = 120.0,
        waterToleranceG = 6.0,
        rateToleranceGPerSec = 1.5,
      ),
      RecipeStep(
        id = "step-6",
        type = StepType.REST,
        label = "滴濾完成靜置",
        startSec = 135,
        durationSec = 45,
        addedWaterG = 0.0,
        waterToleranceG = 2.0,
        rateToleranceGPerSec = 0.5,
      ),
    ),
  )

  fun seededDemoSessions(): List<Triple<BrewSession, List<ScaleSample>, List<BrewEvent>>> {
    val recipe = defaultDemoRecipe()
    val snapshot = recipe.toSnapshot(capturedAtEpochMs = 1_760_100_000_000L)
    return listOf(
      buildCompleteDemoSession(snapshot),
      buildInterruptedDemoSession(snapshot),
    )
  }

  private fun buildCompleteDemoSession(
    snapshot: com.example.domain.model.RecipeSnapshot,
  ): Triple<BrewSession, List<ScaleSample>, List<BrewEvent>> {
    val sessionId = "seed-demo-session-complete"
    val startedAt = 1_760_120_000_000L
    val durationMs = 180_000L
    val baseMonotonic = 500_000L
    val tareBaseline = 0.0
    val rng = Random(101)

    val samples = mutableListOf<ScaleSample>()
    var elapsedMs = 0L
    var seq = 0L
    var cumulativeWater = 0.0

    var dtMs = 0L
    while (elapsedMs <= durationMs) {
      val sec = elapsedMs / 1000.0
      val rate = when {
        sec in 0.5..15.5 -> 4.1   // ~61.5g
        sec in 45.0..75.5 -> 4.05 // ~123.5g
        sec in 105.0..135.5 -> 4.05 // ~123.5g (slightly above 120g target -> +8g overall)
        else -> 0.0
      }
      cumulativeWater += rate * (dtMs / 1000.0)
      val noise = (rng.nextDouble() - rng.nextDouble()) * 0.08
      val quantised = round((cumulativeWater + noise) * 10.0) / 10.0
      seq += 1L

      samples += ScaleSample(
        id = "$sessionId-s-$seq",
        sessionId = sessionId,
        sourceType = SourceType.DEMO,
        deviceId = "demo-scale-01",
        streamEpochId = 1,
        rawWeightG = quantised,
        deviceTimestampMs = 10_000L + elapsedMs,
        sequenceNumber = seq,
        monotonicReceiveMs = baseMonotonic + elapsedMs,
        elapsedMs = elapsedMs,
        timeBasis = TimeBasis.DEVICE_MONOTONIC,
        qualityState = SampleQuality.VALID,
      )
      dtMs = rng.nextLong(92L, 110L)
      elapsedMs += dtMs
    }

    val events = listOf(
      BrewEvent("$sessionId-e1", sessionId, BrewEventType.POUR_START, 500L, baseMonotonic + 500L, ""),
      BrewEvent("$sessionId-e2", sessionId, BrewEventType.POUR_STOP, 15_500L, baseMonotonic + 15_500L, ""),
      BrewEvent("$sessionId-e3", sessionId, BrewEventType.POUR_START, 45_000L, baseMonotonic + 45_000L, ""),
      BrewEvent("$sessionId-e4", sessionId, BrewEventType.POUR_STOP, 75_500L, baseMonotonic + 75_500L, ""),
      BrewEvent("$sessionId-e5", sessionId, BrewEventType.POUR_START, 105_000L, baseMonotonic + 105_000L, ""),
      BrewEvent("$sessionId-e6", sessionId, BrewEventType.POUR_STOP, 135_500L, baseMonotonic + 135_500L, ""),
      BrewEvent("$sessionId-e7", sessionId, BrewEventType.NOTE, 165_000L, baseMonotonic + 165_000L, "收水速度穩定，僅作模擬練習參考。"),
    )

    val session = BrewSession(
      id = sessionId,
      startedAtEpochMs = startedAt,
      endedAtEpochMs = startedAt + durationMs,
      durationMs = durationMs,
      sourceType = SourceType.DEMO,
      deviceId = "demo-scale-01",
      deviceName = "模擬電子秤 (模擬數據)",
      timeBasis = TimeBasis.DEVICE_MONOTONIC,
      tareBaselineG = tareBaseline,
      notes = "【模擬範例】三段注水完整練習，第三段總注水量略高於目標約 8 g。",
      isSeededDemo = true,
      recipeSnapshot = snapshot,
      algorithmConfig = AlgorithmConfig(),
    )
    return Triple(session, samples, events)
  }

  private fun buildInterruptedDemoSession(
    snapshot: com.example.domain.model.RecipeSnapshot,
  ): Triple<BrewSession, List<ScaleSample>, List<BrewEvent>> {
    val sessionId = "seed-demo-session-gap"
    val startedAt = 1_760_080_000_000L
    val durationMs = 165_000L
    val baseMonotonic = 800_000L
    val rng = Random(202)

    val samples = mutableListOf<ScaleSample>()
    var elapsedMs = 0L
    var seq = 0L
    var cumulativeWater = 0.0
    var epoch = 1
    var warmUpRemaining = 0

    var dtMs = 0L
    while (elapsedMs <= durationMs) {
      // Simulated gap from 61,500ms to 64,800ms
      if (elapsedMs in 61_501L..64_799L) {
        if (epoch == 1) {
          epoch = 2
          warmUpRemaining = 4
        }
        // Physical pouring still continued slightly in simulation during disconnect
        cumulativeWater += 3.8 * (dtMs / 1000.0)
        dtMs = rng.nextLong(90L, 112L)
        elapsedMs += dtMs
        continue
      }

      val sec = elapsedMs / 1000.0
      val rate = when {
        sec in 1.0..15.0 -> 3.8
        sec in 46.0..74.0 -> 3.8
        sec in 105.0..133.0 -> 3.9
        else -> 0.0
      }
      cumulativeWater += rate * (dtMs / 1000.0)
      val noise = (rng.nextDouble() - rng.nextDouble()) * 0.1
      val quantised = round((cumulativeWater + noise) * 10.0) / 10.0
      seq += 1L

      val quality = if (warmUpRemaining > 0) {
        warmUpRemaining -= 1
        SampleQuality.WARMING_UP
      } else {
        SampleQuality.VALID
      }

      val deviceTs = if (epoch == 1) 10_000L + elapsedMs else (elapsedMs - 64_800L)

      samples += ScaleSample(
        id = "$sessionId-s-$seq",
        sessionId = sessionId,
        sourceType = SourceType.DEMO,
        deviceId = "demo-scale-01",
        streamEpochId = epoch,
        rawWeightG = quantised,
        deviceTimestampMs = deviceTs,
        sequenceNumber = seq,
        monotonicReceiveMs = baseMonotonic + elapsedMs,
        elapsedMs = elapsedMs,
        timeBasis = TimeBasis.DEVICE_MONOTONIC,
        qualityState = quality,
      )
      dtMs = rng.nextLong(90L, 112L)
      elapsedMs += dtMs
    }

    val events = listOf(
      BrewEvent("$sessionId-e1", sessionId, BrewEventType.POUR_START, 1_000L, baseMonotonic + 1_000L, ""),
      BrewEvent("$sessionId-e2", sessionId, BrewEventType.POUR_STOP, 15_000L, baseMonotonic + 15_000L, ""),
      BrewEvent("$sessionId-e3", sessionId, BrewEventType.POUR_START, 46_000L, baseMonotonic + 46_000L, ""),
      BrewEvent("$sessionId-e4", sessionId, BrewEventType.DISCONNECTED, 61_500L, baseMonotonic + 61_500L, "模擬斷線缺口"),
      BrewEvent("$sessionId-e5", sessionId, BrewEventType.RECONNECTED, 64_800L, baseMonotonic + 64_800L, "重連進入 Epoch 2"),
      BrewEvent("$sessionId-e6", sessionId, BrewEventType.POUR_STOP, 74_000L, baseMonotonic + 74_000L, ""),
      BrewEvent("$sessionId-e7", sessionId, BrewEventType.POUR_START, 105_000L, baseMonotonic + 105_000L, ""),
      BrewEvent("$sessionId-e8", sessionId, BrewEventType.TOUCH_EQUIPMENT, 118_000L, baseMonotonic + 118_000L, "輕觸濾杯調整"),
      BrewEvent("$sessionId-e9", sessionId, BrewEventType.POUR_STOP, 133_000L, baseMonotonic + 133_000L, ""),
    )

    val session = BrewSession(
      id = sessionId,
      startedAtEpochMs = startedAt,
      endedAtEpochMs = startedAt + durationMs,
      durationMs = durationMs,
      sourceType = SourceType.DEMO,
      deviceId = "demo-scale-01",
      deviceName = "模擬電子秤 (模擬數據)",
      timeBasis = TimeBasis.DEVICE_MONOTONIC,
      tareBaselineG = 0.0,
      notes = "【模擬範例】示範第 61–65 秒連線中斷及第 118 秒碰觸器具干擾對曲線與階段摘要的斷開處理。",
      isSeededDemo = true,
      recipeSnapshot = snapshot,
      algorithmConfig = AlgorithmConfig(),
    )
    return Triple(session, samples, events)
  }
}
