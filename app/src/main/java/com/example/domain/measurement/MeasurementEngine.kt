package com.example.domain.measurement

import com.example.domain.model.AlgorithmConfig
import com.example.domain.model.BrewEvent
import com.example.domain.model.BrewEventType
import com.example.domain.model.DataQualitySummary
import com.example.domain.model.MissingInterval
import com.example.domain.model.PlottedPoint
import com.example.domain.model.PourIntervalSummary
import com.example.domain.model.RecipeSnapshot
import com.example.domain.model.SampleQuality
import com.example.domain.model.ScaleSample
import com.example.domain.model.SessionAnalysisResult
import com.example.domain.model.StageComparison
import com.example.domain.model.StepType
import kotlin.math.abs

/**
 * Pure Kotlin least-squares linear regression rate estimator and gap-aware session analyzer.
 *
 * Assumptions & Rules:
 * - Uses actual sample timestamps (monotonicReceiveMs) rather than assuming fixed 100 ms intervals.
 * - Preserves signed raw weights without silent clamping.
 * - Never interpolates across gaps, never fills missing data with zero, and never calculates a
 *   slope across separate valid intervals, stream epochs, or interference events.
 */
object RateEstimator {

  /**
   * Computes ordinary least-squares slope (g/s) over [samples] belonging to a single contiguous
   * valid segment. Returns null if insufficient samples or time span exists.
   */
  fun leastSquaresSlopeGPerSec(
    samples: List<ScaleSample>,
    tareBaselineG: Double,
    config: AlgorithmConfig,
  ): Double? {
    if (samples.size < config.minSamplesForSlope) return null
    val firstTimeMs = samples.first().monotonicReceiveMs
    val lastTimeMs = samples.last().monotonicReceiveMs
    if (lastTimeMs - firstTimeMs < config.minWindowSpanMs) return null

    var sumX = 0.0
    var sumY = 0.0
    val n = samples.size.toDouble()

    val xs = DoubleArray(samples.size)
    val ys = DoubleArray(samples.size)
    for (i in samples.indices) {
      val x = (samples[i].monotonicReceiveMs - firstTimeMs) / 1000.0
      val y = samples[i].rawWeightG - tareBaselineG
      xs[i] = x
      ys[i] = y
      sumX += x
      sumY += y
    }

    val meanX = sumX / n
    val meanY = sumY / n
    var numerator = 0.0
    var denominator = 0.0
    for (i in samples.indices) {
      val dx = xs[i] - meanX
      val dy = ys[i] - meanY
      numerator += dx * dy
      denominator += dx * dx
    }
    if (denominator < 1e-9) return null
    return numerator / denominator
  }

  /**
   * Partitions [samples] into contiguous valid segments, breaking whenever:
   * - qualityState != VALID
   * - streamEpochId changes
   * - consecutive monotonic timestamps differ by more than [AlgorithmConfig.maxGapMsBeforeBreak]
   * - a breaking [BrewEvent] (disconnect, background, clock reset, touch/move interference) occurs between samples
   */
  fun splitIntoValidSegments(
    samples: List<ScaleSample>,
    events: List<BrewEvent>,
    config: AlgorithmConfig,
  ): List<List<ScaleSample>> {
    if (samples.isEmpty()) return emptyList()
    val sortedSamples = samples.sortedBy { it.monotonicReceiveMs }
    val breakingEvents = events
      .filter { it.type.breaksAnalysisInterval }
      .sortedBy { it.monotonicReceiveMs }

    val segments = mutableListOf<MutableList<ScaleSample>>()
    var current = mutableListOf<ScaleSample>()
    var prevValid: ScaleSample? = null

    for (sample in sortedSamples) {
      if (sample.qualityState != SampleQuality.VALID) {
        if (current.isNotEmpty()) {
          segments += current
          current = mutableListOf()
        }
        prevValid = null
        continue
      }

      val prev = prevValid
      val shouldBreak = if (prev == null) {
        false
      } else {
        val epochChanged = sample.streamEpochId != prev.streamEpochId
        val gapTooLarge = (sample.monotonicReceiveMs - prev.monotonicReceiveMs) > config.maxGapMsBeforeBreak
        val timeWentBackwards = sample.monotonicReceiveMs <= prev.monotonicReceiveMs
        val eventBetween = breakingEvents.any { ev ->
          ev.monotonicReceiveMs in prev.monotonicReceiveMs..sample.monotonicReceiveMs ||
            ev.elapsedMs in prev.elapsedMs..sample.elapsedMs
        }
        epochChanged || gapTooLarge || timeWentBackwards || eventBetween
      }

      if (shouldBreak && current.isNotEmpty()) {
        segments += current
        current = mutableListOf()
      }
      current += sample
      prevValid = sample
    }

    if (current.isNotEmpty()) {
      segments += current
    }
    return segments
  }

