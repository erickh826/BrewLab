package com.example.device

import android.os.SystemClock
import com.example.domain.model.ConnectionQualityEvent
import com.example.domain.model.SampleQuality
import com.example.domain.model.ScaleSample
import com.example.domain.model.SourceType
import com.example.domain.model.TimeBasis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.round
import kotlin.random.Random

/**
 * Simulated precision coffee scale data source ("模擬數據").
 *
 * Emits ~10 Hz quantised (0.1 g) readings with real monotonic timestamps, configurable noise,
 * independent auto pour/rest simulation (never coupled to the user's recipe), optional manual
 * pouring-rate override, tare support, and simulated disconnection/reconnection with epoch bumps
 * and post-reconnect warm-up samples.
 */
class DemoScaleDataSource(
  private val warmUpSampleCount: Int = 4,
) : ScaleDataSource, DemoScaleControls {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var streamJob: Job? = null
  private var interruptionJob: Job? = null

  override val sourceType: SourceType = SourceType.DEMO

  private val _deviceInfo = MutableStateFlow(
    ScaleDeviceInfo(
      deviceId = "demo-scale-01",
      displayName = "模擬電子秤 (模擬數據)",
      sourceType = SourceType.DEMO,
      isSimulated = true,
      timeBasis = TimeBasis.DEVICE_MONOTONIC,
      detailLabel = "模擬數據 · 約 10 Hz · 0.1 g 解析度",
    )
  )
  override val deviceInfo: StateFlow<ScaleDeviceInfo> = _deviceInfo.asStateFlow()

  private val _connectionState = MutableStateFlow<ScaleConnectionState>(ScaleConnectionState.Disconnected)
  override val connectionState: StateFlow<ScaleConnectionState> = _connectionState.asStateFlow()

  private val _currentStreamEpochId = MutableStateFlow(1)
  override val currentStreamEpochId: StateFlow<Int> = _currentStreamEpochId.asStateFlow()

  private val _samples = MutableSharedFlow<ScaleSample>(extraBufferCapacity = 128)
  override val samples: Flow<ScaleSample> = _samples.asSharedFlow()

  private val _qualityEvents = MutableSharedFlow<ConnectionQualityEvent>(extraBufferCapacity = 64)
  override val qualityEvents: Flow<ConnectionQualityEvent> = _qualityEvents.asSharedFlow()

  override val supportedCommands: StateFlow<Set<ScaleCommand>> = MutableStateFlow(setOf(
    ScaleCommand.CONNECT,
    ScaleCommand.DISCONNECT,
    ScaleCommand.TARE,
    ScaleCommand.MANUAL_SIM_POUR_RATE,
    ScaleCommand.SIMULATE_INTERRUPTION,
    ScaleCommand.TOGGLE_AUTO_SIM,
  ))

  private val _demoConfig = MutableStateFlow(DemoSimConfig())
  override val demoConfig: StateFlow<DemoSimConfig> = _demoConfig.asStateFlow()

  // Internal physical simulation state (completely independent of recipes)
  private var simAssemblyBaseWeightG = 285.4
  private var tareOffsetG = 0.0
  private var addedWaterMassG = 0.0
  private var sequenceNum = 0L
  private var deviceClockBaseMs = 0L
  private var connectedAtMonotonicMs = 0L
  private var remainingWarmUpSamples = 0
  private val random = Random(42)

  override suspend fun connect() {
    if (_connectionState.value is ScaleConnectionState.Connected ||
      _connectionState.value is ScaleConnectionState.Connecting
    ) {
      return
    }
    _connectionState.value = ScaleConnectionState.Connecting
    emitQualityEvent("開始連接模擬電子秤", "初始化模擬數據串流（獨立於配方目標）", false)
    delay(250L)
    if (_connectionState.value != ScaleConnectionState.Connecting) return

    connectedAtMonotonicMs = SystemClock.elapsedRealtime()
    deviceClockBaseMs = 10_000L
    sequenceNum = 0L
    addedWaterMassG = 0.0
    tareOffsetG = 0.0
    _currentStreamEpochId.update { it + 1 }
    remainingWarmUpSamples = warmUpSampleCount
    _connectionState.value = ScaleConnectionState.Connected
    emitQualityEvent("模擬電子秤已連線", "模擬數據串流啟動 (Epoch ${_currentStreamEpochId.value})", false)
    startStreamingLoop()
  }

  override suspend fun disconnect() {
    interruptionJob?.cancel()
    interruptionJob = null
    streamJob?.cancel()
    streamJob = null
    _connectionState.value = ScaleConnectionState.Disconnected
    _demoConfig.update { it.copy(currentAutoPhaseLabel = "已中斷連線") }
    emitQualityEvent("模擬電子秤已斷線", "停止發送模擬數據樣本", true)
  }

  override suspend fun tare(): ScaleTareResult {
    if (!_connectionState.value.isConnected) return ScaleTareResult.DISCONNECTED
    tareOffsetG = simAssemblyBaseWeightG + addedWaterMassG
    emitQualityEvent("已執行模擬歸零 (Tare)", "將目前總重設為 0.0 g 參考點", false)
    return ScaleTareResult.SUCCESS
  }

  override fun setAutoPhaseEnabled(enabled: Boolean) {
    _demoConfig.update {
      it.copy(
        autoPhaseEnabled = enabled,
        currentAutoPhaseLabel = if (enabled) "自動模擬階段" else "手動注水速率控制",
      )
    }
  }

  override fun setManualPourRate(rateGPerSec: Double) {
    _demoConfig.update {
      it.copy(
        autoPhaseEnabled = false,
        manualPourRateGPerSec = rateGPerSec.coerceIn(0.0, 12.0),
        currentAutoPhaseLabel = "手動控制：${"%.1f".format(rateGPerSec.coerceIn(0.0, 12.0))} g/s",
      )
    }
  }

  override fun setNoiseAmplitude(noiseG: Double) {
    _demoConfig.update { it.copy(noiseAmplitudeG = noiseG.coerceIn(0.0, 0.5)) }
  }

  override fun simulateInterruption(durationMs: Long) {
    if (_connectionState.value !is ScaleConnectionState.Connected) return
    if (interruptionJob?.isActive == true) return
    interruptionJob = scope.launch {
      streamJob?.cancel()
      streamJob = null
      _connectionState.value = ScaleConnectionState.Interrupted("模擬訊號遺失")
      emitQualityEvent("模擬數據中斷", "停止發送樣本 ${durationMs / 1000.0} 秒，保留原有分析缺口", true)
      delay(durationMs)

      _connectionState.value = ScaleConnectionState.Reconnecting
      emitQualityEvent("模擬重新連線中", "建立新串流時段並啟動預熱樣本", true)
      delay(300L)

      _currentStreamEpochId.update { it + 1 }
      deviceClockBaseMs = 0L // Simulate device clock reset across reconnection
      remainingWarmUpSamples = warmUpSampleCount
      _connectionState.value = ScaleConnectionState.Connected
      emitQualityEvent(
        "模擬電子秤已重新連線",
        "進入 Epoch ${_currentStreamEpochId.value}（首 $warmUpSampleCount 個樣本標記為預熱緩衝）",
        false,
      )
      startStreamingLoop()
    }
  }

  private fun startStreamingLoop() {
    streamJob?.cancel()
    streamJob = scope.launch {
      var lastTickMs = SystemClock.elapsedRealtime()
      while (isActive && _connectionState.value is ScaleConnectionState.Connected) {
        // Variable ~10 Hz interval (88..114 ms) so rate estimator relies on real timestamps
        val stepDelayMs = random.nextLong(88L, 115L)
        delay(stepDelayMs)
        if (_connectionState.value !is ScaleConnectionState.Connected) break

        val nowMs = SystemClock.elapsedRealtime()
        val dtSec = (nowMs - lastTickMs).coerceAtLeast(1L) / 1000.0
        lastTickMs = nowMs

        val simElapsedSec = (nowMs - connectedAtMonotonicMs) / 1000.0
        val cfg = _demoConfig.value
        val (rateGPerSec, phaseLabel) = if (cfg.autoPhaseEnabled) {
          independentAutoPourProfile(simElapsedSec)
        } else {
          cfg.manualPourRateGPerSec to "手動控制：${"%.1f".format(cfg.manualPourRateGPerSec)} g/s"
        }
        if (cfg.currentAutoPhaseLabel != phaseLabel) {
          _demoConfig.update { it.copy(currentAutoPhaseLabel = phaseLabel) }
        }

        addedWaterMassG += rateGPerSec * dtSec

        // Triangular-like small noise centered around 0
        val noise = if (cfg.noiseAmplitudeG > 0.0) {
          ((random.nextDouble() - random.nextDouble()) * cfg.noiseAmplitudeG)
        } else {
          0.0
        }
        val netMassG = (simAssemblyBaseWeightG + addedWaterMassG) - tareOffsetG + noise
        val quantisedG = round(netMassG * 10.0) / 10.0

        sequenceNum += 1L
        val quality = if (remainingWarmUpSamples > 0) {
          remainingWarmUpSamples -= 1
          SampleQuality.WARMING_UP
        } else {
          SampleQuality.VALID
        }

        val sample = ScaleSample(
          id = UUID.randomUUID().toString(),
          sessionId = "",
          sourceType = SourceType.DEMO,
          deviceId = _deviceInfo.value.deviceId,
          streamEpochId = _currentStreamEpochId.value,
          rawWeightG = quantisedG,
          deviceTimestampMs = deviceClockBaseMs + (nowMs - connectedAtMonotonicMs),
          sequenceNumber = sequenceNum,
          monotonicReceiveMs = nowMs,
          elapsedMs = 0L,
          timeBasis = TimeBasis.DEVICE_MONOTONIC,
          qualityState = quality,
        )
        _samples.tryEmit(sample)
      }
    }
  }

  /**
   * Deliberately independent from any recipe schedule:
   * - 0–5s: initial wait (0 g/s)
   * - 5–21s: independent bloom pour (~3.6 g/s -> ~57.6g)
   * - 21–48s: rest (0 g/s)
   * - 48–82s: main pour (~3.9 g/s -> ~132.6g)
   * - 82–108s: rest (0 g/s)
   * - 108–142s: final pour (~3.4 g/s -> ~115.6g)
   * - 142s+: drawdown rest (0 g/s)
   */
  private fun independentAutoPourProfile(simSec: Double): Pair<Double, String> {
    return when {
      simSec < 5.0 -> 0.0 to "自動模擬：等待注水"
      simSec < 21.0 -> 3.6 to "自動模擬：第一段注水 (~3.6 g/s)"
      simSec < 48.0 -> 0.0 to "自動模擬：靜置觀察"
      simSec < 82.0 -> 3.9 to "自動模擬：第二段注水 (~3.9 g/s)"
      simSec < 108.0 -> 0.0 to "自動模擬：靜置觀察"
      simSec < 142.0 -> 3.4 to "自動模擬：第三段注水 (~3.4 g/s)"
      else -> 0.0 to "自動模擬：滴濾靜置"
    }
  }

  private fun emitQualityEvent(title: String, detail: String, isWarning: Boolean) {
    _qualityEvents.tryEmit(
      ConnectionQualityEvent(
        id = UUID.randomUUID().toString(),
        timestampEpochMs = System.currentTimeMillis(),
        title = title,
        detail = detail,
        isWarning = isWarning,
      )
    )
  }
}
