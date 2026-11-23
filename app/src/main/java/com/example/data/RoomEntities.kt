package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.BrewEvent
import com.example.domain.model.BrewEventType
import com.example.domain.model.BrewSession
import com.example.domain.model.Recipe
import com.example.domain.model.SampleQuality
import com.example.domain.model.ScaleSample
import com.example.domain.model.SourceType
import com.example.domain.model.TimeBasis

@Entity(tableName = "recipes")
data class RecipeEntity(
  @PrimaryKey val id: String,
  val name: String,
  val version: Int,
  val equipment: String,
  val coffeeDoseG: Double,
  val totalTargetWaterG: Double,
  val beanNotes: String,
  val roastNotes: String,
  val grinderNotes: String,
  val isDemoTemplate: Boolean,
  val updatedAtEpochMs: Long,
  val stepsJson: String,
) {
  fun toDomain(): Recipe = Recipe(
    id = id,
    name = name,
    version = version,
    equipment = equipment,
    coffeeDoseG = coffeeDoseG,
    totalTargetWaterG = totalTargetWaterG,
    beanNotes = beanNotes,
    roastNotes = roastNotes,
    grinderNotes = grinderNotes,
    isDemoTemplate = isDemoTemplate,
    updatedAtEpochMs = updatedAtEpochMs,
    steps = BrewJsonCodec.decodeSteps(stepsJson),
  )

  companion object {
    fun fromDomain(recipe: Recipe): RecipeEntity = RecipeEntity(
      id = recipe.id,
      name = recipe.name,
      version = recipe.version,
      equipment = recipe.equipment,
      coffeeDoseG = recipe.coffeeDoseG,
      totalTargetWaterG = recipe.totalTargetWaterG,
      beanNotes = recipe.beanNotes,
      roastNotes = recipe.roastNotes,
      grinderNotes = recipe.grinderNotes,
      isDemoTemplate = recipe.isDemoTemplate,
      updatedAtEpochMs = recipe.updatedAtEpochMs,
      stepsJson = BrewJsonCodec.encodeSteps(recipe.steps),
    )
  }
}

@Entity(tableName = "brew_sessions")
data class BrewSessionEntity(
  @PrimaryKey val id: String,
  val startedAtEpochMs: Long,
  val endedAtEpochMs: Long,
  val durationMs: Long,
  val sourceType: String,
  val deviceId: String,
  val deviceName: String,
  val timeBasis: String,
  val tareBaselineG: Double,
  val notes: String,
  val isSeededDemo: Boolean,
  val recipeSnapshotJson: String,
  val algorithmConfigJson: String,
) {
  fun toDomain(): BrewSession = BrewSession(
    id = id,
    startedAtEpochMs = startedAtEpochMs,
    endedAtEpochMs = endedAtEpochMs,
    durationMs = durationMs,
    sourceType = SourceType.valueOf(sourceType),
    deviceId = deviceId,
    deviceName = deviceName,
    timeBasis = TimeBasis.valueOf(timeBasis),
    tareBaselineG = tareBaselineG,
    notes = notes,
    isSeededDemo = isSeededDemo,
    recipeSnapshot = BrewJsonCodec.decodeSnapshot(recipeSnapshotJson),
    algorithmConfig = BrewJsonCodec.decodeAlgorithmConfig(algorithmConfigJson),
  )

  companion object {
    fun fromDomain(session: BrewSession): BrewSessionEntity = BrewSessionEntity(
      id = session.id,
      startedAtEpochMs = session.startedAtEpochMs,
      endedAtEpochMs = session.endedAtEpochMs,
      durationMs = session.durationMs,
      sourceType = session.sourceType.name,
      deviceId = session.deviceId,
      deviceName = session.deviceName,
      timeBasis = session.timeBasis.name,
      tareBaselineG = session.tareBaselineG,
      notes = session.notes,
      isSeededDemo = session.isSeededDemo,
      recipeSnapshotJson = BrewJsonCodec.encodeSnapshot(session.recipeSnapshot),
      algorithmConfigJson = BrewJsonCodec.encodeAlgorithmConfig(session.algorithmConfig),
    )
  }
}

@Entity(
  tableName = "scale_samples",
  foreignKeys = [
    ForeignKey(
      entity = BrewSessionEntity::class,
      parentColumns = ["id"],
      childColumns = ["sessionId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [Index("sessionId")],
)
data class ScaleSampleEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
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
) {
  fun toDomain(): ScaleSample = ScaleSample(
    id = id,
    sessionId = sessionId,
    sourceType = SourceType.valueOf(sourceType),
    deviceId = deviceId,
    streamEpochId = streamEpochId,
    rawWeightG = rawWeightG,
    deviceTimestampMs = deviceTimestampMs,
    sequenceNumber = sequenceNumber,
    monotonicReceiveMs = monotonicReceiveMs,
    elapsedMs = elapsedMs,
    timeBasis = TimeBasis.valueOf(timeBasis),
    qualityState = SampleQuality.valueOf(qualityState),
  )

  companion object {
    fun fromDomain(sample: ScaleSample): ScaleSampleEntity = ScaleSampleEntity(
      id = sample.id,
      sessionId = sample.sessionId,
      sourceType = sample.sourceType.name,
      deviceId = sample.deviceId,
      streamEpochId = sample.streamEpochId,
      rawWeightG = sample.rawWeightG,
      deviceTimestampMs = sample.deviceTimestampMs,
      sequenceNumber = sample.sequenceNumber,
      monotonicReceiveMs = sample.monotonicReceiveMs,
      elapsedMs = sample.elapsedMs,
      timeBasis = sample.timeBasis.name,
      qualityState = sample.qualityState.name,
    )
  }
}

@Entity(
  tableName = "brew_events",
  foreignKeys = [
    ForeignKey(
      entity = BrewSessionEntity::class,
      parentColumns = ["id"],
      childColumns = ["sessionId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [Index("sessionId")],
)
data class BrewEventEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val eventType: String,
  val elapsedMs: Long,
  val monotonicReceiveMs: Long,
  val note: String,
) {
  fun toDomain(): BrewEvent = BrewEvent(
    id = id,
    sessionId = sessionId,
    type = BrewEventType.valueOf(eventType),
    elapsedMs = elapsedMs,
    monotonicReceiveMs = monotonicReceiveMs,
    note = note,
  )

  companion object {
    fun fromDomain(event: BrewEvent): BrewEventEntity = BrewEventEntity(
      id = event.id,
      sessionId = event.sessionId,
      eventType = event.type.name,
      elapsedMs = event.elapsedMs,
      monotonicReceiveMs = event.monotonicReceiveMs,
      note = event.note,
    )
  }
}
