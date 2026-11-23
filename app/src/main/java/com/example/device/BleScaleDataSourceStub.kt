package com.example.device

import com.example.domain.model.ConnectionQualityEvent
import com.example.domain.model.ScaleSample
import com.example.domain.model.SourceType
import com.example.domain.model.TimeBasis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Extension boundary for future physical BLE coffee scales (commercial scales or custom ESP32).
 *
 * Integration instructions for future hardware milestones:
 * 1. Implement a dedicated BLE GATT transport & protocol parser in `com.example.device.ble`.
 * 2. Keep all GATT characteristic UUIDs, packet framing, and vendor parsing strictly inside
 *    that adapter—never inside UI composables or `com.example.domain.measurement`.
 * 3. Emit `ScaleSample` objects with `sourceType = SourceType.BLE_HARDWARE`, real monotonic receive
 *    timestamps, and accurate `TimeBasis`.
 *
 * In this milestone, hardware BLE is intentionally unimplemented and strictly reports "尚未整合".
 */
class BleScaleDataSourceStub : ScaleDataSource {

  override val sourceType: SourceType = SourceType.BLE_HARDWARE

  private val _deviceInfo = MutableStateFlow(
    ScaleDeviceInfo(
      deviceId = "ble-unintegrated",
      displayName = "實體藍牙電子秤（尚未整合）",
      sourceType = SourceType.BLE_HARDWARE,
      isSimulated = false,
      timeBasis = TimeBasis.HOST_MONOTONIC,
      detailLabel = "尚未整合 · 預留商業電子秤 / ESP32 BLE 介面邊界",
    )
  )
  override val deviceInfo: StateFlow<ScaleDeviceInfo> = _deviceInfo.asStateFlow()

  private val _connectionState = MutableStateFlow<ScaleConnectionState>(
    ScaleConnectionState.NotIntegrated("尚未整合")
  )
  override val connectionState: StateFlow<ScaleConnectionState> = _connectionState.asStateFlow()

  private val _currentStreamEpochId = MutableStateFlow(0)
  override val currentStreamEpochId: StateFlow<Int> = _currentStreamEpochId.asStateFlow()

  private val _samples = MutableSharedFlow<ScaleSample>(extraBufferCapacity = 8)
  override val samples: Flow<ScaleSample> = _samples.asSharedFlow()

  private val _qualityEvents = MutableSharedFlow<ConnectionQualityEvent>(extraBufferCapacity = 16)
  override val qualityEvents: Flow<ConnectionQualityEvent> = _qualityEvents.asSharedFlow()

  override val supportedCommands: Set<ScaleCommand> = emptySet()

  override suspend fun connect() {
    _connectionState.value = ScaleConnectionState.NotIntegrated("尚未整合")
    _qualityEvents.tryEmit(
      ConnectionQualityEvent(
        id = UUID.randomUUID().toString(),
        timestampEpochMs = System.currentTimeMillis(),
        title = "實體藍牙電子秤尚未整合",
        detail = "此版本僅支援模擬數據模式，BLE 適配層尚未實作物理連線。",
        isWarning = true,
      )
    )
  }

  override suspend fun disconnect() {
    _connectionState.value = ScaleConnectionState.NotIntegrated("尚未整合")
  }

  override suspend fun tare() {
    // No-op while BLE hardware adapter is unimplemented
  }
}
