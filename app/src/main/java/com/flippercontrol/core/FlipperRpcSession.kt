package com.flippercontrol.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

// ─── Field numbers из flipperzero-protobuf/flipper.proto (master) ────────────

object PbFieldId {
    const val COMMAND_ID     = 1
    const val COMMAND_STATUS = 2  // official proto field 2
    const val HAS_NEXT       = 3  // official proto field 3

    // System — поля 5-46
    const val PING_REQUEST            = 5   // system_ping_request
    const val PING_RESPONSE           = 6   // system_ping_response
    const val DEVICE_INFO_REQUEST     = 32  // system_device_info_request
    const val DEVICE_INFO_RESPONSE    = 33  // system_device_info_response

    // App — поля 16-65
    const val APP_START               = 16  // app_start_request
    const val APP_EXIT                = 47  // app_exit_request

    // Storage — поля 7-15
    const val STORAGE_LIST_REQ        = 7   // storage_list_request
    const val STORAGE_LIST_RESP       = 8   // storage_list_response
    const val STORAGE_READ_REQ        = 9   // storage_read_request
    const val STORAGE_READ_RESP       = 10  // storage_read_response
    const val STORAGE_WRITE_REQ       = 11  // storage_write_request
    const val STORAGE_DELETE_REQ      = 12  // storage_delete_request

    // GPIO — поля 51-57
    const val GPIO_WRITE_PIN          = 57  // gpio_write_pin
    const val GPIO_READ_PIN           = 55  // gpio_read_pin
    const val GPIO_READ_PIN_RESPONSE  = 56  // gpio_read_pin_response
}

// ─── Простой protobuf builder ─────────────────────────────────────────────────

object ProtoWriter {
    fun varint(fieldNumber: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val tag = (fieldNumber shl 3) or 0
        writeVarint(out, tag.toLong())
        writeVarint(out, value)
        return out.toByteArray()
    }

    fun bytes(fieldNumber: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val tag = (fieldNumber shl 3) or 2
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
        if (len < 0 || pos + len > data.size)
            throw IllegalStateException("readBytes: len=$len pos=$pos size=${data.size}")
        return data.copyOfRange(pos, pos + len).also { pos += len }
    }

    fun readString() = String(readBytes())
    fun hasMore() = pos < data.size

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> { if (pos + 8 > data.size) throw IllegalStateException("skip wire1: pos=$pos size=${data.size}"); pos += 8 }
            2 -> readBytes()
            5 -> { if (pos + 4 > data.size) throw IllegalStateException("skip wire5: pos=$pos size=${data.size}"); pos += 4 }
            else -> throw IllegalStateException("Unknown wire type: $wireType")
        }
    }
}

// ─── Типы данных ──────────────────────────────────────────────────────────────

data class FlipperResponse(
    val commandId: Int,
    val commandStatus: Int,
    val hasNext: Boolean,
    val payload: Map<Int, Any>
)

data class FsFile(
    val name: String,
    val isDir: Boolean,
    val size: Long = 0L,
)

// ─── RPC Session ──────────────────────────────────────────────────────────────

class FlipperRpcSession(private val ble: FlipperBleManager) {

    private val commandCounter = AtomicInteger(1)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val pending = java.util.concurrent.ConcurrentHashMap<Int, Channel<FlipperResponse>>()

