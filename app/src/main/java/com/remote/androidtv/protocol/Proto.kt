package com.remote.androidtv.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal protobuf encoder/decoder: just enough for the Android TV Remote v2 messages,
 * so the app does not need the protobuf toolchain.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, WIRE_VARINT)
        writeVarint(out, value)
        return this
    }

    fun varint(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_LENGTH)
        writeVarint(out, value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = writeVarint(out, ((field shl 3) or wireType).toLong())

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5

        fun writeVarint(out: OutputStream, value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
    }
}

/** A decoded protobuf message: field number -> values (Long for numeric, ByteArray for length-delimited). */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int): Boolean = fields.containsKey(field)

    fun varint(field: Int): Long? = fields[field]?.lastOrNull() as? Long

    fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray

    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)

    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            var pos = 0
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IOException("Truncated varint")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IOException("Varint too long")
                }
            }
            fun take(n: Int): ByteArray {
                if (n < 0 || pos + n > data.size) throw IOException("Truncated field")
                return data.copyOfRange(pos, pos + n).also { pos += n }
            }
            fun Long.fromLittleEndian(bytes: ByteArray): Long =
                bytes.foldIndexed(0L) { i, acc, b -> acc or ((b.toLong() and 0xFF) shl (8 * i)) }

            while (pos < data.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 7).toInt()) {
                    ProtoWriter.WIRE_VARINT -> readVarint()
                    ProtoWriter.WIRE_LENGTH -> take(readVarint().toInt())
                    ProtoWriter.WIRE_FIXED64 -> 0L.fromLittleEndian(take(8))
                    ProtoWriter.WIRE_FIXED32 -> 0L.fromLittleEndian(take(4))
                    else -> throw IOException("Unsupported wire type in key $key")
                }
                fields.getOrPut(field) { mutableListOf() }.add(value)
            }
            return ProtoMessage(fields)
        }
    }
}

/** Both TV ports frame each protobuf message with a varint length prefix. */
object Framing {
    fun write(out: OutputStream, payload: ByteArray) {
        val buf = ByteArrayOutputStream(payload.size + 5)
        ProtoWriter.writeVarint(buf, payload.size.toLong())
        buf.write(payload)
        // One write call -> one TLS record.
        out.write(buf.toByteArray())
        out.flush()
    }

    fun read(input: InputStream): ByteArray {
        var length = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Connection closed by TV")
            length = length or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IOException("Bad frame length")
        }
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n < 0) throw EOFException("Connection closed by TV")
            read += n
        }
        return data
    }
}
