package com.example.device.ble

internal object BleWeightParser {
  private val decimalNumber = Regex("""[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?""")

  fun parseWeightGrams(payload: ByteArray): Double? {
    val text = payload.decodeToString().trim()
    // The numeric grammar also rejects the replacement character from malformed UTF-8.
    if (!decimalNumber.matches(text)) return null
    return text.toDoubleOrNull()?.takeIf(Double::isFinite)
  }
}
