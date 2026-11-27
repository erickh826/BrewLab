package com.example.device

import com.example.domain.model.ConnectionQualityEvent
import com.example.domain.model.ScaleSample
import com.example.domain.model.SourceType
import com.example.domain.model.TimeBasis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface ScaleConnectionState {
  val label: String
  val isConnected: Boolean
    get() = this is Connected

  val canDisconnect: Boolean
    get() = when (this) {
      Scanning, SelectingDevice, Connecting, DiscoveringServices, Subscribing,
      AwaitingSample, Connected, Reconnecting -> true
      else -> false
    }

  data object Disconnected : ScaleConnectionState {
    override val label: String = "未連線"
  }

  data object Scanning : ScaleConnectionState {
    override val label: String = "正在掃描電子秤…"
  }

  data object SelectingDevice : ScaleConnectionState {
    override val label: String = "請選擇電子秤"
  }

  data object Connecting : ScaleConnectionState {
    override val label: String = "連線中…"
  }

  data object DiscoveringServices : ScaleConnectionState {
    override val label: String = "正在搜尋重量服務…"
  }

  data object Subscribing : ScaleConnectionState {
    override val label: String = "正在啟用重量通知…"
  }

  data object AwaitingSample : ScaleConnectionState {
    override val label: String = "藍牙已連線 · 等待有效重量"
  }

  data object Connected : ScaleConnectionState {
    override val label: String = "已連線"
  }

  data object Reconnecting : ScaleConnectionState {
    override val label: String = "重新連線中…"
  }

  data class Interrupted(val reason: String) : ScaleConnectionState {
    override val label: String = "數據中斷：$reason"
  }

  data class BleFailure(override val label: String) : ScaleConnectionState

  data class DisconnectedWithReason(val reason: String) : ScaleConnectionState {
    override val label: String = "已斷線 · $reason"
  }
}

enum class ScaleCommand {
  CONNECT,
  DISCONNECT,
  TARE,
  MANUAL_SIM_POUR_RATE,
  SIMULATE_INTERRUPTION,
  TOGGLE_AUTO_SIM,
}

data class ScaleDeviceInfo(
  val deviceId: String,
  val displayName: String,
  val sourceType: SourceType,
  val isSimulated: Boolean,
  val timeBasis: TimeBasis,
  val detailLabel: String,
)

data class DemoSimConfig(
  val autoPhaseEnabled: Boolean = true,
  val manualPourRateGPerSec: Double = 0.0,
  val noiseAmplitudeG: Double = 0.1,
  val currentAutoPhaseLabel: String = "待機",
)

interface ScaleDataSource {
  val sourceType: SourceType
  val deviceInfo: StateFlow<ScaleDeviceInfo>
  val connectionState: StateFlow<ScaleConnectionState>
  val currentStreamEpochId: StateFlow<Int>
  val samples: Flow<ScaleSample>
  val qualityEvents: Flow<ConnectionQualityEvent>
  val supportedCommands: Set<ScaleCommand>

  suspend fun connect()
  suspend fun disconnect()
  suspend fun tare()
}

interface DemoScaleControls {
  val demoConfig: StateFlow<DemoSimConfig>
  fun setAutoPhaseEnabled(enabled: Boolean)
  fun setManualPourRate(rateGPerSec: Double)
  fun setNoiseAmplitude(noiseG: Double)
  fun simulateInterruption(durationMs: Long = 2200L)
}
