package com.example.data

import com.example.domain.model.BrewEvent
import com.example.domain.model.BrewSession
import com.example.domain.model.Recipe
import com.example.domain.model.ScaleSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

data class FullBrewSessionRecord(
  val session: BrewSession,
  val samples: List<ScaleSample>,
  val events: List<BrewEvent>,
)

class BrewRepository(
  private val database: BrewLabDatabase,
) {
  private val recipeDao = database.recipeDao()
  private val sessionDao = database.sessionDao()

  val recipes: Flow<List<Recipe>> = recipeDao.observeAll()
    .map { entities -> entities.map { it.toDomain() } }

  val sessions: Flow<List<BrewSession>> = sessionDao.observeAllSessions()
    .map { entities -> entities.map { it.toDomain() } }

  suspend fun ensureSeeded() {
    if (recipeDao.count() == 0) {
      recipeDao.upsert(RecipeEntity.fromDomain(SeedData.defaultDemoRecipe()))
    }
    if (sessionDao.countSessions() == 0) {
      SeedData.seededDemoSessions().forEach { (session, samples, events) ->
        sessionDao.insertFullSession(
          session = BrewSessionEntity.fromDomain(session),
          samples = samples.map { ScaleSampleEntity.fromDomain(it) },
          events = events.map { BrewEventEntity.fromDomain(it) },
        )
      }
    }
  }

  suspend fun saveRecipe(recipe: Recipe, isNew: Boolean) {
    val updated = if (isNew) {
      recipe.copy(updatedAtEpochMs = System.currentTimeMillis())
    } else {
      recipe.copy(
        version = recipe.version + 1,
        updatedAtEpochMs = System.currentTimeMillis(),
      )
    }
    recipeDao.upsert(RecipeEntity.fromDomain(updated))
  }

  suspend fun duplicateRecipe(recipe: Recipe): Recipe {
    val copy = recipe.copy(
      id = UUID.randomUUID().toString(),
      name = "${recipe.name}（複本）",
      version = 1,
      isDemoTemplate = false,
      updatedAtEpochMs = System.currentTimeMillis(),
      steps = recipe.steps.map { it.copy(id = UUID.randomUUID().toString()) },
    )
    recipeDao.upsert(RecipeEntity.fromDomain(copy))
    return copy
  }

  suspend fun deleteRecipe(recipeId: String) {
    recipeDao.deleteById(recipeId)
  }

  suspend fun saveCompletedSession(
    session: BrewSession,
    samples: List<ScaleSample>,
    events: List<BrewEvent>,
  ) {
    sessionDao.insertFullSession(
      session = BrewSessionEntity.fromDomain(session),
      samples = samples.map { ScaleSampleEntity.fromDomain(it) },
      events = events.map { BrewEventEntity.fromDomain(it) },
    )
  }

  fun observeSessionDetail(sessionId: String): Flow<FullBrewSessionRecord?> {
    return combine(
      sessionDao.observeSession(sessionId),
      sessionDao.observeSamplesForSession(sessionId),
      sessionDao.observeEventsForSession(sessionId),
    ) { sessionEntity, sampleEntities, eventEntities ->
      sessionEntity?.let {
        FullBrewSessionRecord(
          session = it.toDomain(),
          samples = sampleEntities.map { s -> s.toDomain() },
          events = eventEntities.map { e -> e.toDomain() },
        )
      }
    }
  }

  suspend fun getSessionDetail(sessionId: String): FullBrewSessionRecord? {
    val session = sessionDao.getSessionById(sessionId)?.toDomain() ?: return null
    val samples = sessionDao.getSamplesForSession(sessionId).map { it.toDomain() }
    val events = sessionDao.getEventsForSession(sessionId).map { it.toDomain() }
    return FullBrewSessionRecord(session = session, samples = samples, events = events)
  }

  suspend fun updateSessionNotes(sessionId: String, notes: String) {
    sessionDao.updateNotes(sessionId, notes)
  }

  suspend fun deleteSession(sessionId: String) {
    sessionDao.deleteSessionById(sessionId)
  }
}
