package com.example.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {
  @Query("SELECT * FROM recipes ORDER BY updatedAtEpochMs DESC")
  fun observeAll(): Flow<List<RecipeEntity>>

  @Query("SELECT COUNT(*) FROM recipes")
  suspend fun count(): Int

  @Query("SELECT * FROM recipes WHERE id = :id")
  suspend fun getById(id: String): RecipeEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: RecipeEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(entities: List<RecipeEntity>)

  @Query("DELETE FROM recipes WHERE id = :id")
  suspend fun deleteById(id: String)
}

@Dao
interface SessionDao {
  @Query("SELECT * FROM brew_sessions ORDER BY startedAtEpochMs DESC")
  fun observeAllSessions(): Flow<List<BrewSessionEntity>>

  @Query("SELECT * FROM brew_sessions WHERE id = :id")
  fun observeSession(id: String): Flow<BrewSessionEntity?>

  @Query("SELECT * FROM brew_sessions WHERE id = :id")
  suspend fun getSessionById(id: String): BrewSessionEntity?

  @Query("SELECT COUNT(*) FROM brew_sessions")
  suspend fun countSessions(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSession(session: BrewSessionEntity)

  @Query("UPDATE brew_sessions SET notes = :notes WHERE id = :sessionId")
  suspend fun updateNotes(sessionId: String, notes: String)

  @Query("DELETE FROM brew_sessions WHERE id = :sessionId")
  suspend fun deleteSessionById(sessionId: String)

  @Query("SELECT * FROM scale_samples WHERE sessionId = :sessionId ORDER BY monotonicReceiveMs ASC")
  suspend fun getSamplesForSession(sessionId: String): List<ScaleSampleEntity>

  @Query("SELECT * FROM scale_samples WHERE sessionId = :sessionId ORDER BY monotonicReceiveMs ASC")
  fun observeSamplesForSession(sessionId: String): Flow<List<ScaleSampleEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSamples(samples: List<ScaleSampleEntity>)

  @Query("SELECT * FROM brew_events WHERE sessionId = :sessionId ORDER BY elapsedMs ASC")
  suspend fun getEventsForSession(sessionId: String): List<BrewEventEntity>

  @Query("SELECT * FROM brew_events WHERE sessionId = :sessionId ORDER BY elapsedMs ASC")
  fun observeEventsForSession(sessionId: String): Flow<List<BrewEventEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertEvents(events: List<BrewEventEntity>)

  @Transaction
  suspend fun insertFullSession(
    session: BrewSessionEntity,
    samples: List<ScaleSampleEntity>,
    events: List<BrewEventEntity>,
  ) {
    insertSession(session)
    if (samples.isNotEmpty()) insertSamples(samples)
    if (events.isNotEmpty()) insertEvents(events)
  }
}

@Database(
  entities = [
    RecipeEntity::class,
    BrewSessionEntity::class,
    ScaleSampleEntity::class,
    BrewEventEntity::class,
  ],
  version = 1,
  exportSchema = false,
)
abstract class BrewLabDatabase : RoomDatabase() {
  abstract fun recipeDao(): RecipeDao
  abstract fun sessionDao(): SessionDao
}
