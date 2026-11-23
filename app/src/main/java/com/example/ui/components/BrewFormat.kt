package com.example.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BrewFormat {
  fun formatMmSs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", min, sec)
  }

  fun formatSecAsMmSs(seconds: Int): String = formatMmSs(seconds.coerceAtLeast(0) * 1000L)

  fun formatGrams(value: Double?, decimals: Int = 1): String {
    if (value == null || value.isNaN() || value.isInfinite()) return "—"
    return String.format(Locale.US, "%.${decimals}f g", value)
  }

  fun formatGramsNumber(value: Double?, decimals: Int = 1): String {
    if (value == null || value.isNaN() || value.isInfinite()) return "—"
    return String.format(Locale.US, "%.${decimals}f", value)
  }

  fun formatSignedDeltaGrams(value: Double?): String {
    if (value == null || value.isNaN() || value.isInfinite()) return "—"
    return String.format(Locale.US, "%+.1f g", value)
  }

  fun formatRate(value: Double?): String {
    if (value == null || value.isNaN() || value.isInfinite()) return "—"
    return String.format(Locale.US, "%.1f g/s", value)
  }

  fun formatRateNumber(value: Double?): String {
    if (value == null || value.isNaN() || value.isInfinite()) return "—"
    return String.format(Locale.US, "%.1f", value)
  }

  fun formatHz(hz: Double?): String {
    if (hz == null || hz.isNaN() || hz.isInfinite()) return "—"
    return String.format(Locale.US, "%.1f Hz", hz)
  }

  fun formatDateTime(epochMs: Long): String {
    val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TRADITIONAL_CHINESE)
    return fmt.format(Date(epochMs))
  }

  fun formatTimeOnly(epochMs: Long): String {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    return fmt.format(Date(epochMs))
  }
}
