package com.flippercontrol.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object PbField {
    // PB_Main fields
    const val COMMAND_ID     = 1
    const val COMMAND_STATUS = 2
    const val HAS_NEXT       = 3

    // System
    const val PING_REQUEST         = 5
    const val PING_RESPONSE        = 6
    const val DEVICE_INFO_REQUEST  = 32
    const val DEVICE_INFO_RESPONSE = 33

    // Storage
    const val STORAGE_LIST_REQUEST   = 7
    const val STORAGE_LIST_RESPONSE  = 8
    const val STORAGE_READ_REQUEST   = 9
    const val STORAGE_READ_RESPONSE  = 10
    const val STORAGE_WRITE_REQUEST  = 11
    const val STORAGE_DELETE_REQUEST = 12
    const val STORAGE_MKDIR_REQUEST  = 13

    // App
    const val APP_START_REQUEST = 16
    const val APP_EXIT_REQUEST  = 17

    // GPIO (from gpio.proto)
    const val GPIO_SET_PIN_MODE  = 51
    const val GPIO_READ_PIN      = 55
    const val GPIO_READ_PIN_RESP = 56
    const val GPIO_WRITE_PIN     = 57
}

// GPIO pin enum values from official Flipper protobuf gpio.proto
enum class GpioPin(val id: Int) {
    PC0(0), PC1(1), PC3(2), PB2(3), PB3(4), PA4(5), PA6(6), PA7(7)
}

object ProtoWriter {
    fun varint(fieldNumber: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, ((fieldNumber shl 3) or 0).toLong())
        writeVarint(out, value)
        return out.toByteArray()
    }

    fun bytes(fieldNumber: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, ((fieldNumber shl 3) or 2).toLong())
        writeVarint(out, data.size.toLong())
        out.write(data)
        return out.toByteArray()
    }

    fun string(fieldNumber: Int, value: String) = bytes(fieldNumber, value.toByteArray(Charsets.UTF_8))

    fun message(fieldNumber: Int, inner: ByteArray) = bytes(fieldNumber, inner)

    fun encodeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, value)
        return out.toByteArray()
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
        if (len <= 0 || pos + len > data.size) return byteArrayOf()
        return data.copyOfRange(pos, pos + len).also { pos += len }
    }

    fun readString() = String(readBytes(), Charsets.UTF_8)
    fun hasMore() = pos < data.size

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> pos = minOf(pos + 8, data.size)
            2 -> readBytes()
            5 -> pos = minOf(pos + 4, data.size)
        }
    }
}

data class FlipperResponse(
    val commandId: Int,
    val commandStatus: Int,
    val hasNext: Boolean,
    val payload: Map<Int, Any>
)

data class FsFile(
    val name: String,
    val isDir: Boolean,
    val size: Long = 0L
)

class FlipperRpcSession(private val ble: FlipperBleManager) {

    private val commandCounter = AtomicInteger(1)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pending = ConcurrentHashMap<Int, Channel<FlipperResponse>>()

    private val _events = MutableSharedFlow<FlipperResponse>(extraBufferCapacity = 64)
    val events: SharedFlow<FlipperResponse> = _events

    init {
        scope.launch { processIncoming() }
    }

    private suspend fun processIncoming() {
        val buffer = ByteArrayOutputStream()
        try {
            for (chunk in ble.incomingData) {
                buffer.write(chunk)
                tryParseMessages(buffer)
            }
        } catch (_: Exception) {}
    }

    private suspend fun tryParseMessages(buffer: ByteArrayOutputStream) {
        while (true) {
            val data = buffer.toByteArray()
            if (data.isEmpty()) return

            val reader = ProtoReader(data)
            val msgLen = try { reader.readVarint().toInt() } catch (_: Exception) { return }
            val headerLen = reader.pos  // bytes consumed by the length varint
            val totalLen = headerLen + msgLen

            if (msgLen <= 0 || data.size < totalLen) return

            val msgBytes = data.copyOfRange(headerLen, totalLen)
            buffer.reset()
            if (data.size > totalLen) {
                buffer.write(data, totalLen, data.size - totalLen)
            }

            try { parseAndDispatch(msgBytes) } catch (_: Exception) {}
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
                PbField.COMMAND_ID     -> commandId = reader.readVarint().toInt()
                PbField.COMMAND_STATUS -> commandStatus = reader.readVarint().toInt()
                PbField.HAS_NEXT       -> hasNext = reader.readVarint() != 0L
                else -> when (wireType) {
                    2    -> payload[field] = reader.readBytes()
                    0    -> payload[field] = reader.readVarint()
                    else -> reader.skip(wireType)
                }
            }
        }

