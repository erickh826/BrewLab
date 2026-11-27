package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.brew.BrewPhase
import com.example.brew.LiveBrewState
import com.example.device.DemoSimConfig
import com.example.device.ScaleConnectionState
import com.example.domain.model.BrewEventType
import com.example.domain.model.Recipe
import com.example.domain.model.SourceType
import com.example.domain.model.StepType
import com.example.ui.components.BrewFormat
import com.example.ui.components.CumulativeWeightChartCard
import com.example.ui.components.InstrumentSectionCard
import com.example.ui.components.PouringRateChartCard
import com.example.ui.components.SourceConnectionPill
import com.example.ui.components.StatusPill
import com.example.ui.theme.MonoReadoutLarge
import com.example.ui.theme.MonoReadoutMedium
import com.example.ui.theme.MonoReadoutSmall
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalCrimsonBg
import com.example.ui.theme.SignalOchre
import com.example.ui.theme.SignalOchreBg
import com.example.ui.theme.SignalTeal
import com.example.ui.theme.SignalTealBg

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrewScreen(
  liveState: LiveBrewState,
  recipes: List<Recipe>,
  selectedSourceType: SourceType,
  connectionState: ScaleConnectionState,
  supportsTare: Boolean,
  displayedBleWeightG: Double?,
  hasWeightReading: Boolean,
  demoSimConfig: DemoSimConfig,
  onSelectRecipe: (Recipe) -> Unit,
  onToggleReminder: (Boolean) -> Unit,
  onConnect: () -> Unit,
  onDisconnect: () -> Unit,
  onTareBeforeStart: () -> Unit,
  onStartRecording: () -> Unit,
  onFinishRecording: () -> Unit,
  onRecordEvent: (BrewEventType, String) -> Unit,
  onSetDemoAutoPhase: (Boolean) -> Unit,
  onSetDemoManualRate: (Double) -> Unit,
  onSimulateInterruption: () -> Unit,
  onUpdatePendingNotes: (String) -> Unit,
  onSaveReviewedSession: () -> Unit,
  onDiscardReviewedSession: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showNoteDialog by remember { mutableStateOf(false) }
  var noteDraft by remember { mutableStateOf("") }
  var showDiscardConfirm by remember { mutableStateOf(false) }
  var showRecipeMenu by remember { mutableStateOf(false) }

  if (showNoteDialog) {
    AlertDialog(
      onDismissRequest = { showNoteDialog = false },
      title = { Text("新增時間點備註") },
      text = {
        OutlinedTextField(
          value = noteDraft,
          onValueChange = { noteDraft = it },
          label = { Text("備註內容") },
          placeholder = { Text("例如：粉床偏乾、調整手腕高度") },
          modifier = Modifier
            .fillMaxWidth()
            .testTag("brew_note_dialog_input"),
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onRecordEvent(BrewEventType.NOTE, noteDraft)
            noteDraft = ""
            showNoteDialog = false
          },
          modifier = Modifier.testTag("brew_note_dialog_confirm"),
        ) {
          Text("加入標記")
        }
      },
      dismissButton = {
        TextButton(onClick = { showNoteDialog = false }) { Text("取消") }
      },
    )
  }

  if (showDiscardConfirm) {
    AlertDialog(
      onDismissRequest = { showDiscardConfirm = false },
      title = { Text("捨棄此次沖煮紀錄？") },
      text = { Text("捨棄後本次採集的樣本與事件將不會寫入本機資料庫。") },
      confirmButton = {
        Button(
          onClick = {
            showDiscardConfirm = false
            onDiscardReviewedSession()
          },
          colors = ButtonDefaults.buttonColors(containerColor = SignalCrimson),
          modifier = Modifier.testTag("brew_discard_confirm_button"),
        ) {
          Text("確定捨棄")
        }
      },
      dismissButton = {
        TextButton(onClick = { showDiscardConfirm = false }) { Text("返回") }
      },
    )
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    if (liveState.phase == BrewPhase.REVIEWING) {
      ReviewAfterBrewContent(
        liveState = liveState,
        onUpdateNotes = onUpdatePendingNotes,
        onSave = onSaveReviewedSession,
        onRequestDiscard = { showDiscardConfirm = true },
      )
      return@Box
    }

    val activeRecipe = liveState.selectedRecipe
    val activeSnapshot = liveState.activeSnapshot
    val displaySteps = activeSnapshot?.steps ?: activeRecipe?.steps.orEmpty()
    val targetTotalWater = activeSnapshot?.totalTargetWaterG ?: activeRecipe?.totalTargetWaterG ?: 300.0

    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = 680.dp),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // 1. Status & Recipe Bar
      item {
        InstrumentSectionCard {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            SourceConnectionPill(sourceType = selectedSourceType, connectionState = connectionState)
            StatusPill(
              text = liveState.dataQualityLabel,
              containerColor = if (liveState.isDataStreamHealthy) SignalTealBg else SignalOchreBg,
              contentColor = if (liveState.isDataStreamHealthy) SignalTeal else SignalOchre,
            )
          }

          if (liveState.foregroundInterruptedNotice) {
            Spacer(modifier = Modifier.height(8.dp))
            StatusPill(
              text = "偵測到程式曾離開前景：已保留既有數據並於缺口處斷開分析",
              containerColor = SignalCrimsonBg,
              contentColor = SignalCrimson,
            )
          }

          Spacer(modifier = Modifier.height(10.dp))

          // Recipe selector (locked during recording)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = if (liveState.phase == BrewPhase.RECORDING) "已鎖定配方快照" else "練習配方",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = activeSnapshot?.let { "${it.name}（v${it.version}）" }
                  ?: activeRecipe?.let { "${it.name}（v${it.version}）" }
                  ?: "請先於配方分頁建立配方",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
              )
            }
            if (liveState.phase == BrewPhase.IDLE && recipes.isNotEmpty()) {
              Box {
                OutlinedButton(
                  onClick = { showRecipeMenu = true },
                  modifier = Modifier.testTag("brew_select_recipe_button"),
                ) {
                  Text("切換配方")
                }
                DropdownMenu(
                  expanded = showRecipeMenu,
                  onDismissRequest = { showRecipeMenu = false },
                ) {
                  recipes.forEach { recipe ->
                    DropdownMenuItem(
                      text = {
                        Text("${recipe.name} (${BrewFormat.formatGrams(recipe.coffeeDoseG, 0)} / ${BrewFormat.formatGrams(recipe.totalTargetWaterG, 0)})")
                      },
                      onClick = {
                        onSelectRecipe(recipe)
                        showRecipeMenu = false
                      },
                    )
                  }
                }
              }
            }
          }
        }
      }

      // 2. Pre-start Setup Reminder (IDLE only)
      if (liveState.phase == BrewPhase.IDLE) {
        item {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(MaterialTheme.shapes.medium)
              .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
              .padding(horizontal = 14.dp, vertical = 10.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "開始前準備提醒",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
              IconButton(
                onClick = { onToggleReminder(!liveState.setupReminderExpanded) },
                modifier = Modifier.testTag("brew_toggle_reminder_button"),
              ) {
                Icon(
                  imageVector = if (liveState.setupReminderExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                  contentDescription = "展開或收合準備提醒",
                )
              }
            }
            AnimatedVisibility(visible = liveState.setupReminderExpanded) {
              Text(
                text = "1. 完成潤濕濾紙並排空分享壺餘水\n" +
                  "2. 將整組沖煮器具（濾杯、咖啡粉、分享壺）置於電子秤中央\n" +
                  "3. 等待讀數穩定後按「歸零 (Tare)」，再按「開始紀錄」",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
        }
      }

      // 3. Large Primary Telemetry Instrument Readouts
      item {
        InstrumentSectionCard {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            // Timer
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "經過時間 (mm:ss)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = BrewFormat.formatMmSs(liveState.elapsedMs),
                style = MonoReadoutLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("brew_elapsed_time_text"),
              )
            }

            // Live Pouring Rate
            Column(
              modifier = Modifier.weight(1f),
              horizontalAlignment = Alignment.End,
            ) {
              Text(
                text = "注水速率 (g/s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = BrewFormat.formatRate(liveState.liveRateGPerSec),
                style = MonoReadoutLarge,
                color = if (liveState.liveRateGPerSec != null) {
                  MaterialTheme.colorScheme.tertiary
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag("brew_live_rate_text"),
              )
            }
          }

          Spacer(modifier = Modifier.height(12.dp))

          // Cumulative Added Water
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(MaterialTheme.shapes.small)
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
              .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
          ) {
            Column {
              Text(
                text = if (selectedSourceType == SourceType.BLE_HARDWARE && liveState.phase == BrewPhase.IDLE) {
                  "電子秤讀數"
                } else {
                  "累計注水量"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = if (selectedSourceType == SourceType.BLE_HARDWARE && liveState.phase == BrewPhase.IDLE) {
                  displayedBleWeightG?.takeIf { connectionState.isConnected }?.let { "$it g" } ?: "—"
                } else {
                  BrewFormat.formatGrams(liveState.currentAddedWaterG)
                },
                style = MonoReadoutLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("brew_cumulative_water_text"),
              )
            }
            Column(horizontalAlignment = Alignment.End) {
              Text(
                text = "配方目標總量",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = BrewFormat.formatGrams(targetTotalWater, 0),
                style = MonoReadoutMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      // 4. Current Planned Recipe Step Banner (clearly labelled as planned, NOT auto-detected actual pour)
      if (liveState.phase == BrewPhase.RECORDING && liveState.activePlannedStep != null) {
        val planned = liveState.activePlannedStep
        item {
          InstrumentSectionCard {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              StatusPill(
                text = "配方計劃階段（僅供時間參考）",
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = "第 ${planned.stepIndex}/${planned.totalSteps} 步",
                style = MonoReadoutSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "${planned.step.label}（${planned.step.type.displayName}）",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold,
                )
                val targetDesc = if (planned.step.type == StepType.POUR) {
                  "計劃區間 ${BrewFormat.formatSecAsMmSs(planned.step.startSec)}–${BrewFormat.formatSecAsMmSs(planned.step.endSec)} · 本段目標 +${BrewFormat.formatGrams(planned.step.addedWaterG, 0)}（累計 ${BrewFormat.formatGrams(planned.targetWaterAtStepEnd, 0)}）"
                } else {
                  "計劃區間 ${BrewFormat.formatSecAsMmSs(planned.step.startSec)}–${BrewFormat.formatSecAsMmSs(planned.step.endSec)} · 靜置不注水"
                }
                Text(
                  text = targetDesc,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Text(
                text = if (planned.isCompleted) "已完成計劃" else "餘 ${"%.0f".format(planned.remainingSec)}s",
                style = MonoReadoutMedium,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }
      }

      // 5. Primary Controls (Connect, Tare [disabled during recording], Start/Finish)
      item {
        InstrumentSectionCard {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            if (liveState.phase == BrewPhase.IDLE) {
              OutlinedButton(
                onClick = {
                  if (selectedSourceType == SourceType.DEMO && connectionState.isConnected) onDisconnect()
                  else onConnect()
                },
                modifier = Modifier
                  .weight(1f)
                  .height(52.dp)
                  .testTag("brew_connect_button"),
              ) {
                Icon(Icons.Filled.Scale, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  when {
                    selectedSourceType == SourceType.BLE_HARDWARE -> "裝置設定"
                    connectionState.isConnected -> "斷開電子秤"
                    else -> "連接模擬秤"
                  }
                )
              }

              FilledTonalButton(
                onClick = onTareBeforeStart,
                enabled = supportsTare && connectionState.isConnected && !liveState.isTaring,
                modifier = Modifier
                  .weight(1f)
                  .height(52.dp)
                  .testTag("brew_tare_button"),
              ) {
                Text(
                  when {
                    liveState.isTaring -> "歸零中"
                    liveState.hasTaredBeforeStart && connectionState.isConnected -> "已歸零"
                    else -> "歸零 (Tare)"
                  },
                )
              }
            } else {
              // Tare explicitly disabled during active recording
              OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier
                  .weight(1f)
                  .height(52.dp)
                  .testTag("brew_tare_disabled_button"),
              ) {
                Icon(Icons.Filled.Balance, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("紀錄中停用歸零")
              }
            }

            if (liveState.phase == BrewPhase.IDLE) {
              Button(
                onClick = onStartRecording,
                enabled = connectionState.isConnected && !liveState.isTaring && activeRecipe != null &&
                  (selectedSourceType == SourceType.DEMO || hasWeightReading),
                modifier = Modifier
                  .weight(1f)
                  .height(52.dp)
                  .testTag("brew_start_button"),
              ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("開始紀錄")
              }
            } else {
              Button(
                onClick = onFinishRecording,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                modifier = Modifier
                  .weight(1f)
                  .height(52.dp)
                  .testTag("brew_finish_button"),
              ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("結束沖煮")
              }
            }
          }

          val tareMessage = liveState.tareError ?: if (connectionState.isConnected && !supportsTare) {
            "這台電子秤未支援遠端歸零。"
          } else {
            null
          }
          if (liveState.phase == BrewPhase.IDLE && tareMessage != null) {
            Text(
              text = tareMessage,
              modifier = Modifier.padding(top = 8.dp),
              style = MaterialTheme.typography.bodyMedium,
            )
          }

          // Manual Event Marking Controls during recording
          if (liveState.phase == BrewPhase.RECORDING) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = "手動標記事件（用於分段摘要與干擾標示）",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Button(
                onClick = { onRecordEvent(BrewEventType.POUR_START, "") },
                enabled = !liveState.isManuallyPouring,
                modifier = Modifier
                  .height(48.dp)
                  .testTag("brew_mark_pour_start_button"),
              ) {
                Icon(Icons.Filled.WaterDrop, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("標記注水開始")
              }
              FilledTonalButton(
                onClick = { onRecordEvent(BrewEventType.POUR_STOP, "") },
                enabled = liveState.isManuallyPouring,
                modifier = Modifier
                  .height(48.dp)
                  .testTag("brew_mark_pour_stop_button"),
              ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("標記注水停止")
              }
              OutlinedButton(
                onClick = { onRecordEvent(BrewEventType.TOUCH_EQUIPMENT, "") },
                modifier = Modifier
                  .height(48.dp)
                  .testTag("brew_mark_touch_button"),
              ) {
                Icon(Icons.Filled.PanTool, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("碰觸器具")
              }
              OutlinedButton(
                onClick = { onRecordEvent(BrewEventType.MOVE_EQUIPMENT, "") },
                modifier = Modifier
                  .height(48.dp)
                  .testTag("brew_mark_move_button"),
              ) {
                Text("移動器具")
              }
              OutlinedButton(
                onClick = { showNoteDialog = true },
                modifier = Modifier
                  .height(48.dp)
                  .testTag("brew_mark_note_button"),
              ) {
                Icon(Icons.Filled.EditNote, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("備註")
              }
            }
          }
        }
      }

      // 6. Dual Live Charts
      item {
        CumulativeWeightChartCard(
          title = "累計注水量曲線（實測 vs 配方目標）",
          steps = displaySteps,
          targetTotalWaterG = targetTotalWater,
          actualSegments = liveState.weightSegments,
          events = liveState.events,
          currentElapsedMs = liveState.elapsedMs,
        )
      }

      item {
        PouringRateChartCard(
          title = "注水速率曲線",
          steps = displaySteps,
          rateSegments = liveState.rateSegments,
          events = liveState.events,
          currentElapsedMs = liveState.elapsedMs,
        )
      }

      // 7. Demo Simulator Controls (independent of recipe!)
      if (selectedSourceType == SourceType.DEMO) {
        item {
          InstrumentSectionCard {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("模擬電子秤控制台（模擬數據）", style = MaterialTheme.typography.titleMedium)
                Text(
                  text = "模擬器獨立運作，不會自動配合配方目標。狀態：${demoSimConfig.currentAutoPhaseLabel}",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text("自動注水/靜置模擬", style = MaterialTheme.typography.bodyLarge)
              Switch(
                checked = demoSimConfig.autoPhaseEnabled,
                onCheckedChange = onSetDemoAutoPhase,
                modifier = Modifier.testTag("brew_demo_auto_switch"),
              )
            }

            if (!demoSimConfig.autoPhaseEnabled) {
              Spacer(modifier = Modifier.height(6.dp))
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text("手動模擬注水速率", style = MaterialTheme.typography.labelLarge)
                Text(
                  text = BrewFormat.formatRate(demoSimConfig.manualPourRateGPerSec),
                  style = MonoReadoutSmall,
                )
              }
              Slider(
                value = demoSimConfig.manualPourRateGPerSec.toFloat(),
                onValueChange = { onSetDemoManualRate(it.toDouble()) },
                valueRange = 0f..12f,
                modifier = Modifier.testTag("brew_demo_manual_rate_slider"),
              )
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
              onClick = onSimulateInterruption,
              enabled = connectionState.isConnected,
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("brew_simulate_interruption_button"),
            ) {
              Icon(Icons.Filled.WifiOff, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text("模擬斷線與重連（產生數據缺口）")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ReviewAfterBrewContent(
  liveState: LiveBrewState,
  onUpdateNotes: (String) -> Unit,
  onSave: () -> Unit,
  onRequestDiscard: () -> Unit,
) {
  val analysis = liveState.reviewAnalysis
  val snapshot = liveState.activeSnapshot

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .widthIn(max = 680.dp),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      InstrumentSectionCard {
        Text("完成紀錄 · 儲存前檢視", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "配方：${snapshot?.name ?: "—"} · 時長 ${BrewFormat.formatMmSs(liveState.elapsedMs)} · 累計注水量 ${BrewFormat.formatGrams(analysis?.finalValidWaterG)}",
          style = MonoReadoutSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = "有效樣本 ${analysis?.qualitySummary?.validSampleCount ?: 0} / ${analysis?.qualitySummary?.totalSampleCount ?: 0} 筆 · 中斷區間 ${analysis?.qualitySummary?.missingIntervals?.size ?: 0} 段 · 手動注水段 ${analysis?.pourIntervals?.size ?: 0} 段",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }

    item {
      InstrumentSectionCard {
        Text("沖煮筆記與觀察", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = liveState.pendingNotes,
          onValueChange = onUpdateNotes,
          label = { Text("品飲筆記 / 操作備忘") },
          placeholder = { Text("記錄口感、水柱穩定度或想調整的步驟…") },
          minLines = 3,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("brew_review_notes_input"),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedButton(
            onClick = onRequestDiscard,
            modifier = Modifier
              .weight(1f)
              .height(50.dp)
              .testTag("brew_review_discard_button"),
          ) {
            Text("捨棄")
          }
          Button(
            onClick = onSave,
            modifier = Modifier
              .weight(2f)
              .height(50.dp)
              .testTag("brew_review_save_button"),
          ) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("儲存紀錄並查看報告")
          }
        }
      }
    }

    if (snapshot != null && analysis != null) {
      item {
        CumulativeWeightChartCard(
          steps = snapshot.steps,
          targetTotalWaterG = snapshot.totalTargetWaterG,
          actualSegments = analysis.validWeightSegments,
          events = liveState.events,
          missingIntervals = analysis.qualitySummary.missingIntervals,
          currentElapsedMs = liveState.elapsedMs,
        )
      }
    }
  }
}