  /**
   * Computes live pouring rate (g/s) for the latest sample in the active session, or null ("—")
   * if disconnected, stale, warming up, or lacking sufficient valid window data.
   */
  fun computeLiveRateGPerSec(
    samples: List<ScaleSample>,
    events: List<BrewEvent>,
    tareBaselineG: Double,
    config: AlgorithmConfig,
    nowMonotonicMs: Long,
  ): Double? {
    if (samples.isEmpty()) return null
    val latest = samples.maxByOrNull { it.monotonicReceiveMs } ?: return null
    if (latest.qualityState != SampleQuality.VALID) return null
    if (nowMonotonicMs - latest.monotonicReceiveMs > config.maxGapMsBeforeBreak) return null

    val segments = splitIntoValidSegments(samples, events, config)
    val latestSegment = segments.lastOrNull() ?: return null
    if (latestSegment.last().id != latest.id) return null

    val cutoffMs = latest.monotonicReceiveMs - config.smoothingWindowMs
    val windowSamples = latestSegment.filter { it.monotonicReceiveMs >= cutoffMs }
    return leastSquaresSlopeGPerSec(windowSamples, tareBaselineG, config)
  }
}

object SessionAnalyzer {

  fun analyze(
    recipeSnapshot: RecipeSnapshot,
    samples: List<ScaleSample>,
    events: List<BrewEvent>,
    tareBaselineG: Double,
    config: AlgorithmConfig,
    sessionDurationMs: Long,
  ): SessionAnalysisResult {
    val sortedSamples = samples.sortedBy { it.monotonicReceiveMs }
    val sortedEvents = events.sortedBy { it.elapsedMs }
    val validSegments = RateEstimator.splitIntoValidSegments(sortedSamples, sortedEvents, config)

    val weightSegments = mutableListOf<List<PlottedPoint>>()
    val rateSegments = mutableListOf<List<PlottedPoint>>()

    for (segment in validSegments) {
      val plottedWeightPoints = mutableListOf<PlottedPoint>()
      var currentRateRun = mutableListOf<PlottedPoint>()

      for (idx in segment.indices) {
        val sample = segment[idx]
        val cutoffMs = sample.monotonicReceiveMs - config.smoothingWindowMs
        val windowSamples = segment.subList(0, idx + 1).filter { it.monotonicReceiveMs >= cutoffMs }
        val slope = RateEstimator.leastSquaresSlopeGPerSec(windowSamples, tareBaselineG, config)
        val point = PlottedPoint(
          elapsedMs = sample.elapsedMs,
          monotonicReceiveMs = sample.monotonicReceiveMs,
          weightG = sample.rawWeightG - tareBaselineG,
          rateGPerSec = slope,
          streamEpochId = sample.streamEpochId,
        )
        plottedWeightPoints += point
        if (slope != null) {
          currentRateRun += point
        } else if (currentRateRun.isNotEmpty()) {
          rateSegments += currentRateRun
          currentRateRun = mutableListOf()
        }
      }
      if (plottedWeightPoints.isNotEmpty()) {
        weightSegments += plottedWeightPoints
      }
      if (currentRateRun.isNotEmpty()) {
        rateSegments += currentRateRun
      }
    }

    val missingIntervals = computeMissingIntervals(sortedSamples, sortedEvents, config, sessionDurationMs)
    val pourIntervals = computeManualPourIntervals(validSegments, sortedEvents, tareBaselineG, sessionDurationMs)
    val stageComparisons = computeStageComparisons(recipeSnapshot, validSegments, tareBaselineG)

    val validCount = sortedSamples.count { it.qualityState == SampleQuality.VALID }
    val warmUpCount = sortedSamples.count { it.qualityState == SampleQuality.WARMING_UP }
    val epochCount = sortedSamples.map { it.streamEpochId }.distinct().size
    val observedHz = computeObservedRateHz(validSegments)
    val finalValidWaterG = validSegments.lastOrNull()?.lastOrNull()?.let { it.rawWeightG - tareBaselineG }

    return SessionAnalysisResult(
      validWeightSegments = weightSegments,
      validRateSegments = rateSegments,
      finalValidWaterG = finalValidWaterG,
      pourIntervals = pourIntervals,
      stageComparisons = stageComparisons,
      qualitySummary = DataQualitySummary(
        totalSampleCount = sortedSamples.size,
        validSampleCount = validCount,
        warmUpSampleCount = warmUpCount,
        observedSampleRateHz = observedHz,
        streamEpochCount = epochCount,
        missingIntervals = missingIntervals,
        interferenceEventCount = sortedEvents.count {
          it.type == BrewEventType.TOUCH_EQUIPMENT || it.type == BrewEventType.MOVE_EQUIPMENT
        },
      ),
    )
  }

