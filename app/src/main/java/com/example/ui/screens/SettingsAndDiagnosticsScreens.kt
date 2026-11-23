package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.device.ScaleConnectionState
import com.example.device.ScaleDeviceInfo
import com.example.domain.model.FutureSensorSlot
import com.example.domain.model.SmoothingWindow
import com.example.domain.model.SourceType
import com.example.ui.DiagnosticsUiState
import com.example.ui.components.BrewFormat
import com.example.ui.components.InstrumentSectionCard
import com.example.ui.components.SourceConnectionPill
import com.example.ui.components.StatusPill
import com.example.ui.theme.MonoReadoutSmall
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalCrimsonBg
import com.example.ui.theme.SignalOchre
import com.example.ui.theme.SignalOchreBg
import com.example.ui.theme.SignalTeal
import com.example.ui.theme.SignalTealBg

@Composable
fun DeviceSettingsScreen(
  selectedSourceType: SourceType,
  connectionState: ScaleConnectionState,
  deviceInfo: ScaleDeviceInfo,
  smoothingWindow: SmoothingWindow,
  demoNoiseG: Double,
  isRecording: Boolean,
  futureSensors: List<FutureSensorSlot>,
  onSelectSourceType: (SourceType) -> Unit,
  onSelectSmoothingWindow: (SmoothingWindow) -> Unit,
  onSelectDemoNoise: (Double) -> Unit,
  onConnect: () -> Unit,
  onDisconnect: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = 680.dp),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        InstrumentSectionCard {
          Text("電子秤數據來源", style = MaterialTheme.typography.titleLarge)
          Spacer(modifier = Modifier.height(6.dp))
          SourceConnectionPill(sourceType = selectedSourceType, connectionState = connectionState)
          Spacer(modifier = Modifier.height(10.dp))

          if (isRecording) {
            Text(
              text = "沖煮紀錄進行中，為保護數據完整性已鎖定來源切換。",
              style = MaterialTheme.typography.bodyMedium,
              color = SignalOchre,
            )
            Spacer(modifier = Modifier.height(6.dp))
          }

          SourceType.entries.forEach { source ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(
                selected = selectedSourceType == source,
                enabled = !isRecording,
                onClick = { onSelectSourceType(source) },
                modifier = Modifier.testTag("settings_source_${source.name}"),
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = if (source == SourceType.DEMO) "模擬電子秤（模擬數據）" else "實體藍牙電子秤（尚未整合）",
                  style = MaterialTheme.typography.titleMedium,
                )
                Text(
                  text = if (source == SourceType.DEMO) {
                    "以約 10 Hz 產生含真實時間戳與可調噪聲的練習讀數，獨立於配方目標。"
                  } else {
                    "尚未整合：預留商業 BLE 電子秤或自製 ESP32 裝置的 ScaleDataSource 適配介面，不會回報假連線。"
                  },
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(8.dp))
          if (selectedSourceType == SourceType.DEMO) {
            OutlinedButton(
              onClick = { if (connectionState.isConnected) onDisconnect() else onConnect() },
              enabled = !isRecording,
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("settings_connect_toggle_button"),
            ) {
              Text(if (connectionState.isConnected) "斷開模擬電子秤" else "連接模擬電子秤")
            }
          } else {
            StatusPill(
              text = "實體硬體狀態：尚未整合（未實作 BLE 掃描與封包解析）",
              containerColor = SignalCrimsonBg,
              contentColor = SignalCrimson,
            )
          }
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "裝置資訊：${deviceInfo.detailLabel}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      item {
        InstrumentSectionCard {
          Text("注水速率平滑視窗（最小平方法斜率）", style = MaterialTheme.typography.titleMedium)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "以實際樣本接收時間戳進行線性回歸計算（預設 1 秒），不跨斷線或缺口計算。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(10.dp))
          SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SmoothingWindow.entries.forEachIndexed { idx, window ->
              SegmentedButton(
                selected = smoothingWindow == window,
                onClick = { onSelectSmoothingWindow(window) },
                shape = SegmentedButtonDefaults.itemShape(idx, SmoothingWindow.entries.size),
                modifier = Modifier.testTag("settings_window_${window.windowMs}"),
              ) {
                Text(window.label)
              }
            }
          }
        }
      }

      item {
        InstrumentSectionCard {
          Text("模擬數據噪聲設定", style = MaterialTheme.typography.titleMedium)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "設定模擬讀數（0.1 g 量化）的微幅隨機波動幅度，以測試速率估算穩定性。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(10.dp))
          val options = listOf(0.0, 0.1, 0.2)
          SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { idx, noise ->
              SegmentedButton(
                selected = kotlin.math.abs(demoNoiseG - noise) < 0.02,
                onClick = { onSelectDemoNoise(noise) },
                shape = SegmentedButtonDefaults.itemShape(idx, options.size),
              ) {
                Text("±${"%.1f".format(noise)} g")
              }
            }
          }
        }
      }

      item {
        InstrumentSectionCard {
          Text("未來擴充感測器邊界", style = MaterialTheme.typography.titleMedium)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "預留給後續水溫與 TDS 感測器整合；未整合前絕不顯示假數據。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(10.dp))
          futureSensors.forEachIndexed { idx, slot ->
            if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column {
                Text(slot.sensorName, style = MaterialTheme.typography.bodyLarge)
                Text(
                  text = "狀態：${slot.statusLabel}",
                  style = MaterialTheme.typography.bodyMedium,
                  color = SignalCrimson,
                )
              }
              Text(
                text = "${slot.latestReading} ${slot.unit}",
                style = MonoReadoutSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun DiagnosticsScreen(
  diagnostics: DiagnosticsUiState,
  onSimulateInterruption: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = 680.dp),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        InstrumentSectionCard {
          Text("即時串流與系統診斷", style = MaterialTheme.typography.titleLarge)
          Spacer(modifier = Modifier.height(10.dp))
          SourceConnectionPill(
            sourceType = diagnostics.sourceType,
            connectionState = diagnostics.connectionState,
          )
          Spacer(modifier = Modifier.height(12.dp))

          DiagnosticRow("數據來源", diagnostics.sourceType.displayName)
          DiagnosticRow("連線狀態", diagnostics.connectionState.label)
          DiagnosticRow(
            "最新樣本延遲 (Age)",
            diagnostics.latestSampleAgeMs?.let { "$it ms" } ?: "—",
          )
          DiagnosticRow(
            "實測更新頻率",
            BrewFormat.formatHz(diagnostics.observedSampleRateHz),
          )
          DiagnosticRow("平滑視窗設定", diagnostics.smoothingWindow.label)
          DiagnosticRow("目前串流時段 (Epoch)", "#${diagnostics.currentStreamEpochId}")
          DiagnosticRow("時間基準 (Time Basis)", diagnostics.deviceInfo.timeBasis.displayName)

          if (diagnostics.sourceType == SourceType.DEMO && diagnostics.connectionState.isConnected) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
              onClick = onSimulateInterruption,
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("diagnostics_simulate_gap_button"),
            ) {
              Text("觸發模擬斷線測試 (2.2s)")
            }
          }
        }
      }

      item {
        Text("近期連線與數據品質事件", style = MaterialTheme.typography.titleMedium)
      }

      if (diagnostics.recentEvents.isEmpty()) {
        item {
          InstrumentSectionCard {
            Text(
              text = "尚無連線或數據品質事件。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        items(diagnostics.recentEvents, key = { it.id }) { event ->
          InstrumentSectionCard {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = event.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (event.isWarning) SignalCrimson else SignalTeal,
              )
              Text(
                text = BrewFormat.formatTimeOnly(event.timestampEpochMs),
                style = MonoReadoutSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = event.detail,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 5.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MonoReadoutSmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
