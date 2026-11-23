package com.example.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BrewLabApplication
import com.example.data.FullBrewSessionRecord
import com.example.device.DemoSimConfig
import com.example.device.ScaleConnectionState
import com.example.device.ScaleDeviceInfo
import com.example.domain.measurement.RecipeValidator
import com.example.domain.measurement.SessionAnalyzer
import com.example.domain.model.BrewEventType
import com.example.domain.model.BrewSession
import com.example.domain.model.ConnectionQualityEvent
import com.example.domain.model.FutureSensorSlot
import com.example.domain.model.Recipe
import com.example.domain.model.RecipeValidationResult
import com.example.domain.model.SessionAnalysisResult
import com.example.domain.model.SmoothingWindow
import com.example.domain.model.SourceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
  val sourceType: SourceType,
  val deviceInfo: ScaleDeviceInfo,
  val connectionState: ScaleConnectionState,
  val latestSampleAgeMs: Long?,
  val observedSampleRateHz: Double?,
  val smoothingWindow: SmoothingWindow,
  val currentStreamEpochId: Int,
  val recentEvents: List<ConnectionQualityEvent>,
  val futureSensors: List<FutureSensorSlot>,
)

class BrewLabViewModel(application: Application) : AndroidViewModel(application) {
  private val app = application as BrewLabApplication
  private val repository = app.repository
  private val deviceManager = app.deviceManager
  private val preferences = app.preferences
  val coordinator = app.brewCoordinator

  val recipes: StateFlow<List<Recipe>> = repository.recipes
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val sessions: StateFlow<List<BrewSession>> = repository.sessions
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val liveBrewState = coordinator.state
  val connectionState = deviceManager.connectionState
  val deviceInfo = deviceManager.deviceInfo
  val selectedSourceType = deviceManager.selectedSourceType
  val smoothingWindow = preferences.smoothingWindow
  val demoNoiseG = preferences.demoNoiseG

  val demoSimConfig: StateFlow<DemoSimConfig> = deviceManager.demoDataSource.demoConfig

  private val tickerFlow: Flow<Long> = flow {
    while (true) {
      emit(SystemClock.elapsedRealtime())
      delay(250L)
    }
  }