  private fun computeObservedRateHz(validSegments: List<List<ScaleSample>>): Double? {
    val intervalsMs = mutableListOf<Long>()
    for (seg in validSegments) {
      for (i in 1 until seg.size) {
        val dt = seg[i].monotonicReceiveMs - seg[i - 1].monotonicReceiveMs
        if (dt > 0L) intervalsMs += dt
      }
    }
    if (intervalsMs.isEmpty()) return null
    val avgMs = intervalsMs.average()
    return if (avgMs > 0.0) 1000.0 / avgMs else null
  }

  private fun computeMissingIntervals(
    sortedSamples: List<ScaleSample>,
    sortedEvents: List<BrewEvent>,
    config: AlgorithmConfig,
    sessionDurationMs: Long,
  ): List<MissingInterval> {
    if (sortedSamples.isEmpty()) {
      return if (sessionDurationMs > 0L) {
        listOf(MissingInterval(0L, sessionDurationMs, "整段沖煮未接收到有效秤重數據"))
      } else {
        emptyList()
      }
    }

    val intervals = mutableListOf<MissingInterval>()
    val validSamples = sortedSamples.filter { it.qualityState == SampleQuality.VALID }
    if (validSamples.isEmpty()) {
      return listOf(MissingInterval(0L, sessionDurationMs, "沒有有效樣本（可能處於預熱或中斷狀態）"))
    }

    if (validSamples.first().elapsedMs > config.maxGapMsBeforeBreak) {
      intervals += MissingInterval(0L, validSamples.first().elapsedMs, "開始階段尚未取得穩定有效讀數")
    }

    for (i in 1 until validSamples.size) {
      val prev = validSamples[i - 1]
      val curr = validSamples[i]
      val dt = curr.monotonicReceiveMs - prev.monotonicReceiveMs
      val breakingEvent = sortedEvents.firstOrNull { ev ->
        ev.type.breaksAnalysisInterval &&
          (ev.monotonicReceiveMs in prev.monotonicReceiveMs..curr.monotonicReceiveMs ||
            ev.elapsedMs in prev.elapsedMs..curr.elapsedMs)
      }
      if (curr.streamEpochId != prev.streamEpochId || dt > config.maxGapMsBeforeBreak || breakingEvent != null) {
        val reason = when {
          breakingEvent != null -> breakingEvent.type.displayName
          curr.streamEpochId != prev.streamEpochId -> "裝置串流時段切換（Epoch ${prev.streamEpochId} → ${curr.streamEpochId}）"
          else -> "樣本間隔超過 ${config.maxGapMsBeforeBreak} ms"
        }
        intervals += MissingInterval(prev.elapsedMs, curr.elapsedMs, reason)
      }
    }

    if (sessionDurationMs - validSamples.last().elapsedMs > config.maxGapMsBeforeBreak) {
      intervals += MissingInterval(
        validSamples.last().elapsedMs,
        sessionDurationMs,
        "結束前未接收到有效數據",
      )
    }

    return intervals
  }

