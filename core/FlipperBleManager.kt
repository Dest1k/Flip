package com.flippercontrol.core

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

// ─── Flipper Zero BLE UUIDs ───────────────────────────────────────────────────

object FlipperUuids {
    // Serial Port Profile service
    val SERVICE       = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
    val CHAR_TX       = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000") // phone → flipper
    val CHAR_RX       = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0001") // flipper → phone
    val CHAR_RX_FLOW  = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0002") // flow control
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

    // Данные от Flipper'а идут сюда
    val incomingData = Channel<ByteArray>(Channel.UNLIMITED)

    private val _state = MutableStateFlow<BleState>(BleState.Disconnected)
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── Scan ──────────────────────────────────────────────────────────────────

    fun startScan(onFound: (BluetoothDevice, String) -> Unit) {
        _state.value = BleState.Scanning

        val scanner = adapter.bluetoothLeScanner ?: run {
            _state.value = BleState.Error("BLE недоступен")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(FlipperUuids.SERVICE))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: "Flipper Zero"
                scanner.stopScan(this)
                onFound(result.device, name)
            }
            override fun onScanFailed(errorCode: Int) {
                _state.value = BleState.Error("Scan error: $errorCode")
            }
        })
    }

    // ─── Connect ───────────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice) {
        _state.value = BleState.Connecting(device)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ─── GATT Callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.requestMtu(512)  // макс MTU для больших пакетов
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = BleState.Disconnected
                    cleanup()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // MTU согласован — теперь запускаем discovery сервисов
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = BleState.Error("Services discovery failed: $status")
                return
            }

            val service = gatt.getService(FlipperUuids.SERVICE) ?: run {
                _state.value = BleState.Error("Flipper service не найден. Это Flipper Zero?")
                return
            }

            // TX: мы пишем сюда
            txChar = service.getCharacteristic(FlipperUuids.CHAR_TX)

            // RX: подписываемся на нотификации
            val rxChar = service.getCharacteristic(FlipperUuids.CHAR_RX) ?: return
            gatt.setCharacteristicNotification(rxChar, true)
            rxChar.getDescriptor(FlipperUuids.DESCRIPTOR_NOTIFY)?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
            // Connected выставляется в onDescriptorWrite после подтверждения нотификаций
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == FlipperUuids.DESCRIPTOR_NOTIFY) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val deviceName = gatt.device.name ?: "Flipper Zero"
                    _state.value = BleState.Connected(gatt.device, deviceName)
                } else {
                    _state.value = BleState.Error("Не удалось включить нотификации: $status")
                }
            }
        }

        @Deprecated("Needed for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == FlipperUuids.CHAR_RX) {
                scope.launch { incomingData.send(characteristic.value.clone()) }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == FlipperUuids.CHAR_RX) {
                scope.launch { incomingData.send(value.clone()) }
            }
        }
    }

    // ─── Send data ─────────────────────────────────────────────────────────────

    // Flipper RPC: данные разбиваются на чанки по MTU
    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun send(data: ByteArray) {
        val char = txChar ?: return
        val g = gatt ?: return

        writeMutex.withLock {
            val chunkSize = 200 // безопасный размер < MTU
            data.toList().chunked(chunkSize).forEach { chunk ->
                char.value = chunk.toByteArray()
                withContext(Dispatchers.Main) {
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(char)
                }
                delay(20) // небольшая пауза между чанками
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
