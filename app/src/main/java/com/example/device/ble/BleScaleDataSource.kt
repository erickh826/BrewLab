package com.example.device.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat
import com.example.device.ScaleCommand
import com.example.device.ScaleConnectionState
import com.example.device.ScaleDataSource
import com.example.device.ScaleDeviceInfo
import com.example.domain.model.ConnectionQualityEvent
import com.example.domain.model.SampleQuality
import com.example.domain.model.ScaleSample
import com.example.domain.model.SourceType
import com.example.domain.model.TimeBasis
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class BleScaleCandidate(val id: String, val name: String)

// Permission is checked before scanning or selecting a device. GATT and cleanup handle revocation.
@SuppressLint("MissingPermission")
class BleScaleDataSource(context: Context) : ScaleDataSource {
  private val appContext = context.applicationContext
  private val handler = Handler(Looper.getMainLooper())

  override val sourceType = SourceType.BLE_HARDWARE
  override val supportedCommands = setOf(ScaleCommand.CONNECT, ScaleCommand.DISCONNECT)

  private val defaultDevice = ScaleDeviceInfo(
    deviceId = "",
    displayName = "實體藍牙電子秤",
    sourceType = sourceType,
    isSimulated = false,
    timeBasis = TimeBasis.HOST_MONOTONIC,
    detailLabel = "ESP32 BLE · 主機單調接收時鐘",
  )
  private val _deviceInfo = MutableStateFlow(defaultDevice)
  override val deviceInfo = _deviceInfo.asStateFlow()
  private val _connectionState = MutableStateFlow<ScaleConnectionState>(ScaleConnectionState.Disconnected)
  override val connectionState = _connectionState.asStateFlow()
  private val _currentStreamEpochId = MutableStateFlow(0)
  override val currentStreamEpochId = _currentStreamEpochId.asStateFlow()
  private val _samples = MutableSharedFlow<ScaleSample>(extraBufferCapacity = 128)
  override val samples = _samples.asSharedFlow()
  private val _qualityEvents = MutableSharedFlow<ConnectionQualityEvent>(extraBufferCapacity = 16)
  override val qualityEvents = _qualityEvents.asSharedFlow()
  private val _candidates = MutableStateFlow<List<BleScaleCandidate>>(emptyList())
  val candidates = _candidates.asStateFlow()
  private val _invalidPayload = MutableStateFlow(false)
  val invalidPayload = _invalidPayload.asStateFlow()

  // Commands, callback processing, and timeouts share the main looper.
  private val foundDevices = linkedMapOf<String, BluetoothDevice>()
  private var scanner: BluetoothLeScanner? = null
  private var scanCallback: ScanCallback? = null
  private var activeGatt: BluetoothGatt? = null
  private var timeout: Runnable? = null
  private var subscribedAtMs = 0L

  override suspend fun connect() = withContext(Dispatchers.Main.immediate) {
    if (_connectionState.value.canDisconnect) return@withContext
    _deviceInfo.value = defaultDevice
    if (missingBleScalePermissions(appContext).isNotEmpty()) {
      fail("請在系統設定允許藍牙掃描所需權限")
      return@withContext
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      val location = appContext.getSystemService(LocationManager::class.java)
      if (location == null || !LocationManagerCompat.isLocationEnabled(location)) {
        fail("請開啟系統位置服務以掃描電子秤")
        return@withContext
      }
    }
    try {
      val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
      if (adapter == null) {
        fail("此裝置沒有藍牙介面")
        return@withContext
      }
      val leScanner = adapter.bluetoothLeScanner
      if (leScanner == null) {
        fail(if (adapter.isEnabled) "此裝置無法進行 BLE 掃描" else "請開啟手機藍牙")
        return@withContext
      }
      startScan(leScanner)
    } catch (_: SecurityException) {
      fail("藍牙掃描權限已撤銷")
    } catch (_: IllegalStateException) {
      fail("藍牙掃描無法啟動，請確認藍牙已開啟")
    }
  }

  private fun startScan(leScanner: BluetoothLeScanner) {
    val callback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        handler.post {
          if (scanCallback !== this) return@post
          val record = result.scanRecord ?: return@post
          if (record.serviceUuids?.contains(SERVICE_PARCEL_UUID) != true) return@post
          val id = result.device.address
          val current = _candidates.value
          val name = record.deviceName ?: current.firstOrNull { it.id == id }?.name ?: "ESP32 電子秤"
          val candidate = BleScaleCandidate(id, name)
          foundDevices[id] = result.device
          if (candidate !in current) {
            _candidates.value = current.filterNot { it.id == id } + candidate
          }
        }
      }