  private fun computeManualPourIntervals(
    validSegments: List<List<ScaleSample>>,
    sortedEvents: List<BrewEvent>,
    tareBaselineG: Double,
    sessionDurationMs: Long,
  ): List<PourIntervalSummary> {
    val starts = mutableListOf<BrewEvent>()
    val pairs = mutableListOf<Pair<Long, Long>>()
    var openStart: BrewEvent? = null

    for (ev in sortedEvents) {
      if (ev.type == BrewEventType.POUR_START && openStart == null) {
        openStart = ev
        starts += ev
      } else if (ev.type == BrewEventType.POUR_STOP && openStart != null) {
        pairs += openStart.elapsedMs to ev.elapsedMs
        openStart = null
      }
    }
    if (openStart != null && sessionDurationMs > openStart.elapsedMs) {
      pairs += openStart.elapsedMs to sessionDurationMs
    }

    return pairs.mapIndexed { idx, (startMs, endMs) ->
      val durationSec = ((endMs - startMs).coerceAtLeast(0L)) / 1000.0
      val matchingSegments = validSegments.mapNotNull { seg ->
        val inside = seg.filter { it.elapsedMs in startMs..endMs }
        if (inside.size >= 2) inside else null
      }
      if (matchingSegments.size == 1 && durationSec > 0.0) {
        val seg = matchingSegments.first()
        val coversStart = seg.first().elapsedMs - startMs <= 1200L
        val coversEnd = endMs - seg.last().elapsedMs <= 1200L
        if (coversStart && coversEnd) {
          val deltaWater = (seg.last().rawWeightG - tareBaselineG) - (seg.first().rawWeightG - tareBaselineG)
          val spanSec = (seg.last().monotonicReceiveMs - seg.first().monotonicReceiveMs) / 1000.0
          val avgRate = if (spanSec > 0.1) deltaWater / spanSec else null
          PourIntervalSummary(
            index = idx + 1,
            startElapsedMs = startMs,
            endElapsedMs = endMs,
            durationSec = durationSec,
            addedWaterG = deltaWater,
            averageRateGPerSec = avgRate,
            isCompleteValidInterval = true,
            statusMessage = "手動標記注水段（連續有效樣本）",
          )
        } else {
          PourIntervalSummary(
            index = idx + 1,
            startElapsedMs = startMs,
            endElapsedMs = endMs,
            durationSec = durationSec,
            addedWaterG = null,
            averageRateGPerSec = null,
            isCompleteValidInterval = false,
            statusMessage = "此標記區間邊界缺乏有效數據，無法完整計算。",
          )
        }
      } else {
        PourIntervalSummary(
          index = idx + 1,
          startElapsedMs = startMs,
          endElapsedMs = endMs,
          durationSec = durationSec,
          addedWaterG = null,
          averageRateGPerSec = null,
          isCompleteValidInterval = false,
          statusMessage = "此區間內含數據中斷或外部干擾，禁止跨段計算斜率與注水量。",
        )
      }
    }
  }

