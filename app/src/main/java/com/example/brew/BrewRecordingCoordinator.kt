package com.example.brew

import android.os.SystemClock
import com.example.data.AppPreferences
import com.example.data.BrewRepository
import com.example.device.ScaleConnectionId
import com.example.device.ScaleConnectionState
import com.example.device.ScaleDeviceManager
import com.example.device.ScaleTareResult
import com.example.domain.measurement.ActivePlannedStep
import com.example.domain.measurement.RateEstimator
import com.example.domain.measurement.SessionAnalyzer
import com.example.domain.measurement.TargetCurveGenerator
import com.example.domain.model.AlgorithmConfig
import com.example.domain.model.BrewEvent
import com.example.domain.model.BrewEventType
import com.example.domain.model.BrewSession
import com.example.domain.model.PlottedPoint
import com.example.domain.model.Recipe
import com.example.domain.model.RecipeSnapshot
import com.example.domain.model.SampleQuality
import com.example.domain.model.ScaleSample
import com.example.domain.model.SessionAnalysisResult
import com.example.domain.model.SourceType
import com.example.domain.model.TimeBasis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class BrewPhase {
  IDLE,
  RECORDING,
  REVIEWING,
}

data class LiveBrewState(
  val phase: BrewPhase = BrewPhase.IDLE,
  val selectedRecipe: Recipe? = null,
  val activeSnapshot: RecipeSnapshot? = null,
  val hasTaredBeforeStart: Boolean = false,
  val isTaring: Boolean = false,
  val tareError: String? = null,
  val setupReminderExpanded: Boolean = true,
  val elapsedMs: Long = 0L,
  val currentAddedWaterG: Double? = null,
  val liveRateGPerSec: Double? = null,
  val activePlannedStep: ActivePlannedStep? = null,
  val dataQualityLabel: String = "尚未開始",
  val isDataStreamHealthy: Boolean = false,
  val isManuallyPouring: Boolean = false,
  val tareBaselineG: Double = 0.0,
  val samples: List<ScaleSample> = emptyList(),
  val events: List<BrewEvent> = emptyList(),
  val weightSegments: List<List<PlottedPoint>> = emptyList(),
  val rateSegments: List<List<PlottedPoint>> = emptyList(),
  val reviewAnalysis: SessionAnalysisResult? = null,
  val pendingSession: BrewSession? = null,
  val pendingNotes: String = "",
  val foregroundInterruptedNotice: Boolean = false,
)

/**
 * Application-scoped coordinator owning the single live sample subscription and recording timer.
 * Surviving tab navigation ensures timers never reset and duplicate subscriptions never occur.
 */
