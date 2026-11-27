package com.example.device.ble

import com.example.device.ScaleTareResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** One command channel per GATT connection, used on the same dispatcher as its callbacks. */
internal class BleTareSession {
  private class Request(val id: Int) {
    val result = CompletableDeferred<ScaleTareResult>()
    var writeConfirmed = false
    var reply: ScaleTareResult? = null
  }

  private var nextId = 1
  private var pending: Request? = null
  private var writeInFlight: Request? = null
  private var closed = false

  suspend fun tare(write: (ByteArray) -> Boolean): ScaleTareResult {
    if (closed) return ScaleTareResult.DISCONNECTED
    if (pending != null) return ScaleTareResult.BUSY
    if (writeInFlight != null) return ScaleTareResult.RECONNECT_REQUIRED
    // IDs are never reused on this connection, even when a request times out.
    if (nextId > MAX_REQUEST_ID) return ScaleTareResult.RECONNECT_REQUIRED
    val request = Request(nextId++)
    pending = request
    writeInFlight = request
    try {
      if (!write("TARE:${request.id}".encodeToByteArray())) {
        writeInFlight = null
        return ScaleTareResult.WRITE_FAILED
      }
      return withTimeoutOrNull(TARE_TIMEOUT_MS) { request.result.await() } ?: ScaleTareResult.TIMEOUT
    } finally {
      if (pending === request) pending = null
    }
  }

  fun onWriteResult(success: Boolean) {
    // A write callback carries no ID. Drain it before accepting another write, even after cancellation.
    val request = writeInFlight ?: return
    writeInFlight = null
    if (pending !== request) return
    if (!success) {
      request.result.complete(ScaleTareResult.WRITE_FAILED)
      return
    }
    request.writeConfirmed = true
    request.reply?.let { request.result.complete(it) }
  }

  fun onReply(payload: ByteArray) {
    val request = pending ?: return
    if (request.reply != null || request.result.isCompleted) return
    val match = RESPONSE.matchEntire(payload.decodeToString()) ?: return
    val id = match.groupValues[1].ifEmpty { match.groupValues[2] }.toInt()
    if (id != request.id) return
    val result = when (match.groupValues[3]) {
      "" -> ScaleTareResult.SUCCESS
      "TIMEOUT" -> ScaleTareResult.DEVICE_TIMEOUT
      "NOT_READY" -> ScaleTareResult.NOT_READY
      "BUSY" -> ScaleTareResult.BUSY
      else -> ScaleTareResult.DEVICE_ERROR
    }
    request.reply = result
    if (request.writeConfirmed) request.result.complete(result)
  }

  fun close() {
    closed = true
    val request = pending
    pending = null
    writeInFlight = null
    request?.result?.complete(ScaleTareResult.DISCONNECTED)
  }

  private companion object {
    const val MAX_REQUEST_ID = 65535
    const val TARE_TIMEOUT_MS = 8_000L
    val RESPONSE = Regex("""(?:OK:([0-9]{1,5})|ERR:([0-9]{1,5}):([A-Z][A-Z0-9_]*))""")
  }
}