  val diagnosticsState: StateFlow<DiagnosticsUiState> = combine(
    deviceManager.selectedSourceType,
    deviceManager.deviceInfo,
    deviceManager.connectionState,
    deviceManager.latestSampleMonotonicMs,
    deviceManager.observedSampleRateHz,
    preferences.smoothingWindow,
    deviceManager.currentStreamEpochId,
    deviceManager.recentQualityEvents,
    tickerFlow,
  ) { arr ->
    val srcType = arr[0] as SourceType
    val info = arr[1] as ScaleDeviceInfo
    val conn = arr[2] as ScaleConnectionState
    val latestMono = arr[3] as Long?
    val hz = arr[4] as Double?
    val window = arr[5] as SmoothingWindow
    val epoch = arr[6] as Int
    @Suppress("UNCHECKED_CAST")
    val events = arr[7] as List<ConnectionQualityEvent>
    val nowMono = arr[8] as Long

    val ageMs = if (conn.isConnected && latestMono != null) {
      (nowMono - latestMono).coerceAtLeast(0L)
    } else {
      null
    }

    DiagnosticsUiState(
      sourceType = srcType,
      deviceInfo = info,
      connectionState = conn,
      latestSampleAgeMs = ageMs,
      observedSampleRateHz = if (conn.isConnected) hz else null,
      smoothingWindow = window,
      currentStreamEpochId = epoch,
      recentEvents = events,
      futureSensors = deviceManager.futureSensorSlots,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = DiagnosticsUiState(
      sourceType = SourceType.DEMO,
      deviceInfo = deviceManager.deviceInfo.value,
      connectionState = deviceManager.connectionState.value,
      latestSampleAgeMs = null,
      observedSampleRateHz = null,
      smoothingWindow = preferences.smoothingWindow.value,
      currentStreamEpochId = deviceManager.currentStreamEpochId.value,
      recentEvents = emptyList(),
      futureSensors = deviceManager.futureSensorSlots,
    ),
  )

  init {
    viewModelScope.launch {
      repository.ensureSeeded()
    }
    deviceManager.demoDataSource.setNoiseAmplitude(preferences.demoNoiseG.value)
  }

  // --- Device & Settings Actions ---

  fun connectScale() {
    viewModelScope.launch {
      deviceManager.connect()
    }
  }

  fun disconnectScale() {
    viewModelScope.launch {
      deviceManager.disconnect()
    }
  }

  fun tareScaleBeforeStart() {
    viewModelScope.launch {
      coordinator.performTareBeforeStart()
    }
  }

  fun selectSourceType(type: SourceType) {
    if (liveBrewState.value.phase != com.example.brew.BrewPhase.IDLE) return
    viewModelScope.launch {
      deviceManager.selectSourceType(type)
    }
  }

  fun setSmoothingWindow(window: SmoothingWindow) {
    preferences.setSmoothingWindow(window)
  }

  fun setDemoNoise(noiseG: Double) {
    preferences.setDemoNoiseG(noiseG)
    deviceManager.demoDataSource.setNoiseAmplitude(noiseG)
  }

  fun setDemoAutoPhase(enabled: Boolean) {
    deviceManager.activeDemoControls()?.setAutoPhaseEnabled(enabled)
  }

  fun setDemoManualPourRate(rateGPerSec: Double) {
    deviceManager.activeDemoControls()?.setManualPourRate(rateGPerSec)
  }

  fun simulateDemoInterruption() {
    deviceManager.activeDemoControls()?.simulateInterruption(2200L)
  }

  // --- Live Brew Actions ---

  fun selectRecipe(recipe: Recipe) = coordinator.selectRecipe(recipe)
  fun setSetupReminderExpanded(expanded: Boolean) = coordinator.setSetupReminderExpanded(expanded)
  fun startBrewRecording() = coordinator.startRecording()
  fun recordManualEvent(type: BrewEventType, note: String = "") = coordinator.recordManualEvent(type, note)
  fun finishBrewRecording() = coordinator.finishRecording()
  fun updatePendingNotes(notes: String) = coordinator.updatePendingNotes(notes)
  fun discardReviewedSession() = coordinator.discardReviewedSession()

  fun saveReviewedSession(onSaved: (String) -> Unit) {
    viewModelScope.launch {
      val savedId = coordinator.saveReviewedSession()
      if (savedId != null) {
        onSaved(savedId)
      }
    }
  }

  // --- Recipe CRUD ---

  fun validateRecipe(recipe: Recipe): RecipeValidationResult = RecipeValidator.validate(recipe)

  fun saveRecipe(recipe: Recipe, isNew: Boolean, onDone: () -> Unit) {
    viewModelScope.launch {
      repository.saveRecipe(recipe, isNew)
      onDone()
    }
  }

  fun duplicateRecipe(recipe: Recipe) {
    viewModelScope.launch {
      repository.duplicateRecipe(recipe)
    }
  }

  fun deleteRecipe(recipeId: String) {
    viewModelScope.launch {
      repository.deleteRecipe(recipeId)
    }
  }

  // --- Session Detail & History ---

  fun observeSessionDetail(sessionId: String): Flow<Pair<FullBrewSessionRecord, SessionAnalysisResult>?> {
    return repository.observeSessionDetail(sessionId).combine(preferences.smoothingWindow) { record, _ ->
      record?.let {
        val analysis = SessionAnalyzer.analyze(
          recipeSnapshot = it.session.recipeSnapshot,
          samples = it.samples,
          events = it.events,
          tareBaselineG = it.session.tareBaselineG,
          config = it.session.algorithmConfig,
          sessionDurationMs = it.session.durationMs,
        )
        it to analysis
      }
    }
  }

  fun updateSessionNotes(sessionId: String, notes: String) {
    viewModelScope.launch {
      repository.updateSessionNotes(sessionId, notes)
    }
  }

  fun deleteSession(sessionId: String, onDeleted: () -> Unit) {
    viewModelScope.launch {
      repository.deleteSession(sessionId)
      onDeleted()
    }
  }
}
