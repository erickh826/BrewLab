package com.example

import com.example.data.BrewExporter
import com.example.data.SeedData
import com.example.domain.measurement.RateEstimator
import com.example.domain.measurement.RecipeValidator
import com.example.domain.measurement.SessionAnalyzer
import com.example.domain.measurement.TargetCurveGenerator
import com.example.domain.model.AlgorithmConfig
import com.example.domain.model.BrewEvent
import com.example.domain.model.BrewEventType
import com.example.domain.model.BrewSession
import com.example.domain.model.RecipeStep
import com.example.domain.model.SampleQuality
import com.example.domain.model.ScaleSample
import com.example.domain.model.SourceType
import com.example.domain.model.StepType
import com.example.domain.model.TimeBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementLogicTest {

  private fun sample(
    idx: Int,
    monotonicMs: Long,
    elapsedMs: Long,
    weightG: Double,
    epoch: Int = 1,
    quality: SampleQuality = SampleQuality.VALID,
    deviceTsMs: Long? = monotonicMs,
    seq: Long? = idx.toLong(),
  ): ScaleSample = ScaleSample(
    id = "s-$idx",
    sessionId = "sess-1",
    sourceType = SourceType.DEMO,
    deviceId = "demo-scale-01",
    streamEpochId = epoch,
    rawWeightG = weightG,
    deviceTimestampMs = deviceTsMs,
    sequenceNumber = seq,
    monotonicReceiveMs = monotonicMs,
    elapsedMs = elapsedMs,
    timeBasis = TimeBasis.DEVICE_MONOTONIC,
    qualityState = quality,
  )

  @Test
  fun `constant rate estimation uses actual irregular timestamps`() {
    // Irregular intervals: 0ms, 70ms, 210ms, 340ms, 520ms, 790ms, 1000ms at exact 4.5 g/s
    val times = listOf(0L, 70L, 210L, 340L, 520L, 790L, 1000L)
    val samples = times.mapIndexed { idx, t ->
      sample(idx = idx, monotonicMs = 10_000L + t, elapsedMs = t, weightG = 4.5 * (t / 1000.0))
    }

    val rate = RateEstimator.leastSquaresSlopeGPerSec(
      samples = samples,
      tareBaselineG = 0.0,
      config = AlgorithmConfig(smoothingWindowMs = 1000L),
    )
    assertNotNull(rate)
    assertEquals(4.5, rate!!, 1e-6)
  }

  @Test
  fun `signed raw weights and negative slopes are preserved without clamping`() {
    val times = listOf(0L, 100L, 200L, 300L, 400L)
    val samples = times.mapIndexed { idx, t ->
      sample(idx = idx, monotonicMs = 1_000L + t, elapsedMs = t, weightG = -0.5 - 2.0 * (t / 1000.0))
    }
    val rate = RateEstimator.leastSquaresSlopeGPerSec(samples, 0.0, AlgorithmConfig())
    assertNotNull(rate)
    assertEquals(-2.0, rate!!, 1e-6)
  }

  @Test
  fun `disconnection gap and warm-up samples break analysis segments without interpolation`() {
    // Segment 1: 0..1000ms @ 4 g/s
    val seg1 = (0..10).map { i ->
      val t = i * 100L
      sample(i, 10_000L + t, t, 4.0 * (t / 1000.0), epoch = 1)
    }
    // Gap from 1000ms to 3200ms (2.2s disconnection)
    // Segment 2: 2 warm-up samples + valid samples in epoch 2 @ 5 g/s
    val warmUp = listOf(
      sample(11, 13_200L, 3_200L, 10.0, epoch = 2, quality = SampleQuality.WARMING_UP),
      sample(12, 13_300L, 3_300L, 10.5, epoch = 2, quality = SampleQuality.WARMING_UP),
    )
    val seg2 = (0..8).map { i ->
      val t = 3_400L + i * 100L
      sample(13 + i, 10_000L + t, t, 11.0 + 5.0 * (i * 0.1), epoch = 2)
    }

    val allSamples = seg1 + warmUp + seg2
    val events = listOf(
      BrewEvent("e1", "sess-1", BrewEventType.DISCONNECTED, 1_050L, 11_050L),
      BrewEvent("e2", "sess-1", BrewEventType.RECONNECTED, 3_200L, 13_200L),
    )

    val config = AlgorithmConfig(smoothingWindowMs = 1000L)
    val segments = RateEstimator.splitIntoValidSegments(allSamples, events, config)

    assertEquals(2, segments.size)
    assertEquals(11, segments[0].size)
    assertEquals(9, segments[1].size)

    // Live rate immediately after reconnect (during warm-up / insufficient span) must be null ("—")
    val rateRightAfterReconnect = RateEstimator.computeLiveRateGPerSec(
      samples = seg1 + warmUp,
      events = events,
      tareBaselineG = 0.0,
      config = config,
      nowMonotonicMs = 13_350L,
    )
    assertNull(rateRightAfterReconnect)

    // Live rate when stale (>450ms) must also be null ("—")
    val staleRate = RateEstimator.computeLiveRateGPerSec(
      samples = seg1,
      events = emptyList(),
      tareBaselineG = 0.0,
      config = config,
      nowMonotonicMs = 12_000L,
    )
    assertNull(staleRate)
  }

  @Test
  fun `touch interference splits segment so slope never crosses interference event`() {
    val samples = (0..12).map { i ->
      val t = i * 100L
      val weight = if (i == 6) 80.0 else i * 0.4 // huge spike on touch at t=600ms
      sample(i, 5_000L + t, t, weight)
    }
    val events = listOf(
      BrewEvent("ev-touch", "sess-1", BrewEventType.TOUCH_EQUIPMENT, 600L, 5_600L),
    )
    val segments = RateEstimator.splitIntoValidSegments(samples, events, AlgorithmConfig())
    assertTrue(segments.size >= 2)
  }

  @Test
  fun `recipe validator catches overlap and mismatched water total while accepting seeded recipe`() {
    val demo = SeedData.defaultDemoRecipe()
    assertTrue(RecipeValidator.validate(demo).isValid)

    val badTotal = demo.copy(totalTargetWaterG = 350.0)
    assertFalse(RecipeValidator.validate(badTotal).isValid)

    val overlapping = demo.copy(
      steps = listOf(
        RecipeStep("a", StepType.POUR, "A", 0, 20, 100.0),
        RecipeStep("b", StepType.POUR, "B", 15, 20, 200.0),
      ),
    )
    assertFalse(RecipeValidator.validate(overlapping).isValid)
  }

  @Test
  fun `editing recipe does not alter saved session snapshot or measured analysis`() {
    val originalRecipe = SeedData.defaultDemoRecipe()
    val snapshot = originalRecipe.toSnapshot(1_000L)
    val samples = (0..150).map { i ->
      val t = i * 100L
      val water = if (t <= 15_000L) 4.0 * (t / 1000.0) else 60.0
      sample(i, 1_000L + t, t, water)
    }

    val editedRecipe = originalRecipe.copy(
      version = 2,
      totalTargetWaterG = 400.0,
      steps = originalRecipe.steps.mapIndexed { idx, s ->
        if (idx == 0) s.copy(addedWaterG = 160.0) else s
      },
    )

    // Snapshot inside session stays locked at 300 g total / 60 g step 1
    assertEquals(300.0, snapshot.totalTargetWaterG, 0.001)
    assertEquals(60.0, snapshot.steps.first().addedWaterG, 0.001)
    assertEquals(400.0, editedRecipe.totalTargetWaterG, 0.001)

    val curve = TargetCurveGenerator.generateCurve(snapshot.steps)
    assertEquals(300.0, curve.last().targetWaterG, 0.001)

    val analysis = SessionAnalyzer.analyze(
      recipeSnapshot = snapshot,
      samples = samples,
      events = emptyList(),
      tareBaselineG = 0.0,
      config = AlgorithmConfig(),
      sessionDurationMs = 15_000L,
    )
    assertEquals(60.0, analysis.stageComparisons.first().targetAddedWaterG, 0.001)
  }

  @Test
  fun `csv and json exports include source, time_basis, and preserve null values`() {
    val snapshot = SeedData.defaultDemoRecipe().toSnapshot(1_000L)
    val session = BrewSession(
      id = "exp-sess",
      startedAtEpochMs = 1_760_000_000_000L,
      endedAtEpochMs = 1_760_000_010_000L,
      durationMs = 10_000L,
      sourceType = SourceType.DEMO,
      deviceId = "demo-scale-01",
      deviceName = "模擬電子秤",
      timeBasis = TimeBasis.HOST_MONOTONIC,
      tareBaselineG = 0.0,
      notes = "測試匯出",
      isSeededDemo = false,
      recipeSnapshot = snapshot,
      algorithmConfig = AlgorithmConfig(),
    )
    val samples = listOf(
      sample(1, 1000L, 0L, -0.1, deviceTsMs = null, seq = null).copy(timeBasis = TimeBasis.HOST_MONOTONIC),
      sample(2, 1100L, 100L, 4.2, deviceTsMs = 100L, seq = 2L).copy(timeBasis = TimeBasis.HOST_MONOTONIC),
    )
    val events = listOf(
      BrewEvent("ev1", "exp-sess", BrewEventType.POUR_START, 50L, 1050L, ""),
    )

    val rawCsv = BrewExporter.buildRawSamplesCsv(session, samples)
    assertTrue(rawCsv.contains("HOST_MONOTONIC"))
    assertTrue(rawCsv.contains(",-0.1,,,1000,0,HOST_MONOTONIC,VALID"))

    val eventsCsv = BrewExporter.buildEventsCsv(session, events)
    assertTrue(eventsCsv.contains("POUR_START"))

    val json = BrewExporter.buildSessionJson(session, samples, events)
    assertTrue(json.contains("\"timeBasis\": \"HOST_MONOTONIC\""))
    assertTrue(json.contains("\"deviceTimestampMs\": null"))
    assertTrue(json.contains("\"sequenceNumber\": null"))
  }

  @Test
  fun `seeded sessions demonstrate valid and interrupted analysis accurately`() {
    val seeded = SeedData.seededDemoSessions()
    assertEquals(2, seeded.size)

    val (completeSession, completeSamples, completeEvents) = seeded[0]
    val completeAnalysis = SessionAnalyzer.analyze(
      recipeSnapshot = completeSession.recipeSnapshot,
      samples = completeSamples,
      events = completeEvents,
      tareBaselineG = completeSession.tareBaselineG,
      config = completeSession.algorithmConfig,
      sessionDurationMs = completeSession.durationMs,
    )
    assertTrue(completeAnalysis.qualitySummary.missingIntervals.isEmpty())
    assertEquals(3, completeAnalysis.pourIntervals.size)
    assertTrue(completeAnalysis.pourIntervals.all { it.isCompleteValidInterval })

    val (gapSession, gapSamples, gapEvents) = seeded[1]
    val gapAnalysis = SessionAnalyzer.analyze(
      recipeSnapshot = gapSession.recipeSnapshot,
      samples = gapSamples,
      events = gapEvents,
      tareBaselineG = gapSession.tareBaselineG,
      config = gapSession.algorithmConfig,
      sessionDurationMs = gapSession.durationMs,
    )
    assertTrue(gapAnalysis.qualitySummary.missingIntervals.isNotEmpty())
    assertEquals(2, gapAnalysis.qualitySummary.streamEpochCount)
    assertTrue(gapAnalysis.validWeightSegments.size >= 3)
  }

  @Test
  fun `ble stub strictly reports unintegrated and never claims physical connection`() {
    val stub = com.example.device.BleScaleDataSourceStub()
    assertEquals(SourceType.BLE_HARDWARE, stub.sourceType)
    assertFalse(stub.connectionState.value.isConnected)
    assertEquals("尚未整合", stub.connectionState.value.label)
  }
}
