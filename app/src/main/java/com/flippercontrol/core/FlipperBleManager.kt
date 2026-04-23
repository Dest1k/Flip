package com.flippercontrol.core

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ─── Flipper Zero BLE UUIDs ───────────────────────────────────────────────────

object FlipperUuids {
    val SERVICE           = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
    val CHAR_RX           = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000") // flipper → phone (notify, RPC data)
    val CHAR_TX           = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000") // phone → flipper (write)
    val CHAR_OVERFLOW     = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e63fe0000") // overflow / flow control (notify)
    val CHAR_RESET        = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e64fe0000") // session reset (write null byte)
    val DESCRIPTOR_NOTIFY = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

// ─── Connection state ─────────────────────────────────────────────────────────

sealed class BleState {
    object Disconnected : BleState()
    object Scanning     : BleState()
    data class Connecting(val device: BluetoothDevice) : BleState()
    data class Connected(val device: BluetoothDevice, val name: String) : BleState()
    data class Error(val message: String) : BleState()
}

// ─── Manager ──────────────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
class FlipperBleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)!!
    private val adapter: BluetoothAdapter = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    @Volatile private var discoverServicesStarted = false
    @Volatile private var negotiatedMtu = 23  // conservative default until onMtuChanged fires

    private var activeScanner: BluetoothLeScanner? = null
    private var activeScanCallback: ScanCallback? = null

    private val _incomingData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val incomingData: SharedFlow<ByteArray> = _incomingData

    private val _state = MutableStateFlow<BleState>(BleState.Disconnected)
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    val connectionLog: StateFlow<List<String>> = _connectionLog.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun log(msg: String) {
        val time = timeFmt.format(Date())
        _connectionLog.value = _connectionLog.value + "[$time] $msg"
    }

    fun logPublic(msg: String) = log(msg)

    // ─── Scan ──────────────────────────────────────────────────────────────────

    private val targetNames = listOf("Flipper", "Ericose")
    private fun isFlipperName(name: String?) =
        name != null && targetNames.any { name.contains(it, ignoreCase = true) }

    private var scanTimeoutJob: Job? = null

    fun startScan(onFound: (BluetoothDevice, String) -> Unit) {
        _connectionLog.value = emptyList()
        _state.value = BleState.Scanning
        log("Начинаю поиск устройства")

        val bonded = adapter.bondedDevices?.firstOrNull { isFlipperName(it.name) }
        if (bonded != null) {
            log("Найдено в сопряжённых: ${bonded.name}")
            log("MAC: ${bonded.address}")
            _state.value = BleState.Disconnected
            onFound(bonded, bonded.name ?: "Flipper Zero")
            return
        }

        log("В сопряжённых не найдено, запускаю BLE сканирование")
        log("Имена: ${targetNames.joinToString(", ")}")

        val scanner = adapter.bluetoothLeScanner ?: run {
            log("Ошибка: BLE адаптер недоступен")
            _state.value = BleState.Error("BLE недоступен")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (!isFlipperName(name)) return
                log("Найдено: $name")
                log("MAC: ${result.device.address}  RSSI: ${result.rssi} dBm")
                val bonded = result.device.bondState == BluetoothDevice.BOND_BONDED
                log("Сопряжение: ${if (bonded) "да" else "нет"}")
                scanTimeoutJob?.cancel()
                scanner.stopScan(this)
                activeScanner = null
                activeScanCallback = null
                onFound(result.device, name)
            }
            override fun onScanFailed(errorCode: Int) {
                log("Ошибка сканирования: код $errorCode")
                _state.value = BleState.Error("Scan error: $errorCode")
                activeScanner = null
                activeScanCallback = null
            }
        }

        activeScanCallback = callback
        activeScanner = scanner
        scanner.startScan(null, settings, callback)
        log("Сканирование запущено (таймаут 30 с)...")

        scanTimeoutJob = scope.launch {
            delay(30_000)
            if (_state.value is BleState.Scanning) {
                log("Устройство не найдено — убедись что BLE включён")
                scanner.stopScan(callback)
                activeScanner = null
                activeScanCallback = null
                _state.value = BleState.Error("Устройство не найдено. BLE включён?")
            }
        }
    }

    // ─── Connect ───────────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice) {
        log("Подключаюсь к ${device.address}...")
        _state.value = BleState.Connecting(device)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ─── Cancel ────────────────────────────────────────────────────────────────

    fun cancelConnect() {
        log("Отменено пользователем")
        scanTimeoutJob?.cancel()
        activeScanCallback?.let { activeScanner?.stopScan(it) }
        activeScanner = null
        activeScanCallback = null
        gatt?.disconnect()
        _state.value = BleState.Disconnected
    }

    // ─── GATT Callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Ошибка GATT: status=$status")
                _state.value = BleState.Error("Connection failed (status $status)")
                cleanup()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("GATT подключён. Запрашиваю MTU 512...")
                    discoverServicesStarted = false
                    negotiatedMtu = 23
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Соединение разорвано")
                    _state.value = BleState.Disconnected
                    cleanup()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Ошибка MTU (status=$status), продолжаю с MTU=23")
                // Don't fail — just use conservative chunk size and discover services
            } else {
                negotiatedMtu = mtu
                log("MTU: $mtu байт (payload=${mtu - 3}b/пакет)")
            }
            if (discoverServicesStarted) return
            discoverServicesStarted = true
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Ошибка поиска сервисов: $status")
                _state.value = BleState.Error("Services discovery failed: $status")
                return
            }

            log("Найдено сервисов: ${gatt.services.size}")

            val service = gatt.getService(FlipperUuids.SERVICE) ?: run {
                log("Ошибка: Serial Service не найден. Это Flipper Zero?")
                _state.value = BleState.Error("Flipper service не найден. Это Flipper Zero?")
                return
            }

            log("Serial Service найден")
            service.characteristics.forEach {
                log("  char: ${it.uuid}  props: ${it.properties}")
            }

            txChar = service.getCharacteristic(FlipperUuids.CHAR_TX)
                ?: service.characteristics.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }
            log("TX char: ${txChar?.uuid ?: "не найден!"}")

            // Step 1: subscribe to overflow/flow-control channel (63fe).
            // Required before the data channel — Flipper uses this to manage send throttling.
            val flowChar = service.getCharacteristic(FlipperUuids.CHAR_OVERFLOW)
            if (flowChar != null && flowChar.getDescriptor(FlipperUuids.DESCRIPTOR_NOTIFY) != null) {
                log("Подписываюсь на flow-control (63fe)...")
                gatt.setCharacteristicNotification(flowChar, true)
                val desc = flowChar.getDescriptor(FlipperUuids.DESCRIPTOR_NOTIFY)!!
                writeCccd(gatt, desc)
            } else {
                // No flow control char — go straight to data channel
                log("Flow-control char не найден, подписываюсь на данные напрямую")
                subscribeToRxData(gatt, service)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != FlipperUuids.DESCRIPTOR_NOTIFY) return

            val charUuid = descriptor.characteristic.uuid
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Ошибка CCCD ($charUuid): status=$status")
                // Don't abort — try to continue if possible
            }

            when (charUuid) {
                FlipperUuids.CHAR_OVERFLOW -> {
                    // Step 2: overflow subscribed → now subscribe to RPC data channel (61fe)
                    log("Overflow CCCD записан. Подписываюсь на RX данные (61fe)...")
                    val service = gatt.getService(FlipperUuids.SERVICE) ?: run {
                        log("Service не найден в onDescriptorWrite")
                        return
                    }
                    subscribeToRxData(gatt, service)
                }
                FlipperUuids.CHAR_RX -> {
                    // Step 3: data channel subscribed → connection ready
                    log("RX CCCD записан. Соединение готово!")
                    val deviceName = gatt.device.name ?: "Flipper Zero"
                    _state.value = BleState.Connected(gatt.device, deviceName)
                }
                else -> {
                    // Some other descriptor — ignore
                    log("CCCD записан для $charUuid (игнорирую)")
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("TX write error: status=$status char=${characteristic.uuid}")
            }
        }

        @Deprecated("Needed for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val v = characteristic.value?.clone() ?: return
            log("RX [${characteristic.uuid.toString().takeLast(8)}] ${v.size}b: ${v.take(8).joinToString(" ") { "%02X".format(it) }}")
            _incomingData.tryEmit(v)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val v = value.clone()
            log("RX [${characteristic.uuid.toString().takeLast(8)}] ${v.size}b: ${v.take(8).joinToString(" ") { "%02X".format(it) }}")
            _incomingData.tryEmit(v)
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun subscribeToRxData(gatt: BluetoothGatt, service: BluetoothGattService) {
        val rxChar = service.getCharacteristic(FlipperUuids.CHAR_RX)
            ?: service.characteristics.firstOrNull {
                (it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0 &&
                it.uuid != FlipperUuids.CHAR_OVERFLOW
            }
            ?: run {
                log("Ошибка: RX характеристика не найдена")
                _state.value = BleState.Error("RX char не найден")
                return
            }
        val props = rxChar.properties
        log("RX char: ${rxChar.uuid}  props=0x%02X  notify=${props and 0x10 != 0}  indicate=${props and 0x20 != 0}".format(props))
        gatt.setCharacteristicNotification(rxChar, true)
        val desc = rxChar.getDescriptor(FlipperUuids.DESCRIPTOR_NOTIFY) ?: run {
            log("CCCD не найден на RX char — подключение считается готовым")
            _state.value = BleState.Connected(gatt.device, gatt.device.name ?: "Flipper Zero")
            return
        }
        writeCccd(gatt, desc)
    }

    private fun writeCccd(gatt: BluetoothGatt, desc: BluetoothGattDescriptor) {
        val char = desc.characteristic
        // fe61 (RX data) uses INDICATE, not NOTIFY — must write 0x0002, not 0x0001
        val cccdValue = if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE   // {0x02, 0x00}
        else
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE // {0x01, 0x00}
        log("Записываю CCCD для ${char.uuid}: ${if (cccdValue[0] == 0x02.toByte()) "INDICATE" else "NOTIFY"}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(desc, cccdValue)
        } else {
            @Suppress("DEPRECATION")
            desc.value = cccdValue
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(desc)
        }
    }

    // ─── Send data ─────────────────────────────────────────────────────────────

    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun send(data: ByteArray) {
        val char = txChar ?: run { log("TX: txChar is null, cannot send"); return }
        val g = gatt ?: run { log("TX: gatt is null, cannot send"); return }

        // Use negotiated MTU minus ATT overhead (3 bytes), capped at 512
        val chunkSize = (negotiatedMtu - 3).coerceIn(20, 512)

        writeMutex.withLock {
            for (chunk in data.toList().chunked(chunkSize)) {
                val chunkBytes = chunk.toByteArray()
                val result = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(char, chunkBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    } else {
                        @Suppress("DEPRECATION")
                        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        @Suppress("DEPRECATION")
                        char.value = chunkBytes
                        @Suppress("DEPRECATION")
                        if (g.writeCharacteristic(char)) 0 else -1
                    }
                }
                if (result != 0) log("TX write failed: result=$result")
                else log("TX ${chunkBytes.size}b sent")
                delay(20)
            }
        }
    }

    // ─── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        gatt?.disconnect()
    }

    private fun cleanup() {
        gatt?.close()
        gatt = null
        txChar = null
        discoverServicesStarted = false
    }
}
