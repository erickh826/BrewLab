package com.example.device.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.BrewLabApplication
import com.example.brew.BrewRecordingCoordinator
import com.example.device.ScaleCommand
import com.example.device.ScaleDeviceManager
import com.example.device.ScaleTareResult
import com.example.domain.model.SourceType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowBluetoothGatt
import org.robolectric.shadows.ShadowBluetoothLeScanner
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BleScaleDataSourceTareTest {
  @Test
  fun olderFirmwareStillReceivesWeightWithoutTare() = runBlocking {
    val fixture = connect(withCommand = false)
    val sample = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.samples.first() }
    fixture.notify(fixture.weight, "-0.1")

    assertTrue(fixture.source.connectionState.value.isConnected)
    assertEquals(-0.1, withTimeout(1_000L) { sample.await() }.rawWeightG, 0.0)
    assertFalse(ScaleCommand.TARE in fixture.source.supportedCommands.value)
    assertEquals(ScaleTareResult.UNSUPPORTED, fixture.source.tare())
    fixture.source.disconnect()
  }

  @Test
  @Suppress("DEPRECATION") // The GATT shadow records the written CCCD bytes on the descriptor.
  fun commandCccdEnablesTareAndWriteSuccessStillWaitsForAck() = runBlocking {
    val fixture = connect(withCommand = true)
    val command = requireNotNull(fixture.command)
    assertArrayEquals(
      BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
      command.descriptors.single().value,
    )
    fixture.notify(fixture.weight, "14.2")
    assertTrue(ScaleCommand.TARE in fixture.source.supportedCommands.value)

    val result = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.tare() }
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("TARE:1", fixture.shadow.latestWrittenBytes.decodeToString())
    assertFalse(result.isCompleted)
    fixture.notify(command, "OK:1")
    assertEquals(ScaleTareResult.SUCCESS, result.await())
    fixture.source.disconnect()
    assertFalse(ScaleCommand.TARE in fixture.source.supportedCommands.value)
  }

  @Test
  fun coordinatorClearsTareSuccessOnFailureAndSourceSwitch() = runBlocking {
    val app = ApplicationProvider.getApplicationContext<BrewLabApplication>()
    val fixture = connect(withCommand = true)
    val command = requireNotNull(fixture.command)
    val manager = ScaleDeviceManager(bleDataSource = fixture.source)
    val coordinator = BrewRecordingCoordinator(app.repository, manager, app.preferences)
    manager.selectSourceType(SourceType.BLE_HARDWARE)
    val main = shadowOf(Looper.getMainLooper())
    main.idle()
    fixture.notify(fixture.weight, "14.2")

    val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.performTareBeforeStart() }
    main.idle()
    assertTrue(coordinator.state.value.isTaring)
    assertFalse(coordinator.state.value.hasTaredBeforeStart)
    fixture.notify(command, "OK:1")
    first.await()
    assertTrue(coordinator.state.value.hasTaredBeforeStart)
    assertEquals(14.2, requireNotNull(manager.latestRawReadingG.value), 0.0)

    val failed = async(start = CoroutineStart.UNDISPATCHED) { coordinator.performTareBeforeStart() }
    main.idle()
    fixture.notify(command, "ERR:2:TIMEOUT")
    failed.await()
    assertFalse(coordinator.state.value.hasTaredBeforeStart)
    assertEquals(ScaleTareResult.DEVICE_TIMEOUT.errorLabel, coordinator.state.value.tareError)

    val retry = async(start = CoroutineStart.UNDISPATCHED) { coordinator.performTareBeforeStart() }
    main.idle()
    fixture.notify(command, "OK:3")
    retry.await()
    assertTrue(coordinator.state.value.hasTaredBeforeStart)
    manager.selectSourceType(SourceType.DEMO)
    main.idle()
    assertFalse(coordinator.state.value.hasTaredBeforeStart)
  }

  private suspend fun connect(withCommand: Boolean): Fixture {
    val context = ApplicationProvider.getApplicationContext<Application>()
    shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    @Suppress("DEPRECATION")
    adapter.enable()
    val device = adapter.getRemoteDevice(ADDRESS)
    val source = BleScaleDataSource(context)
    val main = shadowOf(Looper.getMainLooper())

    source.connect()
    val scanner: ShadowBluetoothLeScanner = Shadow.extract(adapter.bluetoothLeScanner)
    val advertisedService = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
      .put(17.toByte()).put(7.toByte())
      .putLong(SERVICE_UUID.leastSignificantBits).putLong(SERVICE_UUID.mostSignificantBits).array()
    val record = ReflectionHelpers.callStaticMethod<ScanRecord>(
      ScanRecord::class.java,
      "parseFromBytes",
      ClassParameter.from(ByteArray::class.java, advertisedService),
    )
    @Suppress("DEPRECATION")
    val result = ScanResult(device, record, -40, 0)
    scanner.scanCallbacks.single().onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
    main.idle()
    source.selectDevice(ADDRESS)

    val deviceShadow: ShadowBluetoothDevice = Shadow.extract(device)
    val gatt = deviceShadow.bluetoothGatts.single()
    val gattShadow: ShadowBluetoothGatt = Shadow.extract(gatt)
    val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    val weight = characteristic(WEIGHT_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
    service.addCharacteristic(weight)
    gattShadow.allowCharacteristicNotification(weight)
    val command = if (withCommand) {
      characteristic(COMMAND_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE).also {
        service.addCharacteristic(it)
        gattShadow.allowCharacteristicNotification(it)
      }
    } else null
    gattShadow.addDiscoverableService(service)
    assertFalse(ScaleCommand.TARE in source.supportedCommands.value)
    gattShadow.notifyConnection(ADDRESS)
    main.idle()
    return Fixture(source, gatt, gattShadow, weight, command)
  }

  private fun characteristic(uuid: UUID, properties: Int) = BluetoothGattCharacteristic(
    uuid, properties or BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0,
  ).apply {
    addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE))
  }

  private data class Fixture(
    val source: BleScaleDataSource,
    val gatt: BluetoothGatt,
    val shadow: ShadowBluetoothGatt,
    val weight: BluetoothGattCharacteristic,
    val command: BluetoothGattCharacteristic?,
  ) {
    fun notify(characteristic: BluetoothGattCharacteristic, value: String) {
      shadow.gattCallback.onCharacteristicChanged(gatt, characteristic, value.encodeToByteArray())
      shadowOf(Looper.getMainLooper()).idle()
    }
  }

  private companion object {
    const val ADDRESS = "01:23:45:67:89:AB"
    val SERVICE_UUID: UUID = UUID.fromString("b1e00001-8a20-4c8f-a45e-6f12e3d45c90")
    val WEIGHT_UUID: UUID = UUID.fromString("b1e00002-8a20-4c8f-a45e-6f12e3d45c90")
    val COMMAND_UUID: UUID = UUID.fromString("b1e00003-8a20-4c8f-a45e-6f12e3d45c90")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
  }
}