  private fun computeStageComparisons(
    recipeSnapshot: RecipeSnapshot,
    validSegments: List<List<ScaleSample>>,
    tareBaselineG: Double,
  ): List<StageComparison> {
    return recipeSnapshot.steps.sortedBy { it.startSec }.map { step ->
      val startMs = step.startSec * 1000L
      val endMs = step.endSec * 1000L
      val targetRate = step.plannedAverageRateGPerSec

      // Find a single unbroken valid segment covering both startMs and endMs within 1.5s tolerance
      val coveringSegment = validSegments.firstOrNull { seg ->
        seg.first().elapsedMs <= startMs + 1500L && seg.last().elapsedMs >= endMs - 1500L
      }

      if (coveringSegment == null) {
        StageComparison(
          step = step,
          targetAddedWaterG = step.addedWaterG,
          actualAddedWaterG = null,
          waterDeltaG = null,
          targetStageAverageRateGPerSec = targetRate,
          actualStageAverageRateGPerSec = null,
          isDataSupported = false,
          descriptiveFeedback = "本段數據中斷或樣本不足，實測值顯示為「—」。",
        )
      } else {
        val startSample = coveringSegment.minByOrNull { abs(it.elapsedMs - startMs) }
        val endSample = coveringSegment.minByOrNull { abs(it.elapsedMs - endMs) }
        if (startSample == null || endSample == null ||
          abs(startSample.elapsedMs - startMs) > 1500L ||
          abs(endSample.elapsedMs - endMs) > 1500L ||
          endSample.monotonicReceiveMs <= startSample.monotonicReceiveMs
        ) {
          StageComparison(
            step = step,
            targetAddedWaterG = step.addedWaterG,
            actualAddedWaterG = null,
            waterDeltaG = null,
            targetStageAverageRateGPerSec = targetRate,
            actualStageAverageRateGPerSec = null,
            isDataSupported = false,
            descriptiveFeedback = "本段起訖邊界缺少連續有效樣本，無法比較。",
          )
        } else {
          val actualAdded = (endSample.rawWeightG - tareBaselineG) - (startSample.rawWeightG - tareBaselineG)
          val delta = actualAdded - step.addedWaterG
          val stageAvgRate = actualAdded / step.durationSec.toDouble()
          val feedback = buildDescriptiveStageFeedback(step, delta)
          StageComparison(
            step = step,
            targetAddedWaterG = step.addedWaterG,
            actualAddedWaterG = actualAdded,
            waterDeltaG = delta,
            targetStageAverageRateGPerSec = targetRate,
            actualStageAverageRateGPerSec = stageAvgRate,
            isDataSupported = true,
            descriptiveFeedback = feedback,
          )
        }
      }
    }
  }

  private fun buildDescriptiveStageFeedback(
    step: com.example.domain.model.RecipeStep,
    deltaG: Double,
  ): String {
    val absDelta = abs(deltaG)
    return if (step.type == StepType.REST) {
      if (absDelta <= step.waterToleranceG) {
        "本段靜置重量變化在容許範圍內（變化 ${"%+.1f".format(deltaG)} g）。"
      } else if (deltaG > 0) {
        "本段靜置期間重量增加 ${"%.1f".format(absDelta)} g，高於靜置目標。"
      } else {
        "本段靜置期間重量減少 ${"%.1f".format(absDelta)} g。"
      }
    } else {
      when {
        absDelta <= step.waterToleranceG ->
          "本段注水量符合配方容許誤差範圍（差值 ${"%+.1f".format(deltaG)} g）。"
        deltaG > 0 ->
          "本段注水量高於配方目標 ${"%.1f".format(absDelta)} g。"
        else ->
          "本段注水量低於配方目標 ${"%.1f".format(absDelta)} g。"
      }
    }
  }
}
