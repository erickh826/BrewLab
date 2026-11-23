package com.example.data

import com.example.domain.model.AlgorithmConfig
import com.example.domain.model.RecipeSnapshot
import com.example.domain.model.RecipeStep
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object BrewJsonCodec {
  val moshi: Moshi = Moshi.Builder()
    .addLast(KotlinJsonAdapterFactory())
    .build()

  private val stepListType = Types.newParameterizedType(List::class.java, RecipeStep::class.java)
  private val stepListAdapter = moshi.adapter<List<RecipeStep>>(stepListType)
  private val snapshotAdapter = moshi.adapter(RecipeSnapshot::class.java)
  private val algorithmAdapter = moshi.adapter(AlgorithmConfig::class.java)

  fun encodeSteps(steps: List<RecipeStep>): String = stepListAdapter.toJson(steps)

  fun decodeSteps(json: String): List<RecipeStep> = stepListAdapter.fromJson(json) ?: emptyList()

  fun encodeSnapshot(snapshot: RecipeSnapshot): String = snapshotAdapter.toJson(snapshot)

  fun decodeSnapshot(json: String): RecipeSnapshot =
    snapshotAdapter.fromJson(json)
      ?: throw IllegalStateException("Failed to decode RecipeSnapshot")

  fun encodeAlgorithmConfig(config: AlgorithmConfig): String = algorithmAdapter.toJson(config)

  fun decodeAlgorithmConfig(json: String): AlgorithmConfig =
    algorithmAdapter.fromJson(json) ?: AlgorithmConfig()
}
