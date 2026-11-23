package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.BrewExporter
import com.example.data.ExportKind
import com.example.data.FullBrewSessionRecord
import com.example.domain.model.BrewSession
import com.example.domain.model.SessionAnalysisResult
import com.example.ui.components.BrewFormat
import com.example.ui.components.CumulativeWeightChartCard
import com.example.ui.components.InstrumentSectionCard
import com.example.ui.components.MeasurementModelNoticeCard
import com.example.ui.components.PouringRateChartCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.MonoReadoutSmall
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalCrimsonBg
import com.example.ui.theme.SignalOchre
import com.example.ui.theme.SignalOchreBg
import com.example.ui.theme.SignalTeal
import com.example.ui.theme.SignalTealBg

@Composable
fun HistoryScreen(
  sessions: List<BrewSession>,
  onOpenSession: (String) -> Unit,
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
      if (sessions.isEmpty()) {
        item {
          InstrumentSectionCard {
            Text("尚無沖煮紀錄", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "完成一次模擬或實測沖煮並儲存後，報告與原始數據將會顯示於此。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        items(sessions, key = { it.id }) { session ->
          InstrumentSectionCard(
            modifier = Modifier
              .clickable { onOpenSession(session.id) }
              .testTag("history_session_card_${session.id}"),
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
                    text = "${session.recipeSnapshot.name}（v${session.recipeSnapshot.version}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
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
                  text = "${BrewFormat.formatDateTime(session.startedAtEpochMs)} · 來源：${session.sourceType.displayName}",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = "時長 ${BrewFormat.formatMmSs(session.durationMs)} · 粉量 ${BrewFormat.formatGrams(session.recipeSnapshot.coffeeDoseG, 0)} · 目標 ${BrewFormat.formatGrams(session.recipeSnapshot.totalTargetWaterG, 0)}",
                  style = MonoReadoutSmall,
                )
              }
              Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "查看詳細報告",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionDetailScreen(
  record: FullBrewSessionRecord,
  analysis: SessionAnalysisResult,
  onSaveNotes: (String) -> Unit,
  onDeleteSession: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val session = record.session
  val snapshot = session.recipeSnapshot
  var notesDraft by remember(session.id, session.notes) { mutableStateOf(session.notes) }
  var showDeleteDialog by remember { mutableStateOf(false) }
  var pendingSaveContent by remember { mutableStateOf<Pair<String, String>?>(null) }

  val createDocLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("*/*"),
  ) { uri ->
    val content = pendingSaveContent?.second
    if (uri != null && content != null) {
      val ok = BrewExporter.writeToDocumentUri(context, uri, content)
      Toast.makeText(
        context,
        if (ok) "已儲存檔案" else "儲存失敗",
        Toast.LENGTH_SHORT,
      ).show()
    }
    pendingSaveContent = null
  }

  fun exportContent(kind: ExportKind): String = when (kind) {
    ExportKind.RAW_SAMPLES_CSV -> BrewExporter.buildRawSamplesCsv(session, record.samples)
    ExportKind.EVENTS_CSV -> BrewExporter.buildEventsCsv(session, record.events)
    ExportKind.SESSION_JSON -> BrewExporter.buildSessionJson(session, record.samples, record.events)
  }

  if (showDeleteDialog) {
    AlertDialog(
      onDismissRequest = { showDeleteDialog = false },
      title = { Text("刪除此筆沖煮紀錄？") },
      text = { Text("刪除後將移除本筆紀錄、原始樣本與事件標記，且無法復原。") },
      confirmButton = {
        Button(
          onClick = {
            showDeleteDialog = false
            onDeleteSession()
          },
          colors = ButtonDefaults.buttonColors(containerColor = SignalCrimson),
          modifier = Modifier.testTag("session_delete_confirm_button"),
        ) {
          Text("確定刪除")
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
      },
    )
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = 680.dp),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Header Metadata
      item {
        InstrumentSectionCard {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "${snapshot.name}（快照 v${snapshot.version}）",
              style = MaterialTheme.typography.headlineSmall,
              modifier = Modifier.weight(1f),
            )
            if (session.isSeededDemo) {
              StatusPill("模擬範例", SignalOchreBg, SignalOchre)
            }
          }
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "時間：${BrewFormat.formatDateTime(session.startedAtEpochMs)} · 時長 ${BrewFormat.formatMmSs(session.durationMs)}",
            style = MonoReadoutSmall,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "數據來源：${session.sourceType.displayName}（${session.deviceName}）\n時間基準：${session.timeBasis.displayName} · 演算法 v${session.algorithmConfig.version}（視窗 ${session.algorithmConfig.smoothingWindowMs / 1000.0} 秒）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "器具：${snapshot.equipment} · 粉量 ${BrewFormat.formatGrams(snapshot.coffeeDoseG, 0)} · 目標水量 ${BrewFormat.formatGrams(snapshot.totalTargetWaterG, 0)} · 最後有效累計注水量 ${BrewFormat.formatGrams(analysis.finalValidWaterG)}",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }

      // Charts
      item {
        CumulativeWeightChartCard(
          title = "累計注水量曲線（實測 vs 配方目標）",
          steps = snapshot.steps,
          targetTotalWaterG = snapshot.totalTargetWaterG,
          actualSegments = analysis.validWeightSegments,
          events = record.events,
          missingIntervals = analysis.qualitySummary.missingIntervals,
          currentElapsedMs = session.durationMs,
        )
      }

      item {
        PouringRateChartCard(
          title = "注水速率曲線",
          steps = snapshot.steps,
          rateSegments = analysis.validRateSegments,
          events = record.events,
          missingIntervals = analysis.qualitySummary.missingIntervals,
          currentElapsedMs = session.durationMs,
        )
      }

      // Stage comparisons (Target vs Actual)
      item {
        InstrumentSectionCard {
          Text("配方階段目標與實測比較", style = MaterialTheme.typography.titleMedium)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "階段均速包含該段靜置時間。若區間發生數據中斷或干擾，實測欄位顯示為「—」。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(10.dp))
          analysis.stageComparisons.forEachIndexed { idx, stage ->
            if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = "${stage.step.label}（${BrewFormat.formatSecAsMmSs(stage.step.startSec)}–${BrewFormat.formatSecAsMmSs(stage.step.endSec)}）",
                style = MaterialTheme.typography.titleMedium,
              )
              Text(
                text = stage.step.type.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "目標注水：${BrewFormat.formatGrams(stage.targetAddedWaterG)}  |  實測注水：${BrewFormat.formatGrams(stage.actualAddedWaterG)}  |  差值：${BrewFormat.formatSignedDeltaGrams(stage.waterDeltaG)}",
              style = MonoReadoutSmall,
            )
            Text(
              text = "階段均速（目標 / 實測）：${BrewFormat.formatRate(stage.targetStageAverageRateGPerSec)} / ${BrewFormat.formatRate(stage.actualStageAverageRateGPerSec)}",
              style = MonoReadoutSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = stage.descriptiveFeedback,
              style = MaterialTheme.typography.bodyMedium,
              color = if (stage.isDataSupported) MaterialTheme.colorScheme.onSurface else SignalCrimson,
            )
          }
        }
      }

      // Manually Marked Pouring Intervals
      item {
        InstrumentSectionCard {
          Text("手動標記注水段摘要", style = MaterialTheme.typography.titleMedium)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "依據「標記注水開始」與「標記注水停止」計算該有效注水段之實際注水量與平均注水速率。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(10.dp))
          if (analysis.pourIntervals.isEmpty()) {
            Text(
              text = "此次沖煮未標記手動注水開始／停止事件。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            analysis.pourIntervals.forEachIndexed { idx, pour ->
              if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
              Text(
                text = "第 ${pour.index} 段注水（${BrewFormat.formatMmSs(pour.startElapsedMs)} → ${BrewFormat.formatMmSs(pour.endElapsedMs)}，${"%.1f".format(pour.durationSec)} 秒）",
                style = MaterialTheme.typography.titleMedium,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "段內注水量：${BrewFormat.formatGrams(pour.addedWaterG)}  |  有效注水均速：${BrewFormat.formatRate(pour.averageRateGPerSec)}",
                style = MonoReadoutSmall,
              )
              Text(
                text = pour.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (pour.isCompleteValidInterval) SignalTeal else SignalCrimson,
              )
            }
          }
        }
      }

      // Data quality and missing intervals
      item {
        val q = analysis.qualitySummary
        InstrumentSectionCard {
          Text("數據品質與缺口摘要", style = MaterialTheme.typography.titleMedium)
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "總樣本數：${q.totalSampleCount}（有效 ${q.validSampleCount}、預熱 ${q.warmUpSampleCount}）\n" +
              "實測採樣率：${BrewFormat.formatHz(q.observedSampleRateHz)} · 串流時段（Epochs）：${q.streamEpochCount} · 干擾事件：${q.interferenceEventCount}",
            style = MonoReadoutSmall,
          )
          Spacer(modifier = Modifier.height(8.dp))
          if (q.missingIntervals.isEmpty()) {
            StatusPill("全程數據連續無缺口", SignalTealBg, SignalTeal)
          } else {
            Text("中斷與缺口區間：", style = MaterialTheme.typography.labelLarge, color = SignalCrimson)
            Spacer(modifier = Modifier.height(4.dp))
            q.missingIntervals.forEach { gap ->
              Text(
                text = "• ${BrewFormat.formatMmSs(gap.startElapsedMs)}–${BrewFormat.formatMmSs(gap.endElapsedMs)}：${gap.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = SignalCrimson,
              )
            }
          }
        }
      }

      // Editable Tasting Notes
      item {
        InstrumentSectionCard {
          Text("品飲與練習筆記", style = MaterialTheme.typography.titleMedium)
          Spacer(modifier = Modifier.height(8.dp))
          OutlinedTextField(
            value = notesDraft,
            onValueChange = { notesDraft = it },
            label = { Text("筆記內容") },
            minLines = 3,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("session_notes_input"),
          )
          Spacer(modifier = Modifier.height(10.dp))
          Button(
            onClick = {
              onSaveNotes(notesDraft)
              Toast.makeText(context, "已儲存筆記", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
              .height(48.dp)
              .testTag("session_save_notes_button"),
          ) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("儲存筆記")
          }
        }
      }

      // Exports
      item {
        InstrumentSectionCard {
          Text("匯出真實儲存數據", style = MaterialTheme.typography.titleMedium)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "包含數據來源、時間基準（time_basis）與原始樣本；缺值保留為空白或 null。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(10.dp))
          ExportKind.entries.forEach { kind ->
            val fileName = BrewExporter.suggestedFileName(session, kind)
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = kind.label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
              )
              OutlinedButton(
                onClick = {
                  BrewExporter.shareExportFile(
                    context = context,
                    fileName = fileName,
                    mimeType = kind.mimeType,
                    content = exportContent(kind),
                  )
                },
                modifier = Modifier
                  .height(48.dp)
                  .testTag("export_share_${kind.name}"),
              ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("分享")
              }
              OutlinedButton(
                onClick = {
                  pendingSaveContent = fileName to exportContent(kind)
                  createDocLauncher.launch(fileName)
                },
                modifier = Modifier
                  .height(48.dp)
                  .testTag("export_save_${kind.name}"),
              ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("另存")
              }
            }
          }
        }
      }

      item {
        MeasurementModelNoticeCard()
      }

      item {
        OutlinedButton(
          onClick = { showDeleteDialog = true },
          colors = ButtonDefaults.outlinedButtonColors(contentColor = SignalCrimson),
          modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("session_delete_button"),
        ) {
          Icon(Icons.Filled.Delete, contentDescription = null)
          Spacer(modifier = Modifier.width(6.dp))
          Text("刪除此筆沖煮紀錄")
        }
        Spacer(modifier = Modifier.height(24.dp))
      }
    }
  }
}