        val response = FlipperResponse(commandId, commandStatus, hasNext, payload)
        val ch = pending[commandId]
        if (ch != null) {
            ch.send(response)
            if (!hasNext) pending.remove(commandId)
        } else {
            _events.emit(response)
        }
    }

    private suspend fun sendCommand(fieldId: Int, payload: ByteArray): Channel<FlipperResponse> {
        val id = commandCounter.getAndIncrement()
        val ch = Channel<FlipperResponse>(Channel.UNLIMITED)
        pending[id] = ch

        val msg = ByteArrayOutputStream().apply {
            write(ProtoWriter.varint(PbField.COMMAND_ID, id.toLong()))
            write(ProtoWriter.bytes(fieldId, payload))
        }.toByteArray()

        val framed = ProtoWriter.encodeVarint(msg.size.toLong()) + msg
        ble.send(framed)
        return ch
    }

    suspend fun sendAndReceive(
        fieldId: Int,
        payload: ByteArray,
        timeoutMs: Long = 5000L
    ): List<FlipperResponse> {
        val ch = sendCommand(fieldId, payload)
        val results = mutableListOf<FlipperResponse>()
        return try {
            withTimeout(timeoutMs) {
                do {
                    val resp = ch.receive()
                    results.add(resp)
                } while (resp.hasNext)
                results
            }
        } catch (_: Exception) { results }
    }

    // ── System ────────────────────────────────────────────────────────────────

    suspend fun ping(): Boolean = try {
        val r = sendAndReceive(PbField.PING_REQUEST, byteArrayOf())
        r.isNotEmpty() && r[0].commandStatus == 0
    } catch (_: Exception) { false }

    suspend fun deviceInfo(): Map<String, String> {
        val responses = sendAndReceive(PbField.DEVICE_INFO_REQUEST, byteArrayOf(), timeoutMs = 10000L)
        val result = mutableMapOf<String, String>()
        for (resp in responses) {
            (resp.payload[PbField.DEVICE_INFO_RESPONSE] as? ByteArray)?.let { bytes ->
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

    // ── Storage ───────────────────────────────────────────────────────────────

    suspend fun listStorage(path: String): List<FsFile> {
        val req = ProtoWriter.string(1, path)
        val responses = sendAndReceive(PbField.STORAGE_LIST_REQUEST, req, timeoutMs = 10000L)
        val files = mutableListOf<FsFile>()
        for (resp in responses) {
            if (resp.commandStatus != 0) continue
            (resp.payload[PbField.STORAGE_LIST_RESPONSE] as? ByteArray)?.let { bytes ->
                val r = ProtoReader(bytes)
                while (r.hasMore()) {
                    val (f, wt) = r.readTag()
                    if (f == 1 && wt == 2) {
                        // File message: type(1)=enum[DIR=1,FILE=0], name(2), size(3)
                        val fileBytes = r.readBytes()
                        val fr = ProtoReader(fileBytes)
                        var type = 0; var name = ""; var size = 0L
                        while (fr.hasMore()) {
                            val (ff, fwt) = fr.readTag()
                            when (ff) {
                                1 -> type = fr.readVarint().toInt()
                                2 -> name = fr.readString()
                                3 -> size = fr.readVarint()
                                else -> fr.skip(fwt)
                            }
                        }
                        if (name.isNotEmpty()) files.add(FsFile(name, type == 1, size))
                    } else r.skip(wt)
                }
            }
        }
        return files
    }

    suspend fun readFile(path: String): ByteArray {
        val req = ProtoWriter.string(1, path)
        val responses = sendAndReceive(PbField.STORAGE_READ_REQUEST, req, timeoutMs = 30000L)
        val out = ByteArrayOutputStream()
        for (resp in responses) {
            if (resp.commandStatus != 0) continue
            (resp.payload[PbField.STORAGE_READ_RESPONSE] as? ByteArray)?.let { bytes ->
                // StorageReadResponse: File(1) { data(4) }
                val r = ProtoReader(bytes)
                while (r.hasMore()) {
                    val (f, wt) = r.readTag()
                    if (f == 1 && wt == 2) {
                        val fileMsg = r.readBytes()
                        val fr = ProtoReader(fileMsg)
                        while (fr.hasMore()) {
                            val (ff, fwt) = fr.readTag()
                            if (ff == 4 && fwt == 2) out.write(fr.readBytes())
                            else fr.skip(fwt)
                        }
                    } else r.skip(wt)
                }
            }
        }
        return out.toByteArray()
    }

    suspend fun writeFile(path: String, data: ByteArray): Boolean {
        // StorageWriteRequest: path(1), file(2: File { data(4) })
        val fileMsg = ProtoWriter.bytes(4, data)
        val req = ByteArrayOutputStream().apply {
            write(ProtoWriter.string(1, path))
            write(ProtoWriter.message(2, fileMsg))
        }.toByteArray()
        val r = sendAndReceive(PbField.STORAGE_WRITE_REQUEST, req, timeoutMs = 15000L)
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    suspend fun deleteFile(path: String, recursive: Boolean = false): Boolean {
        val req = ByteArrayOutputStream().apply {
            write(ProtoWriter.string(1, path))
            if (recursive) write(ProtoWriter.varint(2, 1L))
        }.toByteArray()
        val r = sendAndReceive(PbField.STORAGE_DELETE_REQUEST, req)
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    suspend fun mkdir(path: String): Boolean {
        val req = ProtoWriter.string(1, path)
        val r = sendAndReceive(PbField.STORAGE_MKDIR_REQUEST, req)
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    // ── App ───────────────────────────────────────────────────────────────────

    suspend fun appStart(appId: String, args: String = ""): Boolean {
        val req = ByteArrayOutputStream().apply {
            write(ProtoWriter.string(1, appId))
            if (args.isNotEmpty()) write(ProtoWriter.string(2, args))
        }.toByteArray()
        val r = sendAndReceive(PbField.APP_START_REQUEST, req)
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    suspend fun appExit(): Boolean {
        val r = sendAndReceive(PbField.APP_EXIT_REQUEST, byteArrayOf())
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    // ── GPIO ─────────────────────────────────────────────────────────────────

    suspend fun gpioSetPinMode(pin: GpioPin, output: Boolean): Boolean {
        // GpioSetPinMode: pin(1: enum), mode(2: 0=INPUT / 1=OUTPUT_PUSH_PULL)
        val req = ByteArrayOutputStream().apply {
            write(ProtoWriter.varint(1, pin.id.toLong()))
            write(ProtoWriter.varint(2, if (output) 1L else 0L))
        }.toByteArray()
        val r = sendAndReceive(PbField.GPIO_SET_PIN_MODE, req)
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    suspend fun gpioWritePin(pin: GpioPin, high: Boolean): Boolean {
        // GpioWritePinRequest: pin(1: enum), value(2: bool)
        val req = ByteArrayOutputStream().apply {
            write(ProtoWriter.varint(1, pin.id.toLong()))
            write(ProtoWriter.varint(2, if (high) 1L else 0L))
        }.toByteArray()
        val r = sendAndReceive(PbField.GPIO_WRITE_PIN, req)
        return r.isNotEmpty() && r[0].commandStatus == 0
    }

    suspend fun gpioReadPin(pin: GpioPin): Boolean? {
        val req = ProtoWriter.varint(1, pin.id.toLong())
        val r = sendAndReceive(PbField.GPIO_READ_PIN, req)
        if (r.isEmpty() || r[0].commandStatus != 0) return null
        val bytes = r[0].payload[PbField.GPIO_READ_PIN_RESP] as? ByteArray ?: return null
        // GpioReadPinResponse: value(2: bool)
        val reader = ProtoReader(bytes)
        while (reader.hasMore()) {
            val (f, wt) = reader.readTag()
            if (f == 2 && wt == 0) return reader.readVarint() != 0L
            else reader.skip(wt)
        }
        return null
    }

    fun stop() { scope.cancel() }
}
