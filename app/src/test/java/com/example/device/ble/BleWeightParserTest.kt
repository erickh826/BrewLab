package com.example.device.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleWeightParserTest {
  @Test
  fun parsesFiniteWeightsWithoutRounding() {
    assertEquals(0.2, parse("0.2")!!, 0.0)
    assertEquals(102.0, parse("102.0")!!, 0.0)
    assertEquals(0.0, parse("0")!!, 0.0)
    assertEquals(12.3456, parse(" 12.3456\r\n")!!, 0.0)
  }

  @Test
  fun preservesNegativeWeights() {
    assertEquals(-0.1, parse("-0.1")!!, 0.0)
  }

  @Test
  fun rejectsMalformedAndNonFinitePayloads() {
    val invalid = listOf(
      "", " ", "102 g", "0,2", """{"weight":1}""", "0.2\n-0.1", "12.3\u0000",
      "NaN", "Infinity", "-Infinity", "1e309", "0x1p0", "1.0f",
    )
    for (payload in invalid) {
      assertNull("Payload: $payload", parse(payload))
    }
    assertNull(BleWeightParser.parseWeightGrams(byteArrayOf(0x31, 0xC3.toByte(), 0x28)))
  }

  private fun parse(text: String): Double? = BleWeightParser.parseWeightGrams(text.encodeToByteArray())
}