    private val _events = MutableSharedFlow<FlipperResponse>(extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<FlipperResponse> = _events

    init {
        scope.launch { processIncoming() }
    }

    // ─── Incoming ────────────────────────────────────────────────────────────────

    private suspend fun processIncoming() {
        while (true) {
            val buffer = ByteArrayOutputStream()
            try {
                ble.incomingData.collect { chunk ->
                    buffer.write(chunk)
                    tryParseMessages(buffer)
                }
            } catch (e: CancellationException) {
                throw e  // let scope cancellation propagate
            } catch (e: Exception) {
                ble.logPublic("RPC parser crashed: ${e.message} — restarting")
                delay(100)
            }
        }
    }

    private suspend fun tryParseMessages(buffer: ByteArrayOutputStream) {
        while (true) {
            val data = buffer.toByteArray()
            if (data.isEmpty()) return

            val reader = ProtoReader(data)
            val msgLen = try { reader.readVarint().toInt() } catch (_: Exception) { buffer.reset(); return }
            val headerLen = reader.pos

            if (msgLen <= 0 || msgLen > 1_048_576) {
                ble.logPublic("tryParse: invalid msgLen=$msgLen, discarding buffer")
                buffer.reset()
                return
            }

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
        ble.logPublic("RPC ← id=$commandId status=$commandStatus hasNext=$hasNext fields=${payload.keys.sorted()}")

        val ch = pending[commandId]
        if (ch != null) {
            ch.send(response)
            if (!hasNext) pending.remove(commandId)
        } else {
            _events.tryEmit(response)
        }
    }

    // ─── Send ────────────────────────────────────────────────────────────────────

    private suspend fun sendCommand(
        fieldId: Int,
        payload: ByteArray
    ): Pair<Int, Channel<FlipperResponse>> {
        val id = commandCounter.getAndIncrement()
        val ch = Channel<FlipperResponse>(Channel.UNLIMITED)
        pending[id] = ch
        ble.logPublic("RPC → field=$fieldId id=$id (${payload.size}b)")

        val msg = ByteArrayOutputStream().apply {
            write(ProtoWriter.varint(PbFieldId.COMMAND_ID, id.toLong()))
            write(ProtoWriter.bytes(fieldId, payload))
        }.toByteArray()

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
                while (true) {
                    val resp = ch.receive()
                    results.add(resp)
                    if (!resp.hasNext) break
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ble.logPublic("RPC TIMEOUT id=$id after ${timeoutMs}ms (got ${results.size} resp)")
            throw e
        } finally {
            pending.remove(id)
        }
        return results
    }

    // ─── High-level API ──────────────────────────────────────────────────────────

    private fun storageError(status: Int): String = when (status) {
        1  -> "SD карта не готова (вставлена?)"
        2  -> "Файл уже существует"
        3  -> "Файл не найден"
        4  -> "Неверный параметр"
        5  -> "Доступ запрещён"
        6  -> "Недопустимое имя"
        7  -> "Внутренняя ошибка SD"
        8  -> "Не реализовано"
        9  -> "Файл уже открыт"
        10 -> "Папка не пустая"
        17 -> "Приложение уже запущено"
        else -> "Ошибка $status"
    }

    suspend fun ping(): Boolean = try {
        val r = sendAndReceive(PbFieldId.PING_REQUEST, byteArrayOf())
        r.isNotEmpty() && r[0].commandStatus == 0
    } catch (_: Exception) { false }

    suspend fun deviceInfo(): Map<String, String> {
        val responses = sendAndReceive(PbFieldId.DEVICE_INFO_REQUEST, byteArrayOf(), timeoutMs = 10_000L)
        val result = mutableMapOf<String, String>()
        for (resp in responses) {
            (resp.payload[PbFieldId.DEVICE_INFO_RESPONSE] as? ByteArray)?.let { bytes ->
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

    suspend fun listStorage(path: String): List<FsFile> {
        val payload = ByteArrayOutputStream().apply {
            write(ProtoWriter.string(1, path))
        }.toByteArray()

        val responses = sendAndReceive(PbFieldId.STORAGE_LIST_REQ, payload, timeoutMs = 10_000L)
        if (responses.isEmpty()) throw Exception("Нет ответа от Flipper (таймаут)")
        val files = mutableListOf<FsFile>()
        for (resp in responses) {
            if (resp.commandStatus != 0) throw Exception(storageError(resp.commandStatus))
            (resp.payload[PbFieldId.STORAGE_LIST_RESP] as? ByteArray)?.let { bytes ->
                val r = ProtoReader(bytes)
                while (r.hasMore()) {
                    val (f, wt) = r.readTag()
                    if (f == 1) {
                        val fileBytes = r.readBytes()
                        val fr = ProtoReader(fileBytes)
                        var name = ""; var isDir = false; var size = 0L
                        while (fr.hasMore()) {
                            val (ff, fwt) = fr.readTag()
                            when (ff) {
                                1 -> isDir = fr.readVarint() != 0L  // FileType: 0=FILE 1=DIR
                                2 -> name = fr.readString()
                                3 -> size = fr.readVarint()
                                else -> fr.skip(fwt)
                            }
                        }
                        if (name.isNotEmpty()) files.add(FsFile(name, isDir, size))
                    } else r.skip(wt)
                }
            }
        }
        return files
    }

    suspend fun readFile(path: String): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write(ProtoWriter.string(1, path))
        }.toByteArray()

        val responses = sendAndReceive(PbFieldId.STORAGE_READ_REQ, payload, timeoutMs = 10_000L)
        val result = ByteArrayOutputStream()
        for (resp in responses) {
            (resp.payload[PbFieldId.STORAGE_READ_RESP] as? ByteArray)?.let { bytes ->
                val r = ProtoReader(bytes)
                while (r.hasMore()) {
                    val (f, wt) = r.readTag()
                    if (f == 1) {  // ReadResponse.file (File message)
                        val fileBytes = r.readBytes()
                        val fr = ProtoReader(fileBytes)
                        while (fr.hasMore()) {
                            val (ff, fwt) = fr.readTag()
                            if (ff == 4) result.write(fr.readBytes())  // File.data
                            else fr.skip(fwt)
                        }
                    } else r.skip(wt)
                }
            }
        }
        return result.toByteArray()
    }

    suspend fun writeFile(path: String, content: ByteArray): Boolean {
        val payload = ByteArrayOutputStream().apply {
            write(ProtoWriter.string(1, path))
            write(ProtoWriter.message(2) {   // WriteRequest.file (File message)
                write(ProtoWriter.bytes(4, content))  // File.data
            })
        }.toByteArray()
        val r = sendAndReceive(PbFieldId.STORAGE_WRITE_REQ, payload, timeoutMs = 10_000L)
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    suspend fun deleteFile(path: String): Boolean {
        val payload = ByteArrayOutputStream().apply {
            write(ProtoWriter.string(1, path))
        }.toByteArray()
        val r = sendAndReceive(PbFieldId.STORAGE_DELETE_REQ, payload)
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    suspend fun appStart(appName: String, args: String = ""): Boolean {
        // Exit any running app first — prevents ERROR_APP_SYSTEM_LOCKED (17) on repeat calls
        try { sendAndReceive(PbFieldId.APP_EXIT, byteArrayOf(), timeoutMs = 2000L) } catch (_: Exception) {}
        delay(300)

        val payload = ByteArrayOutputStream().apply {
            write(ProtoWriter.string(1, appName))
            if (args.isNotEmpty()) write(ProtoWriter.string(2, args))
        }.toByteArray()
        val r = sendAndReceive(PbFieldId.APP_START, payload, timeoutMs = 10_000L)
        if (r.isEmpty()) return false
        val status = r[0].commandStatus
        if (status != 0) ble.logPublic("appStart($appName) failed: status=$status (${storageError(status)})")
        return status == 0
    }

    suspend fun appExit(): Boolean {
        val r = sendAndReceive(PbFieldId.APP_EXIT, byteArrayOf(), timeoutMs = 3_000L)
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    suspend fun gpioWritePin(pinNumber: Int, high: Boolean): Boolean {
        val payload = ByteArrayOutputStream().apply {
            write(ProtoWriter.varint(1, pinNumber.toLong()))
            write(ProtoWriter.varint(2, if (high) 1L else 0L))
        }.toByteArray()
        val r = sendAndReceive(PbFieldId.GPIO_WRITE_PIN, payload)
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    fun stop() { scope.cancel() }
}
