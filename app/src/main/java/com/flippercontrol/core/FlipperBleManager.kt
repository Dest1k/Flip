package com.flippercontrol.core

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ─── Flipper Zero BLE UUIDs ───────────────────────────────────────────────────

object FlipperUuids {
    // Serial Port Profile service
    val SERVICE       = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
    val CHAR_TX       = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000") // phone → flipper
    val CHAR_RX       = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e63fe0000") // flipper → phone
    val CHAR_RX_FLOW  = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e64fe0000") // flow control
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

    private var activeScanner: BluetoothLeScanner? = null
    private var activeScanCallback: ScanCallback? = null

    val incomingData = Channel<ByteArray>(Channel.UNLIMITED)

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

        // Сначала проверяем уже сопряжённые устройства — Android не возвращает
        // bonded-устройства в BLE scan results, это обход этого ограничения
        val bonded = adapter.bondedDevices?.firstOrNull { isFlipperName(it.name) }
        if (bonded != null) {
            log("Найдено в сопряжённых: ${bonded.name}")
            log("MAC: ${bonded.address}")
            _state.value = BleState.Disconnected // сбросим перед connect()
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
                log("Ошибка согласования MTU: $status")
                _state.value = BleState.Error("MTU negotiation failed: $status")
                return
            }
            if (discoverServicesStarted) return
            discoverServicesStarted = true
            log("MTU: $mtu байт. Ищу сервисы...")
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

            log("Serial Service найден. Подписываюсь на RX...")
            service.characteristics.forEach {
                log("  char: ${it.uuid}  props: ${it.properties}")
            }

            txChar = service.getCharacteristic(FlipperUuids.CHAR_TX)
                ?: service.characteristics.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }

            val rxChar = service.getCharacteristic(FlipperUuids.CHAR_RX)
                ?: service.characteristics.firstOrNull {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                } ?: run {
                log("Ошибка: RX характеристика не найдена")
                return
            }
            gatt.setCharacteristicNotification(rxChar, true)
            rxChar.getDescriptor(FlipperUuids.DESCRIPTOR_NOTIFY)?.let { desc ->
                log("Записываю CCCD дескриптор...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(desc)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == FlipperUuids.DESCRIPTOR_NOTIFY) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("Нотификации включены. Готово!")
                    val deviceName = gatt.device.name ?: "Flipper Zero"
                    _state.value = BleState.Connected(gatt.device, deviceName)
                } else {
                    log("Ошибка включения нотификаций: $status")
                    _state.value = BleState.Error("Не удалось включить нотификации: $status")
                }
            }
        }

        @Deprecated("Needed for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val v = characteristic.value.clone()
            log("RX ${v.size}b: ${v.take(8).joinToString(" ") { "%02X".format(it) }}")
            scope.launch { incomingData.send(v) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val v = value.clone()
            log("RX ${v.size}b: ${v.take(8).joinToString(" ") { "%02X".format(it) }}")
            scope.launch { incomingData.send(v) }
        }
    }

    // ─── Send data ─────────────────────────────────────────────────────────────

    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun send(data: ByteArray) {
        val char = txChar ?: return
        val g = gatt ?: return

        writeMutex.withLock {
            val chunkSize = 200
            for (chunk in data.toList().chunked(chunkSize)) {
                val chunkBytes = chunk.toByteArray()
                withContext(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(char, chunkBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    } else {
                        @Suppress("DEPRECATION")
                        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        @Suppress("DEPRECATION")
                        char.value = chunkBytes
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(char)
                    }
                }
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
    }
}