      override fun onScanFailed(errorCode: Int) {
        handler.post {
          if (scanCallback === this) fail("藍牙掃描失敗（$errorCode）")
        }
      }
    }
    scanner = leScanner
    scanCallback = callback
    _connectionState.value = ScaleConnectionState.Scanning
    leScanner.startScan(
      listOf(ScanFilter.Builder().setServiceUuid(SERVICE_PARCEL_UUID).build()),
      ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
      callback,
    )
    scheduleTimeout(10_000L) {
      stopScan()
      if (foundDevices.isEmpty()) {
        fail("找不到電子秤，請確認 ESP32 正在廣播")
      } else {
        _connectionState.value = ScaleConnectionState.SelectingDevice
      }
    }
  }

  suspend fun selectDevice(id: String) = withContext(Dispatchers.Main.immediate) {
    val device = foundDevices[id] ?: return@withContext
    val candidate = _candidates.value.first { it.id == id }
    stopScan()
    clearCandidates()
    if (missingBleScalePermissions(appContext).isNotEmpty()) {
      fail("請在系統設定允許藍牙連線所需權限")
      return@withContext
    }
    _deviceInfo.value = defaultDevice.copy(
      deviceId = candidate.id,
      displayName = candidate.name,
      detailLabel = "${candidate.name} · ESP32 BLE",
    )
    _connectionState.value = ScaleConnectionState.Connecting
    try {
      activeGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
      if (activeGatt == null) {
        fail("無法建立藍牙連線")
      } else {
        scheduleTimeout(15_000L) { fail("藍牙連線或通知設定逾時") }
      }
    } catch (_: SecurityException) {
      fail("藍牙連線權限已撤銷")
    } catch (_: IllegalStateException) {
      fail("藍牙連線無法啟動，請確認藍牙已開啟")
    }
  }

  private val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      onActiveGatt(gatt) {
        val state = _connectionState.value
        if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
          val reason = if (status == BluetoothGatt.GATT_SUCCESS) "電子秤已離線" else "GATT 錯誤（$status）"
          fail(reason, disconnected = state.isConnected || state == ScaleConnectionState.AwaitingSample)
        } else if (newState == BluetoothProfile.STATE_CONNECTED && state == ScaleConnectionState.Connecting) {
          _connectionState.value = ScaleConnectionState.DiscoveringServices
          if (!gatt.discoverServices()) fail("無法啟動重量服務搜尋")
        }
      }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      onActiveGatt(gatt) {
        if (_connectionState.value != ScaleConnectionState.DiscoveringServices) return@onActiveGatt
        if (status == BluetoothGatt.GATT_SUCCESS) {
          subscribeToWeight(gatt)
        } else {
          fail("搜尋重量服務失敗（$status）")
        }
      }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
      val acknowledgedAtMs = SystemClock.elapsedRealtime()
      onActiveGatt(gatt) {
        if (_connectionState.value != ScaleConnectionState.Subscribing ||
          descriptor.uuid != CCCD_UUID || !isWeightCharacteristic(descriptor.characteristic)
        ) return@onActiveGatt
        if (status != BluetoothGatt.GATT_SUCCESS) {
          fail("啟用重量通知失敗（$status）")
          return@onActiveGatt
        }
        subscribedAtMs = acknowledgedAtMs
        _currentStreamEpochId.value += 1
        _connectionState.value = ScaleConnectionState.AwaitingSample
        awaitNextWeight()
      }
    }

    override fun onCharacteristicChanged(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray,
    ) {
      val receivedAtMs = SystemClock.elapsedRealtime()
      onActiveGatt(gatt) {
        if (isWeightCharacteristic(characteristic)) receiveWeight(value, receivedAtMs)
      }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
      val receivedAtMs = SystemClock.elapsedRealtime()
      val payload = characteristic.value?.copyOf() ?: return
      onActiveGatt(gatt) {
        if (isWeightCharacteristic(characteristic)) receiveWeight(payload, receivedAtMs)
      }
    }
  }

  private fun onActiveGatt(gatt: BluetoothGatt, action: () -> Unit) {
    handler.post {
      if (activeGatt !== gatt) return@post
      try {
        action()
      } catch (_: SecurityException) {
        fail("藍牙連線權限已撤銷", disconnected = true)
      }
    }
  }

  @Suppress("DEPRECATION")
  private fun subscribeToWeight(gatt: BluetoothGatt) {
    val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(WEIGHT_UUID)
    val descriptor = characteristic?.getDescriptor(CCCD_UUID)
    if (characteristic == null || descriptor == null ||
      characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
    ) {
      fail("電子秤缺少重量通知特徵或 CCCD")
      return
    }
    _connectionState.value = ScaleConnectionState.Subscribing
    val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    val writeStarted = gatt.setCharacteristicNotification(characteristic, true) &&
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, enable) == BluetoothStatusCodes.SUCCESS
      } else {
        descriptor.setValue(enable) && gatt.writeDescriptor(descriptor)
      }
    if (!writeStarted) fail("無法將重量通知設定寫入電子秤")
  }

  private fun isWeightCharacteristic(characteristic: BluetoothGattCharacteristic) =
    characteristic.service?.uuid == SERVICE_UUID && characteristic.uuid == WEIGHT_UUID

  private fun receiveWeight(payload: ByteArray, receivedAtMs: Long) {
    val state = _connectionState.value
    if (state != ScaleConnectionState.AwaitingSample && !state.isConnected) return
    val weight = BleWeightParser.parseWeightGrams(payload)
    if (weight == null) {
      if (!_invalidPayload.value) report("已略過無效重量通知", "電子秤傳送的內容並非有效數字", true)
      _invalidPayload.value = true
      return
    }
    _invalidPayload.value = false
    if (!state.isConnected) {
      _connectionState.value = ScaleConnectionState.Connected
      report("電子秤已連線", "已收到有效重量通知", false)
    }
    _samples.tryEmit(
      ScaleSample(
        id = UUID.randomUUID().toString(),
        sessionId = "",
        sourceType = sourceType,
        deviceId = _deviceInfo.value.deviceId,
        streamEpochId = _currentStreamEpochId.value,
        rawWeightG = weight,
        deviceTimestampMs = null,
        sequenceNumber = null,
        monotonicReceiveMs = receivedAtMs,
        elapsedMs = (receivedAtMs - subscribedAtMs).coerceAtLeast(0),
        timeBasis = TimeBasis.HOST_MONOTONIC,
        qualityState = SampleQuality.VALID,
      ),
    )
    awaitNextWeight()
  }

  private fun awaitNextWeight() = scheduleTimeout(5_000L) {
    val reason = if (_invalidPayload.value) "持續未收到有效重量通知" else "5 秒未收到重量通知"
    closeTransport()
    _connectionState.value = ScaleConnectionState.Interrupted(reason)
    report("重量接收中斷", reason, true)
  }

  override suspend fun disconnect() = withContext(Dispatchers.Main.immediate) {
    closeTransport()
    _connectionState.value = ScaleConnectionState.Disconnected
    _deviceInfo.value = defaultDevice
  }

  override suspend fun tare() = Unit

  private fun fail(reason: String, disconnected: Boolean = false) {
    closeTransport()
    _connectionState.value = if (disconnected) {
      ScaleConnectionState.DisconnectedWithReason(reason)
    } else {
      ScaleConnectionState.BleFailure(reason)
    }
    report(if (disconnected) "電子秤連線中斷" else "電子秤連線失敗", reason, true)
  }

  private fun closeTransport() {
    timeout?.let(handler::removeCallbacks)
    timeout = null
    stopScan()
    clearCandidates()
    val gatt = activeGatt
    activeGatt = null
    _invalidPayload.value = false
    if (gatt != null) {
      runCatching { gatt.disconnect() }
      runCatching { gatt.close() }
    }
  }

  private fun stopScan() {
    val activeScanner = scanner
    val callback = scanCallback
    scanner = null
    scanCallback = null
    if (activeScanner != null && callback != null) runCatching { activeScanner.stopScan(callback) }
  }

  private fun clearCandidates() {
    foundDevices.clear()
    _candidates.value = emptyList()
  }

  private fun scheduleTimeout(delayMs: Long, action: () -> Unit) {
    timeout?.let(handler::removeCallbacks)
    timeout = Runnable(action).also { handler.postDelayed(it, delayMs) }
  }

  private fun report(title: String, detail: String, warning: Boolean) {
    _qualityEvents.tryEmit(
      ConnectionQualityEvent(UUID.randomUUID().toString(), System.currentTimeMillis(), title, detail, warning),
    )
  }

  private companion object {
    val SERVICE_UUID: UUID = UUID.fromString("b1e00001-8a20-4c8f-a45e-6f12e3d45c90")
    val SERVICE_PARCEL_UUID = ParcelUuid(SERVICE_UUID)
    val WEIGHT_UUID: UUID = UUID.fromString("b1e00002-8a20-4c8f-a45e-6f12e3d45c90")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
  }
}
