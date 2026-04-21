package com.flippercontrol.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

// ─── Команды ──────────────────────────────────────────────────────────────────
// Flipper RPC использует protobuf. Здесь — минимальный ручной энкодер/декодер
// для ключевых команд без кодогенерации (удобно для начала).
// Для продакшна: подключи official flipperzero-protobuf .proto и сгенерируй Kotlin.

// Protobuf field numbers из flipperzero-protobuf/flipper.proto
object PbFieldId {
    const val COMMAND_ID     = 1
    const val HAS_NEXT       = 2
    const val COMMAND_STATUS = 3

    // Команды (oneof content)
    const val PING_REQUEST       = 6
    const val PING_RESPONSE      = 7
    const val SYSTEM_DEVICE_INFO = 14
    const val STORAGE_READ       = 205
    const val STORAGE_WRITE      = 206
    const val STORAGE_LIST       = 207
    const val SUBGHZ_START_ASYNC = 400
    const val SUBGHZ_STOP_ASYNC  = 401
    const val SUBGHZ_RAW_RX      = 402
    const val APP_START          = 10
    const val APP_EXIT           = 11
    const val GPIO_SET_PIN       = 901
    const val GPIO_READ_PIN      = 902
    const val IR_TX              = 701
}

// ─── Простой protobuf builder ─────────────────────────────────────────────────

object ProtoWriter {
    fun varint(fieldNumber: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val tag = (fieldNumber shl 3) or 0 // wire type 0 = varint
        writeVarint(out, tag.toLong())
        writeVarint(out, value)
        return out.toByteArray()
    }

    fun bytes(fieldNumber: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val tag = (fieldNumber shl 3) or 2 // wire type 2 = length-delimited
        writeVarint(out, tag.toLong())
        writeVarint(out, data.size.toLong())
        out.write(data)
        return out.toByteArray()
    }

    fun string(fieldNumber: Int, value: String) = bytes(fieldNumber, value.toByteArray())

    fun message(fieldNumber: Int, block: ByteArrayOutputStream.() -> Unit): ByteArray {
        val inner = ByteArrayOutputStream()
        inner.block()
        return bytes(fieldNumber, inner.toByteArray())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7F.toLong().inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

// ─── Простой protobuf reader ──────────────────────────────────────────────────

class ProtoReader(private val data: ByteArray) {
    var pos = 0
        private set

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    fun readTag(): Pair<Int, Int> {
        val tag = readVarint().toInt()
        return Pair(tag ushr 3, tag and 0x07)
    }

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        return data.copyOfRange(pos, pos + len).also { pos += len }
    }

    fun readString() = String(readBytes())
    fun hasMore() = pos < data.size

    // Пропустить поле с известным wire type
    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            2 -> readBytes()
            else -> {} // не поддерживается, пропускаем
        }
    }
}

// ─── Ответ от Flipper ─────────────────────────────────────────────────────────

data class FlipperResponse(
    val commandId: Int,
    val commandStatus: Int,
    val hasNext: Boolean,
    val payload: Map<Int, Any> // fieldId → value (ByteArray или Long)
)

// ─── RPC Session ──────────────────────────────────────────────────────────────

class FlipperRpcSession(private val ble: FlipperBleManager) {

    private val commandCounter = AtomicInteger(1)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Ожидающие ответа команды: commandId → channel
    private val pending = java.util.concurrent.ConcurrentHashMap<Int, Channel<FlipperResponse>>()

    // Публичный поток для "push" событий (SubGHz raw, IR, etc.)
    private val _events = MutableSharedFlow<FlipperResponse>(extraBufferCapacity = 64)
    val events: SharedFlow<FlipperResponse> = _events

    // Аккумулятор входящих байтов (Flipper шлёт куски по MTU)
    private val rxBuffer = ByteArrayOutputStream()

    init {
        scope.launch { processIncoming() }
    }

    // ─── Incoming ───────────────────────────────────────────────────────────────

    private suspend fun processIncoming() {
        val buffer = ByteArrayOutputStream()

        for (chunk in ble.incomingData) {
            buffer.write(chunk)
            tryParseMessages(buffer)
        }
    }

    private suspend fun tryParseMessages(buffer: ByteArrayOutputStream) {
        while (true) {
            val data = buffer.toByteArray()
            if (data.isEmpty()) return

            val reader = ProtoReader(data)
            val msgLen = try { reader.readVarint().toInt() } catch (_: Exception) { return }
            val headerLen = reader.pos // точное число байт, занятых varint-заголовком

            if (data.size < headerLen + msgLen) return

            val msgBytes = data.copyOfRange(headerLen, headerLen + msgLen)
            buffer.reset()
            if (data.size > headerLen + msgLen) {
                buffer.write(data, headerLen + msgLen, data.size - headerLen - msgLen)
            }

            parseAndDispatch(msgBytes)
        }
    }

    private suspend fun parseAndDispatch(msgBytes: ByteArray) {
        var commandId = 0
        var commandStatus = 0
        var hasNext = false
        val payload = mutableMapOf<Int, Any>()

        val reader = ProtoReader(msgBytes)
        while (reader.hasMore()) {
            val (field, wireType) = reader.readTag()
            when (field) {
                PbFieldId.COMMAND_ID     -> commandId = reader.readVarint().toInt()
                PbFieldId.COMMAND_STATUS -> commandStatus = reader.readVarint().toInt()
                PbFieldId.HAS_NEXT       -> hasNext = reader.readVarint() != 0L
                else -> {
                    if (wireType == 2) payload[field] = reader.readBytes()
                    else if (wireType == 0) payload[field] = reader.readVarint()
                    else reader.skip(wireType)
                }
            }
        }

        val response = FlipperResponse(commandId, commandStatus, hasNext, payload)

        // Диспатч
        val ch = pending[commandId]
        if (ch != null) {
            ch.send(response)
            if (!hasNext) pending.remove(commandId)
        } else {
            _events.emit(response) // push-событие (SubGHz raw и т.д.)
        }
    }

