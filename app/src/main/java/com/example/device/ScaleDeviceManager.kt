package com.example.device

import com.example.device.ble.BleScaleDataSource
import com.example.domain.model.ConnectionQualityEvent
import com.example.domain.model.FutureSensorSlot
import com.example.domain.model.ScaleSample
import com.example.domain.model.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates the active [ScaleDataSource] and exposes unified reactive streams.
 * Simulated connections are strictly ephemeral and never persisted across app launches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScaleDeviceManager(
  private val bleDataSource: BleScaleDataSource,
  val demoDataSource: DemoScaleDataSource = DemoScaleDataSource(),
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  val bleCandidates = bleDataSource.candidates
  val bleInvalidPayload = bleDataSource.invalidPayload

  private val _selectedSourceType = MutableStateFlow(SourceType.DEMO)
  val selectedSourceType: StateFlow<SourceType> = _selectedSourceType.asStateFlow()

  private fun resolveSource(type: SourceType): ScaleDataSource =
    when (type) {
      SourceType.DEMO -> demoDataSource
      SourceType.BLE_HARDWARE -> bleDataSource
    }

  val deviceInfo: StateFlow<ScaleDeviceInfo> = _selectedSourceType
    .flatMapLatest { resolveSource(it).deviceInfo }
    .stateIn(scope, SharingStarted.Eagerly, demoDataSource.deviceInfo.value)

  val connectionState: StateFlow<ScaleConnectionState> = _selectedSourceType
    .flatMapLatest { resolveSource(it).connectionState }
    .stateIn(scope, SharingStarted.Eagerly, demoDataSource.connectionState.value)

  val currentStreamEpochId: StateFlow<Int> = _selectedSourceType
    .flatMapLatest { resolveSource(it).currentStreamEpochId }
    .stateIn(scope, SharingStarted.Eagerly, demoDataSource.currentStreamEpochId.value)

  val samples: SharedFlow<ScaleSample> = _selectedSourceType
    .flatMapLatest { resolveSource(it).samples }
    .filter(::isCurrentSample)
    .shareIn(scope, SharingStarted.Eagerly, replay = 0)

  private val _recentQualityEvents = MutableStateFlow<List<ConnectionQualityEvent>>(emptyList())
  val recentQualityEvents: StateFlow<List<ConnectionQualityEvent>> = _recentQualityEvents.asStateFlow()

  private val _latestSampleMonotonicMs = MutableStateFlow<Long?>(null)
  val latestSampleMonotonicMs: StateFlow<Long?> = _latestSampleMonotonicMs.asStateFlow()

  private val _observedSampleRateHz = MutableStateFlow<Double?>(null)
  val observedSampleRateHz: StateFlow<Double?> = _observedSampleRateHz.asStateFlow()

  private val _latestRawReadingG = MutableStateFlow<Double?>(null)
  val latestRawReadingG: StateFlow<Double?> = _latestRawReadingG.asStateFlow()

  val futureSensorSlots: List<FutureSensorSlot> = listOf(
    FutureSensorSlot(sensorName = "水溫感測器（預留擴充）", unit = "°C", statusLabel = "尚未整合", latestReading = "—"),
    FutureSensorSlot(sensorName = "TDS 濃度計（預留擴充）", unit = "ppm", statusLabel = "尚未整合", latestReading = "—"),
  )

  private val recentTimestamps = ArrayDeque<Long>()

  init {
    scope.launch {
      merge(demoDataSource.qualityEvents, bleDataSource.qualityEvents).collect { event ->
        _recentQualityEvents.update { list -> (listOf(event) + list).take(30) }
      }
    }
    scope.launch {
      bleDataSource.connectionState.collect { state ->
        if (_selectedSourceType.value == SourceType.BLE_HARDWARE && !state.isConnected) clearReadings()
      }
    }
    scope.launch {
      samples.collect { sample ->
        if (!isCurrentSample(sample)) return@collect
        _latestRawReadingG.value = sample.rawWeightG
        _latestSampleMonotonicMs.value = sample.monotonicReceiveMs
        if (recentTimestamps.isNotEmpty() &&
          sample.monotonicReceiveMs - recentTimestamps.last() > 1000L
        ) {
          recentTimestamps.clear()
        }
        recentTimestamps.addLast(sample.monotonicReceiveMs)
        while (recentTimestamps.size > 20) {
          recentTimestamps.removeFirst()
        }
        val spanMs = recentTimestamps.last() - recentTimestamps.first()
        _observedSampleRateHz.value = if (recentTimestamps.size >= 3 && spanMs > 0L) {
          (recentTimestamps.size - 1) * 1000.0 / spanMs
        } else null
      }
    }
  }

  private fun isCurrentSample(sample: ScaleSample): Boolean {
    val source = activeDataSource()
    return sample.sourceType == source.sourceType &&
      (sample.sourceType != SourceType.BLE_HARDWARE ||
        source.connectionState.value.isConnected && sample.streamEpochId == source.currentStreamEpochId.value)
  }

  fun activeDataSource(): ScaleDataSource = resolveSource(_selectedSourceType.value)

  fun activeDemoControls(): DemoScaleControls? =
    activeDataSource() as? DemoScaleControls

  suspend fun selectSourceType(type: SourceType) = withContext(Dispatchers.Main.immediate) {
    if (_selectedSourceType.value == type) return@withContext
    activeDataSource().disconnect()
    clearReadings()
    _selectedSourceType.value = type
  }

  suspend fun connect() = withContext(Dispatchers.Main.immediate) {
    activeDataSource().connect()
  }

  suspend fun selectBleDevice(id: String) = withContext(Dispatchers.Main.immediate) {
    if (_selectedSourceType.value == SourceType.BLE_HARDWARE) bleDataSource.selectDevice(id)
  }

  suspend fun disconnect() = withContext(Dispatchers.Main.immediate) {
    activeDataSource().disconnect()
    clearReadings()
  }

  private fun clearReadings() {
    _latestRawReadingG.value = null
    _latestSampleMonotonicMs.value = null
    _observedSampleRateHz.value = null
    recentTimestamps.clear()
  }

  suspend fun tare(): Boolean = withContext(Dispatchers.Main.immediate) {
    val source = activeDataSource()
    if (ScaleCommand.TARE !in source.supportedCommands || !source.connectionState.value.isConnected) {
      return@withContext false
    }
    source.tare()
    true
  }
}
