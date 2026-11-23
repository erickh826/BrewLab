package com.example.data

import android.content.Context
import com.example.domain.model.SmoothingWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local client preferences (smoothing window and Demo noise amplitude).
 * Simulated device connections are never persisted across app restarts.
 */
class AppPreferences(context: Context) {
  private val prefs = context.getSharedPreferences("brewlab_prefs", Context.MODE_PRIVATE)

  private val _smoothingWindow = MutableStateFlow(
    SmoothingWindow.fromMs(prefs.getLong(KEY_SMOOTHING_MS, SmoothingWindow.ONE_SECOND.windowMs))
  )
  val smoothingWindow: StateFlow<SmoothingWindow> = _smoothingWindow.asStateFlow()

  private val _demoNoiseG = MutableStateFlow(
    prefs.getFloat(KEY_DEMO_NOISE_G, 0.1f).toDouble()
  )
  val demoNoiseG: StateFlow<Double> = _demoNoiseG.asStateFlow()

  private val _selectedRecipeId = MutableStateFlow<String?>(
    prefs.getString(KEY_SELECTED_RECIPE_ID, null)
  )
  val selectedRecipeId: StateFlow<String?> = _selectedRecipeId.asStateFlow()

  fun setSmoothingWindow(window: SmoothingWindow) {
    prefs.edit().putLong(KEY_SMOOTHING_MS, window.windowMs).apply()
    _smoothingWindow.value = window
  }

  fun setDemoNoiseG(noiseG: Double) {
    val clamped = noiseG.coerceIn(0.0, 0.5)
    prefs.edit().putFloat(KEY_DEMO_NOISE_G, clamped.toFloat()).apply()
    _demoNoiseG.value = clamped
  }

  fun setSelectedRecipeId(recipeId: String?) {
    prefs.edit().putString(KEY_SELECTED_RECIPE_ID, recipeId).apply()
    _selectedRecipeId.value = recipeId
  }

  companion object {
    private const val KEY_SMOOTHING_MS = "smoothing_window_ms"
    private const val KEY_DEMO_NOISE_G = "demo_noise_g"
    private const val KEY_SELECTED_RECIPE_ID = "selected_recipe_id"
  }
}
