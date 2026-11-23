package com.example.domain.model

enum class SourceType(val displayName: String) {
  DEMO("模擬數據"),
  BLE_HARDWARE("實體藍牙電子秤"),
}

enum class TimeBasis(val displayName: String) {
  DEVICE_MONOTONIC("裝置單調時鐘"),
  DEVICE_SEQUENCE_ESTIMATED("裝置序號推算"),
  HOST_MONOTONIC("主機單調接收時鐘"),
}

enum class SampleQuality(val displayName: String) {
  VALID("有效"),
  WARMING_UP("預熱緩衝"),
  GAP("數據缺口"),
  INTERFERENCE("外部干擾"),
  DISCONNECTED("已斷線"),
}

enum class SmoothingWindow(val windowMs: Long, val label: String) {
  HALF_SECOND(500L, "0.5 秒"),
  ONE_SECOND(1000L, "1 秒"),
  TWO_SECONDS(2000L, "2 秒");

  companion object {
    fun fromMs(ms: Long): SmoothingWindow =
      entries.firstOrNull { it.windowMs == ms } ?: ONE_SECOND
  }
}

data class AlgorithmConfig(
  val version: String = "1.0.0",
  val smoothingWindowMs: Long = SmoothingWindow.ONE_SECOND.windowMs,
  val minSamplesForSlope: Int = 3,
  val minWindowSpanMs: Long = 250L,
  val maxGapMsBeforeBreak: Long = 450L,
  val warmUpSampleCount: Int = 4,
)

data class ScaleSample(
  val id: String,
  val sessionId: String,
  val sourceType: SourceType,
  val deviceId: String,
  val streamEpochId: Int,
  val rawWeightG: Double,
  val deviceTimestampMs: Long?,
  val sequenceNumber: Long?,
  val monotonicReceiveMs: Long,
  val elapsedMs: Long,
  val timeBasis: TimeBasis,
  val qualityState: SampleQuality,
)

enum class BrewEventType(
  val displayName: String,
  val breaksAnalysisInterval: Boolean,
) {
  TOUCH_EQUIPMENT("碰觸器具", true),
  MOVE_EQUIPMENT("移動器具", true),
  POUR_START("標記注水開始", false),
  POUR_STOP("標記注水停止", false),
  NOTE("備註", false),
  DISCONNECTED("連線中斷", true),
  RECONNECTED("重新連線", true),
  APP_BACKGROUNDED("程式離開前景", true),
  CLOCK_RESET("時鐘基準重置", true),
}

data class BrewEvent(
  val id: String,
  val sessionId: String,
  val type: BrewEventType,
  val elapsedMs: Long,
  val monotonicReceiveMs: Long,
  val note: String = "",
)

data class BrewSession(
  val id: String,
  val startedAtEpochMs: Long,
  val endedAtEpochMs: Long,
  val durationMs: Long,
  val sourceType: SourceType,
  val deviceId: String,
  val deviceName: String,
  val timeBasis: TimeBasis,
  val tareBaselineG: Double,
  val notes: String,
  val isSeededDemo: Boolean,
  val recipeSnapshot: RecipeSnapshot,
  val algorithmConfig: AlgorithmConfig,
)

data class PlottedPoint(
  val elapsedMs: Long,
  val monotonicReceiveMs: Long,
  val weightG: Double,
  val rateGPerSec: Double?,
  val streamEpochId: Int,
)

data class MissingInterval(
  val startElapsedMs: Long,
  val endElapsedMs: Long,
  val reason: String,
)

data class PourIntervalSummary(
  val index: Int,
  val startElapsedMs: Long,
  val endElapsedMs: Long,
  val durationSec: Double,
  val addedWaterG: Double?,
  val averageRateGPerSec: Double?,
  val isCompleteValidInterval: Boolean,
  val statusMessage: String,
)

data class StageComparison(
  val step: RecipeStep,
  val targetAddedWaterG: Double,
  val actualAddedWaterG: Double?,
  val waterDeltaG: Double?,
  val targetStageAverageRateGPerSec: Double,
  val actualStageAverageRateGPerSec: Double?,
  val isDataSupported: Boolean,
  val descriptiveFeedback: String,
)

data class DataQualitySummary(
  val totalSampleCount: Int,
  val validSampleCount: Int,
  val warmUpSampleCount: Int,
  val observedSampleRateHz: Double?,
  val streamEpochCount: Int,
  val missingIntervals: List<MissingInterval>,
  val interferenceEventCount: Int,
)

data class SessionAnalysisResult(
  val validWeightSegments: List<List<PlottedPoint>>,
  val validRateSegments: List<List<PlottedPoint>>,
  val finalValidWaterG: Double?,
  val pourIntervals: List<PourIntervalSummary>,
  val stageComparisons: List<StageComparison>,
  val qualitySummary: DataQualitySummary,
)

data class ConnectionQualityEvent(
  val id: String,
  val timestampEpochMs: Long,
  val title: String,
  val detail: String,
  val isWarning: Boolean,
)

data class FutureSensorSlot(
  val sensorName: String,
  val unit: String,
  val statusLabel: String = "尚未整合",
  val latestReading: String = "—",
)
