package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.domain.model.AlgorithmConfig
import com.example.domain.model.BrewEvent
import com.example.domain.model.BrewSession
import com.example.domain.model.RecipeSnapshot
import com.example.domain.model.ScaleSample
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportSessionPayload(
  val schemaVersion: String = "brewlab-export-v1",
  val exportedAtIso: String,
  val sessionId: String,
  val isSeededDemo: Boolean,
  val startedAtEpochMs: Long,
  val endedAtEpochMs: Long,
  val durationMs: Long,
  val sourceType: String,
  val sourceLabel: String,
  val deviceId: String,
  val deviceName: String,
  val timeBasis: String,
  val tareBaselineG: Double,
  val notes: String,
  val algorithmConfig: AlgorithmConfig,
  val recipeSnapshot: RecipeSnapshot,
  val events: List<ExportEventPayload>,
  val rawSamples: List<ExportSamplePayload>,
)

data class ExportEventPayload(
  val eventId: String,
  val type: String,
  val label: String,
  val elapsedMs: Long,
  val monotonicReceiveMs: Long,
  val note: String,
)

data class ExportSamplePayload(
  val sampleId: String,
  val sourceType: String,
  val deviceId: String,
  val streamEpochId: Int,
  val rawWeightG: Double,
  val deviceTimestampMs: Long?,
  val sequenceNumber: Long?,
  val monotonicReceiveMs: Long,
  val elapsedMs: Long,
  val timeBasis: String,
  val qualityState: String,
)

enum class ExportKind(val extension: String, val mimeType: String, val label: String) {
  RAW_SAMPLES_CSV("csv", "text/csv", "原始樣本 CSV"),
  EVENTS_CSV("csv", "text/csv", "事件紀錄 CSV"),
  SESSION_JSON("json", "application/json", "完整沖煮 JSON"),
}

object BrewExporter {

  private val exportMoshi: Moshi = Moshi.Builder()
    .addLast(KotlinJsonAdapterFactory())
    .build()

  private val payloadAdapter = exportMoshi
    .adapter(ExportSessionPayload::class.java)
    .serializeNulls()
    .indent("  ")

  fun buildRawSamplesCsv(session: BrewSession, samples: List<ScaleSample>): String {
    val sb = StringBuilder()
    sb.appendLine(
      "session_id,source_type,source_label,device_id,stream_epoch_id,raw_weight_g," +
        "device_timestamp_ms,sequence_number,monotonic_receive_ms,elapsed_ms,time_basis,quality_state"
    )
    samples.sortedBy { it.monotonicReceiveMs }.forEach { sample ->
      sb.append(csvCell(session.id)).append(',')
      sb.append(csvCell(sample.sourceType.name)).append(',')
      sb.append(csvCell(sample.sourceType.displayName)).append(',')
      sb.append(csvCell(sample.deviceId)).append(',')
      sb.append(sample.streamEpochId).append(',')
      sb.append(sample.rawWeightG).append(',')
      sb.append(sample.deviceTimestampMs?.toString() ?: "").append(',')
      sb.append(sample.sequenceNumber?.toString() ?: "").append(',')
      sb.append(sample.monotonicReceiveMs).append(',')
      sb.append(sample.elapsedMs).append(',')
      sb.append(csvCell(sample.timeBasis.name)).append(',')
      sb.append(csvCell(sample.qualityState.name))
      sb.appendLine()
    }
    return sb.toString()
  }

  fun buildEventsCsv(session: BrewSession, events: List<BrewEvent>): String {
    val sb = StringBuilder()
    sb.appendLine("session_id,source_type,time_basis,event_id,event_type,label,elapsed_ms,monotonic_receive_ms,note")
    events.sortedBy { it.elapsedMs }.forEach { event ->
      sb.append(csvCell(session.id)).append(',')
      sb.append(csvCell(session.sourceType.name)).append(',')
      sb.append(csvCell(session.timeBasis.name)).append(',')
      sb.append(csvCell(event.id)).append(',')
      sb.append(csvCell(event.type.name)).append(',')
      sb.append(csvCell(event.type.displayName)).append(',')
      sb.append(event.elapsedMs).append(',')
      sb.append(event.monotonicReceiveMs).append(',')
      sb.append(csvCell(event.note))
      sb.appendLine()
    }
    return sb.toString()
  }

  fun buildSessionJson(
    session: BrewSession,
    samples: List<ScaleSample>,
    events: List<BrewEvent>,
  ): String {
    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
    val payload = ExportSessionPayload(
      exportedAtIso = isoFormat.format(Date()),
      sessionId = session.id,
      isSeededDemo = session.isSeededDemo,
      startedAtEpochMs = session.startedAtEpochMs,
      endedAtEpochMs = session.endedAtEpochMs,
      durationMs = session.durationMs,
      sourceType = session.sourceType.name,
      sourceLabel = session.sourceType.displayName,
      deviceId = session.deviceId,
      deviceName = session.deviceName,
      timeBasis = session.timeBasis.name,
      tareBaselineG = session.tareBaselineG,
      notes = session.notes,
      algorithmConfig = session.algorithmConfig,
      recipeSnapshot = session.recipeSnapshot,
      events = events.sortedBy { it.elapsedMs }.map { ev ->
        ExportEventPayload(
          eventId = ev.id,
          type = ev.type.name,
          label = ev.type.displayName,
          elapsedMs = ev.elapsedMs,
          monotonicReceiveMs = ev.monotonicReceiveMs,
          note = ev.note,
        )
      },
      rawSamples = samples.sortedBy { it.monotonicReceiveMs }.map { s ->
        ExportSamplePayload(
          sampleId = s.id,
          sourceType = s.sourceType.name,
          deviceId = s.deviceId,
          streamEpochId = s.streamEpochId,
          rawWeightG = s.rawWeightG,
          deviceTimestampMs = s.deviceTimestampMs,
          sequenceNumber = s.sequenceNumber,
          monotonicReceiveMs = s.monotonicReceiveMs,
          elapsedMs = s.elapsedMs,
          timeBasis = s.timeBasis.name,
          qualityState = s.qualityState.name,
        )
      },
    )
    return payloadAdapter.toJson(payload)
  }

  fun suggestedFileName(session: BrewSession, kind: ExportKind): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(session.startedAtEpochMs))
    val suffix = when (kind) {
      ExportKind.RAW_SAMPLES_CSV -> "samples"
      ExportKind.EVENTS_CSV -> "events"
      ExportKind.SESSION_JSON -> "session"
    }
    return "brewlab_${stamp}_${suffix}.${kind.extension}"
  }

  fun shareExportFile(
    context: Context,
    fileName: String,
    mimeType: String,
    content: String,
  ) {
    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val outFile = File(exportDir, fileName)
    outFile.writeText(content, Charsets.UTF_8)

    val uri: Uri = FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      outFile,
    )

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = mimeType
      putExtra(Intent.EXTRA_STREAM, uri)
      putExtra(Intent.EXTRA_SUBJECT, fileName)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(shareIntent, "匯出沖煮數據：$fileName").apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
  }

  fun writeToDocumentUri(context: Context, uri: Uri, content: String): Boolean {
    return runCatching {
      context.contentResolver.openOutputStream(uri)?.use { stream ->
        stream.write(content.toByteArray(Charsets.UTF_8))
      } ?: return false
      true
    }.getOrDefault(false)
  }

  private fun csvCell(value: String): String {
    val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
    return if (needsQuotes) {
      "\"${value.replace("\"", "\"\"")}\""
    } else {
      value
    }
  }
}
