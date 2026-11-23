package com.example.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.brew.BrewPhase
import com.example.brew.LiveBrewState
import com.example.device.ScaleConnectionState
import com.example.device.ScaleDeviceInfo
import com.example.domain.model.BrewSession
import com.example.domain.model.Recipe
import com.example.domain.model.SourceType
import com.example.ui.components.BrewFormat
import com.example.ui.components.InstrumentSectionCard
import com.example.ui.components.MeasurementModelNoticeCard
import com.example.ui.components.SourceConnectionPill
import com.example.ui.components.StatusPill
import com.example.ui.theme.MonoReadoutLarge
import com.example.ui.theme.MonoReadoutSmall
import com.example.ui.theme.SignalOchre
import com.example.ui.theme.SignalOchreBg

@Composable
fun HomeScreen(
  liveState: LiveBrewState,
  deviceInfo: ScaleDeviceInfo,
  connectionState: ScaleConnectionState,
  selectedSourceType: SourceType,
  recipes: List<Recipe>,
  recentSessions: List<BrewSession>,
  onConnectOrDisconnect: () -> Unit,
  onGoToBrew: () -> Unit,
  onGoToRecipes: () -> Unit,
  onOpenSession: (String) -> Unit,
  onOpenDeviceSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter,
  ) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = 680.dp),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Hero instrument card
      item {
        InstrumentSectionCard {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "BrewLab 手沖量測工作台",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = if (selectedSourceType == SourceType.DEMO) {
                  "目前使用「模擬數據」進行練習與曲線驗證"
                } else {
                  "實體藍牙電子秤適配邊界（尚未整合）"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          Spacer(modifier = Modifier.height(12.dp))
          SourceConnectionPill(sourceType = selectedSourceType, connectionState = connectionState)

          if (liveState.phase == BrewPhase.RECORDING) {
            Spacer(modifier = Modifier.height(10.dp))
            StatusPill(
              text = "進行中紀錄：${BrewFormat.formatMmSs(liveState.elapsedMs)} · 累計注水量 ${BrewFormat.formatGrams(liveState.currentAddedWaterG)}",
              containerColor = SignalOchreBg,
              contentColor = SignalOchre,
            )
          } else if (connectionState.isConnected) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
              verticalAlignment = Alignment.Bottom,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = BrewFormat.formatGrams(liveState.currentAddedWaterG),
                style = MonoReadoutLarge,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "目前電子秤讀數",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
              )
            }
          }

          Spacer(modifier = Modifier.height(14.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Button(
              onClick = onGoToBrew,
              modifier = Modifier
                .weight(1f)
                .height(50.dp)
                .testTag("home_go_to_brew_button"),
            ) {
              Icon(Icons.Filled.PlayArrow, contentDescription = null)
              Spacer(modifier = Modifier.width(6.dp))
              Text(if (liveState.phase == BrewPhase.RECORDING) "返回即時沖煮" else "前往沖煮台")
            }

            if (selectedSourceType == SourceType.DEMO) {
              OutlinedButton(
                onClick = onConnectOrDisconnect,
                modifier = Modifier
                  .height(50.dp)
                  .testTag("home_connect_button"),
              ) {
                Icon(Icons.Filled.Scale, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (connectionState.isConnected) "斷開模擬秤" else "連接模擬秤")
              }
            } else {
              OutlinedButton(
                onClick = onOpenDeviceSettings,
                modifier = Modifier
                  .height(50.dp)
                  .testTag("home_device_settings_button"),
              ) {
                Icon(Icons.Filled.Tune, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("裝置設定")
              }
            }
          }
        }
      }

      // Active recipe preview
      item {
        val activeRecipe = liveState.selectedRecipe ?: recipes.firstOrNull()
        InstrumentSectionCard {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                Icons.Filled.LocalCafe,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text("目前選用配方", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(
              onClick = onGoToRecipes,
              modifier = Modifier.testTag("home_manage_recipes_button"),
            ) {
              Text("配方管理")
            }
          }

          if (activeRecipe == null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "尚未建立任何配方，請先新增或複製示範配方。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "${activeRecipe.name}（v${activeRecipe.version}）",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.SemiBold,
            )
            if (activeRecipe.isDemoTemplate) {
              Spacer(modifier = Modifier.height(4.dp))
              StatusPill(
                text = "示範配方（可編輯範例，非業界標準）",
                containerColor = SignalOchreBg,
                contentColor = SignalOchre,
              )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "器具：${activeRecipe.equipment}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "粉量 ${BrewFormat.formatGrams(activeRecipe.coffeeDoseG, 0)} · 目標總水量 ${BrewFormat.formatGrams(activeRecipe.totalTargetWaterG, 0)} · 共 ${activeRecipe.steps.size} 步驟（${BrewFormat.formatSecAsMmSs(activeRecipe.totalDurationSec)}）",
              style = MonoReadoutSmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }

      // Measurement model notice
      item {
        MeasurementModelNoticeCard()
      }

      // Recent sessions preview
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "近期沖煮紀錄",
            style = MaterialTheme.typography.titleLarge,
          )
          Text(
            text = "共 ${recentSessions.size} 筆",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (recentSessions.isEmpty()) {
        item {
          InstrumentSectionCard {
            Text(
              text = "尚無儲存的沖煮紀錄。前往「沖煮」分頁連接模擬電子秤即可開始練習。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        items(recentSessions.take(3), key = { it.id }) { session ->
          InstrumentSectionCard(
            modifier = Modifier
              .clickable { onOpenSession(session.id) }
              .testTag("home_recent_session_${session.id}"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Text(
                    text = session.recipeSnapshot.name,
                    style = MaterialTheme.typography.titleMedium,
                  )
                  if (session.isSeededDemo) {
                    StatusPill(
                      text = "模擬範例",
                      containerColor = SignalOchreBg,
                      contentColor = SignalOchre,
                    )
                  }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "${BrewFormat.formatDateTime(session.startedAtEpochMs)} · ${session.sourceType.displayName} · 時長 ${BrewFormat.formatMmSs(session.durationMs)}",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "查看報告",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}