    // ─── Send command ────────────────────────────────────────────────────────────

    private suspend fun sendCommand(
        fieldId: Int,
        payload: ByteArray
    ): Pair<Int, Channel<FlipperResponse>> {
        val id = commandCounter.getAndIncrement()
        val ch = Channel<FlipperResponse>(Channel.UNLIMITED)
        pending[id] = ch

        val msg = ByteArrayOutputStream().apply {
            write(ProtoWriter.varint(PbFieldId.COMMAND_ID, id.toLong()))
            write(ProtoWriter.bytes(fieldId, payload))
        }.toByteArray()

        // Framing: varint(length) + msg
        val framed = ByteArrayOutputStream().apply {
            var len = msg.size.toLong()
            while (len and 0x7F.toLong().inv() != 0L) {
                write(((len and 0x7F) or 0x80).toInt())
                len = len ushr 7
            }
            write(len.toInt())
            write(msg)
        }.toByteArray()

        ble.send(framed)
        return Pair(id, ch)
    }

    suspend fun sendAndReceive(
        fieldId: Int,
        payload: ByteArray,
        timeoutMs: Long = 5000L
    ): List<FlipperResponse> {
        val (id, ch) = sendCommand(fieldId, payload)

        val results = mutableListOf<FlipperResponse>()
        try {
            withTimeout(timeoutMs) {
                do {
                    val resp = ch.receive()
                    results.add(resp)
                } while (resp.hasNext)
            }
        } finally {
            pending.remove(id)
        }
        return results
    }

    // ─── High-level API ───────────────────────────────────────────────────────────

    /** Пинг — проверка соединения */
    suspend fun ping(): Boolean = try {
        val r = sendAndReceive(PbFieldId.PING_REQUEST, byteArrayOf(0x01))
        r.isNotEmpty() && r[0].commandStatus == 0
    } catch (_: Exception) { false }

    /** Инфо об устройстве */
    suspend fun deviceInfo(): Map<String, String> {
        val responses = sendAndReceive(PbFieldId.SYSTEM_DEVICE_INFO, byteArrayOf())
        val result = mutableMapOf<String, String>()
        for (resp in responses) {
            // Поле 14 содержит SystemDeviceInfoResponse → key/value пары
            (resp.payload[PbFieldId.SYSTEM_DEVICE_INFO] as? ByteArray)?.let { bytes ->
                val r = ProtoReader(bytes)
                var key = ""; var value = ""
                while (r.hasMore()) {
                    val (f, wt) = r.readTag()
                    when (f) {
                        1 -> key = r.readString()
                        2 -> value = r.readString()
                        else -> r.skip(wt)
                    }
                }
                if (key.isNotEmpty()) result[key] = value
            }
        }
        return result
    }

    /** Список файлов на SD карте */
    suspend fun listStorage(path: String): List<String> {
        val pathBytes = ByteArrayOutputStream().apply {
            write(ProtoWriter.string(1, path)) // StorageListRequest.path
        }.toByteArray()

        val responses = sendAndReceive(PbFieldId.STORAGE_LIST, pathBytes)
        val files = mutableListOf<String>()
        for (resp in responses) {
            (resp.payload[PbFieldId.STORAGE_LIST] as? ByteArray)?.let { bytes ->
                val r = ProtoReader(bytes)
                while (r.hasMore()) {
                    val (f, wt) = r.readTag()
                    if (f == 1) { // StorageListResponse.file
                        val fileBytes = r.readBytes()
                        val fr = ProtoReader(fileBytes)
                        while (fr.hasMore()) {
                            val (ff, fwt) = fr.readTag()
                            if (ff == 2) files.add(fr.readString()) // name
                            else fr.skip(fwt)
                        }
                    } else r.skip(wt)
                }
            }
        }
        return files
    }

    /** Запустить IR передачу */
    suspend fun irTransmit(name: String): Boolean {
        val payload = ByteArrayOutputStream().apply {
            write(ProtoWriter.string(1, name)) // IrTransmitRequest.name
        }.toByteArray()
        val r = sendAndReceive(PbFieldId.IR_TX, payload)
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    /** GPIO: установить пин HIGH/LOW */
    suspend fun gpioSetPin(pinNumber: Int, high: Boolean): Boolean {
        val payload = ByteArrayOutputStream().apply {
            write(ProtoWriter.varint(1, pinNumber.toLong()))
            write(ProtoWriter.varint(2, if (high) 1L else 0L))
        }.toByteArray()
        val r = sendAndReceive(PbFieldId.GPIO_SET_PIN, payload)
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    /** SubGHz: начать асинхронный приём */
    suspend fun subGhzStartReceive(frequency: Long): Boolean {
        val payload = ByteArrayOutputStream().apply {
            write(ProtoWriter.varint(1, frequency))
        }.toByteArray()
        val r = sendAndReceive(PbFieldId.SUBGHZ_START_ASYNC, payload)
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    suspend fun subGhzStopReceive() {
        sendAndReceive(PbFieldId.SUBGHZ_STOP_ASYNC, byteArrayOf())
    }

    fun stop() { scope.cancel() }
}