class BrewRecordingCoordinator(
  private val repository: BrewRepository,
  private val deviceManager: ScaleDeviceManager,
  private val preferences: AppPreferences,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _state = MutableStateFlow(LiveBrewState())
  val state: StateFlow<LiveBrewState> = _state.asStateFlow()

  private var timerJob: Job? = null
  private var sessionId: String = ""
  private var startedAtEpochMs: Long = 0L
  private var startedMonotonicMs: Long = 0L
  private var sessionSourceType: SourceType = SourceType.DEMO
  private var sessionDeviceId: String = ""
  private var sessionDeviceName: String = ""
  private var sessionTimeBasis: TimeBasis = TimeBasis.DEVICE_MONOTONIC
  private var sessionConfig: AlgorithmConfig = AlgorithmConfig()

  private var isAppInForeground: Boolean = true
  private var foregroundEpochOffset: Int = 0
  private var postResumeWarmUpRemaining: Int = 0
  private var lastDeviceTimestampInEpoch: Long? = null
  private var lastDeviceEpochSeen: Int? = null
  private var wasConnectedDuringRecording: Boolean = false

  private val recordedSamples = mutableListOf<ScaleSample>()
  private val recordedEvents = mutableListOf<BrewEvent>()
  private val lock = Any()
  private var taredConnection: ScaleConnectionId? = null

  init {
    scope.launch {
      repository.recipes.collect { recipes ->
        val prefId = preferences.selectedRecipeId.value
        val current = _state.value.selectedRecipe
        val resolved = recipes.firstOrNull { it.id == current?.id }
          ?: recipes.firstOrNull { it.id == prefId }
          ?: recipes.firstOrNull()
        _state.update { it.copy(selectedRecipe = resolved) }
      }
    }

    // Single persistent sample subscription
    scope.launch {
      deviceManager.samples.collect { incoming ->
        handleIncomingSample(incoming)
      }
    }

    // Monitor connection state changes during recording
    scope.launch {
      deviceManager.connectionState.collect { connState ->
        handleConnectionStateChange(connState)
      }
    }

    scope.launch(Dispatchers.Main.immediate) {
      deviceManager.activeConnection.collect { connection ->
        if (taredConnection != null && taredConnection != deviceManager.currentConnectionId()) {
          taredConnection = null
          _state.update { it.copy(hasTaredBeforeStart = false) }
        }
        if (connection != null && !_state.value.isTaring) {
          _state.update { it.copy(tareError = null) }
        }
      }
    }

    scope.launch(Dispatchers.Main.immediate) {
      deviceManager.selectedSourceType.collect {
        _state.update { it.copy(tareError = null) }
      }
    }

    // Keep smoothing window synced when idle
    scope.launch {
      preferences.smoothingWindow.collect { window ->
        if (_state.value.phase == BrewPhase.IDLE) {
          sessionConfig = sessionConfig.copy(smoothingWindowMs = window.windowMs)
        }
      }
    }
  }

  fun selectRecipe(recipe: Recipe) {
    if (_state.value.phase != BrewPhase.IDLE) return
    preferences.setSelectedRecipeId(recipe.id)
    _state.update { it.copy(selectedRecipe = recipe) }
  }

  fun setSetupReminderExpanded(expanded: Boolean) {
    _state.update { it.copy(setupReminderExpanded = expanded) }
  }

  suspend fun performTareBeforeStart() = withContext(Dispatchers.Main.immediate) {
    val state = _state.value
    if (state.phase != BrewPhase.IDLE || state.isTaring) return@withContext
    val connection = deviceManager.currentConnectionId() ?: return@withContext
    taredConnection = null
    _state.update { it.copy(isTaring = true, hasTaredBeforeStart = false, tareError = null) }
    try {
      val result = deviceManager.tare()
      val currentResult = if (deviceManager.currentConnectionId() == connection) {
        result
      } else {
        ScaleTareResult.DISCONNECTED
      }
      val success = currentResult == ScaleTareResult.SUCCESS
      if (success) taredConnection = connection
      val sameSource = deviceManager.selectedSourceType.value == connection.sourceType
      _state.update {
        it.copy(hasTaredBeforeStart = success, tareError = currentResult.errorLabel.takeIf { sameSource })
      }
    } finally {
      _state.update { it.copy(isTaring = false) }
    }
  }

  fun startRecording() {
    val current = _state.value
    if (current.phase != BrewPhase.IDLE || current.isTaring) return
    val recipe = current.selectedRecipe ?: return
    val connection = deviceManager.currentConnectionId() ?: return

    val nowEpoch = System.currentTimeMillis()
    val nowMono = SystemClock.elapsedRealtime()
    val info = deviceManager.deviceInfo.value
    val smoothing = preferences.smoothingWindow.value
    val snapshot = recipe.toSnapshot(capturedAtEpochMs = nowEpoch)

    val latestWeight = deviceManager.latestRawReadingG.value
    if (info.sourceType == SourceType.BLE_HARDWARE && latestWeight == null) return
    val initialBaseline = if (current.hasTaredBeforeStart && taredConnection == connection) {
      0.0
    } else {
      latestWeight ?: 0.0
    }

    synchronized(lock) {
      recordedSamples.clear()
      recordedEvents.clear()
      sessionId = UUID.randomUUID().toString()
      startedAtEpochMs = nowEpoch
      startedMonotonicMs = nowMono
      sessionSourceType = info.sourceType
      sessionDeviceId = info.deviceId
      sessionDeviceName = info.displayName
      sessionTimeBasis = info.timeBasis
      sessionConfig = AlgorithmConfig(smoothingWindowMs = smoothing.windowMs)
      foregroundEpochOffset = 0
      postResumeWarmUpRemaining = 0
      lastDeviceTimestampInEpoch = null
      lastDeviceEpochSeen = deviceManager.currentStreamEpochId.value
      wasConnectedDuringRecording = true
    }

    _state.update {
      it.copy(
        phase = BrewPhase.RECORDING,
        activeSnapshot = snapshot,
        elapsedMs = 0L,
        currentAddedWaterG = 0.0,
        liveRateGPerSec = null,
        activePlannedStep = TargetCurveGenerator.activeStepAt(snapshot, 0L),
        dataQualityLabel = "前景紀錄中",
        isDataStreamHealthy = true,
        isManuallyPouring = false,
        tareBaselineG = initialBaseline,
        samples = emptyList(),
        events = emptyList(),
        weightSegments = emptyList(),
        rateSegments = emptyList(),
        reviewAnalysis = null,
        pendingSession = null,
        pendingNotes = "",
        foregroundInterruptedNotice = false,
      )
    }

    startTimerLoop()
  }

  fun recordManualEvent(type: BrewEventType, note: String = "") {
    if (_state.value.phase != BrewPhase.RECORDING) return
    val nowMono = SystemClock.elapsedRealtime()
    val elapsed = (nowMono - startedMonotonicMs).coerceAtLeast(0L)
    val event = BrewEvent(
      id = UUID.randomUUID().toString(),
      sessionId = sessionId,
      type = type,
      elapsedMs = elapsed,
      monotonicReceiveMs = nowMono,
      note = note.trim(),
    )
    synchronized(lock) {
      recordedEvents += event
      recomputeLiveUiStateLocked(nowMono)
    }
    if (type == BrewEventType.POUR_START) {
      _state.update { it.copy(isManuallyPouring = true) }
    } else if (type == BrewEventType.POUR_STOP) {
      _state.update { it.copy(isManuallyPouring = false) }
    }
  }

  fun finishRecording() {
    val current = _state.value
    if (current.phase != BrewPhase.RECORDING) return
    timerJob?.cancel()
    timerJob = null

    val nowMono = SystemClock.elapsedRealtime()
    val nowEpoch = System.currentTimeMillis()
    val durationMs = (nowMono - startedMonotonicMs).coerceAtLeast(0L)
    val snapshot = current.activeSnapshot ?: return

    val samplesCopy: List<ScaleSample>
    val eventsCopy: List<BrewEvent>
    synchronized(lock) {
      samplesCopy = recordedSamples.toList()
      eventsCopy = recordedEvents.toList()
    }

    val analysis = SessionAnalyzer.analyze(
      recipeSnapshot = snapshot,
      samples = samplesCopy,
      events = eventsCopy,
      tareBaselineG = current.tareBaselineG,
      config = sessionConfig,
      sessionDurationMs = durationMs,
    )

    val pendingSession = BrewSession(
      id = sessionId,
      startedAtEpochMs = startedAtEpochMs,
      endedAtEpochMs = nowEpoch,
      durationMs = durationMs,
      sourceType = sessionSourceType,
      deviceId = sessionDeviceId,
      deviceName = sessionDeviceName,
      timeBasis = sessionTimeBasis,
      tareBaselineG = current.tareBaselineG,
      notes = "",
      isSeededDemo = false,
      recipeSnapshot = snapshot,
      algorithmConfig = sessionConfig,
    )

    _state.update {
      it.copy(
        phase = BrewPhase.REVIEWING,
        elapsedMs = durationMs,
        samples = samplesCopy,
        events = eventsCopy,
        weightSegments = analysis.validWeightSegments,
        rateSegments = analysis.validRateSegments,
        reviewAnalysis = analysis,
        pendingSession = pendingSession,
        pendingNotes = "",
      )
    }
  }

  fun updatePendingNotes(notes: String) {
    if (_state.value.phase != BrewPhase.REVIEWING) return
    _state.update { it.copy(pendingNotes = notes) }
  }

  suspend fun saveReviewedSession(): String? {
    val current = _state.value
    if (current.phase != BrewPhase.REVIEWING) return null
    val pending = current.pendingSession ?: return null
    val finalSession = pending.copy(notes = current.pendingNotes.trim())

    repository.saveCompletedSession(
      session = finalSession,
      samples = current.samples,
      events = current.events,
    )
    resetToIdle()
    return finalSession.id
  }

  fun discardReviewedSession() {
    if (_state.value.phase != BrewPhase.REVIEWING) return
    resetToIdle()
  }

  fun onAppMovedToBackground() {
    isAppInForeground = false
    if (_state.value.phase != BrewPhase.RECORDING) return
    val nowMono = SystemClock.elapsedRealtime()
    val elapsed = (nowMono - startedMonotonicMs).coerceAtLeast(0L)
    synchronized(lock) {
      recordedEvents += BrewEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        type = BrewEventType.APP_BACKGROUNDED,
        elapsedMs = elapsed,
        monotonicReceiveMs = nowMono,
        note = "應用程式離開前景，保留既有樣本並標記中斷",
      )
      recomputeLiveUiStateLocked(nowMono)
    }
    _state.update {
      it.copy(
        dataQualityLabel = "離開前景中斷",
        isDataStreamHealthy = false,
        foregroundInterruptedNotice = true,
      )
    }
  }

  fun onAppReturnedToForeground() {
    isAppInForeground = true
    if (_state.value.phase != BrewPhase.RECORDING) return
    val nowMono = SystemClock.elapsedRealtime()
    val elapsed = (nowMono - startedMonotonicMs).coerceAtLeast(0L)
    synchronized(lock) {
      foregroundEpochOffset += 1
      postResumeWarmUpRemaining = sessionConfig.warmUpSampleCount
      recordedEvents += BrewEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        type = BrewEventType.RECONNECTED,
        elapsedMs = elapsed,
        monotonicReceiveMs = nowMono,
        note = "返回前景恢復採集（新串流時段）",
      )
      recomputeLiveUiStateLocked(nowMono)
    }
  }

  private fun resetToIdle() {
    timerJob?.cancel()
    timerJob = null
    taredConnection = null
    synchronized(lock) {
      recordedSamples.clear()
      recordedEvents.clear()
    }
    _state.update {
      it.copy(
        phase = BrewPhase.IDLE,
        hasTaredBeforeStart = false,
        tareError = null,
        activeSnapshot = null,
        elapsedMs = 0L,
        currentAddedWaterG = null,
        liveRateGPerSec = null,
        activePlannedStep = null,
        dataQualityLabel = "尚未開始",
        isDataStreamHealthy = false,
        isManuallyPouring = false,
        samples = emptyList(),
        events = emptyList(),
        weightSegments = emptyList(),
        rateSegments = emptyList(),
        reviewAnalysis = null,
        pendingSession = null,
        pendingNotes = "",
        foregroundInterruptedNotice = false,
      )
    }
  }

  private fun startTimerLoop() {
    timerJob?.cancel()
    timerJob = scope.launch {
      while (isActive && _state.value.phase == BrewPhase.RECORDING) {
        delay(200L)
        if (_state.value.phase != BrewPhase.RECORDING) break
        val nowMono = SystemClock.elapsedRealtime()
        synchronized(lock) {
          recomputeLiveUiStateLocked(nowMono)
        }
      }
    }
  }

  private fun handleConnectionStateChange(connState: ScaleConnectionState) {
    if (_state.value.phase != BrewPhase.RECORDING) return
    val nowMono = SystemClock.elapsedRealtime()
    val elapsed = (nowMono - startedMonotonicMs).coerceAtLeast(0L)

    synchronized(lock) {
      if (!connState.isConnected && wasConnectedDuringRecording) {
        wasConnectedDuringRecording = false
        recordedEvents += BrewEvent(
          id = UUID.randomUUID().toString(),
          sessionId = sessionId,
          type = BrewEventType.DISCONNECTED,
          elapsedMs = elapsed,
          monotonicReceiveMs = nowMono,
          note = connState.label,
        )
        recomputeLiveUiStateLocked(nowMono)
      } else if (connState.isConnected && !wasConnectedDuringRecording) {
        wasConnectedDuringRecording = true
        recordedEvents += BrewEvent(
          id = UUID.randomUUID().toString(),
          sessionId = sessionId,
          type = BrewEventType.RECONNECTED,
          elapsedMs = elapsed,
          monotonicReceiveMs = nowMono,
          note = "電子秤重新連線",
        )
        recomputeLiveUiStateLocked(nowMono)
      }
    }
  }

  private fun handleIncomingSample(incoming: ScaleSample) {
    val phase = _state.value.phase
    if (phase == BrewPhase.IDLE) {
      _state.update {
        it.copy(
          currentAddedWaterG = incoming.rawWeightG,
          dataQualityLabel = when {
            incoming.qualityState == SampleQuality.WARMING_UP -> "預熱緩衝中"
            incoming.sourceType == SourceType.BLE_HARDWARE -> "接收電子秤重量中"
            else -> "模擬連線穩定"
          },
          isDataStreamHealthy = incoming.qualityState == SampleQuality.VALID,
        )
      }
      return
    }

    if (phase != BrewPhase.RECORDING || !isAppInForeground) {
      return
    }

    val nowMono = incoming.monotonicReceiveMs
    val elapsed = (nowMono - startedMonotonicMs).coerceAtLeast(0L)

    synchronized(lock) {
      val epochChanged = lastDeviceEpochSeen != null && lastDeviceEpochSeen != incoming.streamEpochId
      val devTs = incoming.deviceTimestampMs
      val clockWentBackwards = !epochChanged &&
        devTs != null &&
        lastDeviceTimestampInEpoch != null &&
        devTs < lastDeviceTimestampInEpoch!!

      if (clockWentBackwards) {
        foregroundEpochOffset += 1
        postResumeWarmUpRemaining = sessionConfig.warmUpSampleCount
        recordedEvents += BrewEvent(
          id = UUID.randomUUID().toString(),
          sessionId = sessionId,
          type = BrewEventType.CLOCK_RESET,
          elapsedMs = elapsed,
          monotonicReceiveMs = nowMono,
          note = "偵測到裝置時鐘重置",
        )
      }

      lastDeviceEpochSeen = incoming.streamEpochId
      lastDeviceTimestampInEpoch = devTs

      val effectiveQuality = if (postResumeWarmUpRemaining > 0) {
        postResumeWarmUpRemaining -= 1
        SampleQuality.WARMING_UP
      } else {
        incoming.qualityState
      }

      val effectiveEpoch = incoming.streamEpochId + (foregroundEpochOffset * 1000)

      val recorded = incoming.copy(
        sessionId = sessionId,
        streamEpochId = effectiveEpoch,
        elapsedMs = elapsed,
        qualityState = effectiveQuality,
      )
      recordedSamples += recorded
      recomputeLiveUiStateLocked(nowMono)
    }
  }

  private fun recomputeLiveUiStateLocked(nowMono: Long) {
    val snapshot = _state.value.activeSnapshot ?: return
    val baseline = _state.value.tareBaselineG
    val elapsed = (nowMono - startedMonotonicMs).coerceAtLeast(0L)

    val analysis = SessionAnalyzer.analyze(
      recipeSnapshot = snapshot,
      samples = recordedSamples,
      events = recordedEvents,
      tareBaselineG = baseline,
      config = sessionConfig,
      sessionDurationMs = elapsed,
    )

    val liveRate = if (isAppInForeground && deviceManager.connectionState.value.isConnected) {
      RateEstimator.computeLiveRateGPerSec(
        samples = recordedSamples,
        events = recordedEvents,
        tareBaselineG = baseline,
        config = sessionConfig,
        nowMonotonicMs = nowMono,
      )
    } else {
      null
    }

    val latestSample = recordedSamples.lastOrNull()
    val latestAge = latestSample?.let { nowMono - it.monotonicReceiveMs }
    val streamFresh = latestSample != null &&
      latestAge != null &&
      latestAge <= sessionConfig.maxGapMsBeforeBreak &&
      isAppInForeground &&
      deviceManager.connectionState.value.isConnected

    val currentWater = if (streamFresh && latestSample != null) {
      latestSample.rawWeightG - baseline
    } else {
      null
    }

    val qualityText = when {
      !isAppInForeground -> "離開前景中斷"
      !deviceManager.connectionState.value.isConnected -> deviceManager.connectionState.value.label
      latestSample == null -> "等待第一個樣本"
      latestSample.qualityState == SampleQuality.WARMING_UP -> "重連預熱緩衝中"
      !streamFresh -> "樣本逾時缺口"
      else -> "有效數據串流中"
    }

    _state.update {
      it.copy(
        elapsedMs = elapsed,
        currentAddedWaterG = currentWater,
        liveRateGPerSec = liveRate,
        activePlannedStep = TargetCurveGenerator.activeStepAt(snapshot, elapsed),
        dataQualityLabel = qualityText,
        isDataStreamHealthy = streamFresh && latestSample?.qualityState == SampleQuality.VALID,
        samples = recordedSamples.toList(),
        events = recordedEvents.toList(),
        weightSegments = analysis.validWeightSegments,
        rateSegments = analysis.validRateSegments,
      )
    }
  }
}
