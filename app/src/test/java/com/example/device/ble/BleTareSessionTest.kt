package com.example.device.ble

import com.example.device.ScaleTareResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleTareSessionTest {
  @Test
  fun writeSuccessWaitsForMatchingAck() = runTest {
    val session = BleTareSession()
    val result = async(start = CoroutineStart.UNDISPATCHED) {
      session.tare {
        assertEquals("TARE:1", it.decodeToString())
        true
      }
    }
    session.onWriteResult(true)
    runCurrent()
    assertFalse(result.isCompleted)

    session.onReply("OK:2".encodeToByteArray())
    session.onReply("ERR:65536:BUSY".encodeToByteArray())
    session.onReply("OK:1\n".encodeToByteArray())
    runCurrent()
    assertFalse(result.isCompleted)

    session.onReply("OK:1".encodeToByteArray())
    assertEquals(ScaleTareResult.SUCCESS, result.await())
  }

  @Test
  fun earlyAckStillWaitsForWriteCallback() = runTest {
    val session = BleTareSession()
    val result = async(start = CoroutineStart.UNDISPATCHED) { session.tare { true } }
    session.onReply("OK:1".encodeToByteArray())
    runCurrent()
    assertFalse(result.isCompleted)

    session.onWriteResult(true)
    assertEquals(ScaleTareResult.SUCCESS, result.await())
  }

  @Test
  fun reportsFirmwareErrors() = runTest {
    val session = BleTareSession()
    val errors = listOf(
      "TIMEOUT" to ScaleTareResult.DEVICE_TIMEOUT,
      "NOT_READY" to ScaleTareResult.NOT_READY,
      "BUSY" to ScaleTareResult.BUSY,
    )
    for ((index, pair) in errors.withIndex()) {
      val (code, expected) = pair
      val result = async(start = CoroutineStart.UNDISPATCHED) { session.tare { true } }
      session.onWriteResult(true)
      session.onReply("ERR:${index + 1}:$code".encodeToByteArray())
      assertEquals(expected, result.await())
    }
  }

  @Test
  fun timeoutDiscardsLateAckWhileNextRequestWaits() = runTest {
    val session = BleTareSession()
    val first = async(start = CoroutineStart.UNDISPATCHED) { session.tare { true } }
    session.onWriteResult(true)
    advanceTimeBy(7_999L)
    assertFalse(first.isCompleted)
    advanceTimeBy(1L)
    assertEquals(ScaleTareResult.TIMEOUT, first.await())

    val next = async(start = CoroutineStart.UNDISPATCHED) {
      session.tare {
        assertEquals("TARE:2", it.decodeToString())
        true
      }
    }
    session.onWriteResult(true)
    session.onReply("OK:1".encodeToByteArray())
    runCurrent()
    assertFalse(next.isCompleted)
    session.onReply("OK:2".encodeToByteArray())
    assertEquals(ScaleTareResult.SUCCESS, next.await())
  }

  @Test
  fun disconnectFinishesTheRequestAndOldAckCannotReachNewConnection() = runTest {
    val old = BleTareSession()
    val oldResult = async(start = CoroutineStart.UNDISPATCHED) { old.tare { true } }
    old.onWriteResult(true)
    old.close()
    assertEquals(ScaleTareResult.DISCONNECTED, oldResult.await())
    assertEquals(ScaleTareResult.DISCONNECTED, old.tare { error("closed session wrote a command") })

    val current = BleTareSession()
    val currentResult = async(start = CoroutineStart.UNDISPATCHED) { current.tare { true } }
    current.onWriteResult(true)
    old.onReply("OK:1".encodeToByteArray())
    runCurrent()
    assertFalse(currentResult.isCompleted)
    current.onReply("OK:1".encodeToByteArray())
    assertEquals(ScaleTareResult.SUCCESS, currentResult.await())
  }

  @Test
  fun cancellationDrainsOriginalWriteCallbackBeforeRetry() = runTest {
    val session = BleTareSession()
    val first = async(start = CoroutineStart.UNDISPATCHED) { session.tare { true } }
    assertEquals(ScaleTareResult.BUSY, session.tare { error("wrote a second command") })
    first.cancelAndJoin()
    assertEquals(ScaleTareResult.RECONNECT_REQUIRED, session.tare { error("original write is still in flight") })
    session.onWriteResult(true)

    val next = async(start = CoroutineStart.UNDISPATCHED) { session.tare { true } }
    session.onReply("OK:1".encodeToByteArray())
    session.onWriteResult(false)
    assertEquals(ScaleTareResult.WRITE_FAILED, next.await())
  }
}
