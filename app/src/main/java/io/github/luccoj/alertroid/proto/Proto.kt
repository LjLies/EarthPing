package io.github.luccoj.alertroid.proto

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException

/**
 * A tiny, schema-less protobuf codec: enough to speak Google's checkin and MCS
 * protocols and to read the alert payloads, without a protobuf runtime dependency.
 */
object Wire {
    const val VARINT = 0
    const val FIXED64 = 1
    const val LEN = 2
    const val FIXED32 = 5

    fun writeVarint(out: ByteArrayOutputStream, value: Long) {
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

    fun varintBytes(value: Long): ByteArray =
        ByteArrayOutputStream(10).also { writeVarint(it, value) }.toByteArray()

    /** Reads a varint from [buf] at [pos]. Returns (value, newPos) or null if incomplete/invalid. */
    fun readVarint(buf: ByteArray, pos: Int, limit: Int = buf.size): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var p = pos
        while (p < limit) {
            val b = buf[p].toInt() and 0xFF
            p++
            if (shift < 64) result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to p
            shift += 7
            if (shift >= 70) return null
        }
        return null
    }

    /** Reads a varint from a stream; throws at EOF. */
    fun readVarint(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("EOF while reading varint")
            if (shift < 64) result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 70) throw IOException("Varint too long")
        }
    }
}

class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun key(tag: Int, wire: Int) = Wire.writeVarint(out, ((tag shl 3) or wire).toLong())

    fun varint(tag: Int, value: Long): ProtoWriter { key(tag, Wire.VARINT); Wire.writeVarint(out, value); return this }
    fun varint(tag: Int, value: Int): ProtoWriter = varint(tag, value.toLong())
    fun bool(tag: Int, value: Boolean): ProtoWriter = varint(tag, if (value) 1L else 0L)

    fun fixed64(tag: Int, value: Long): ProtoWriter {
        key(tag, Wire.FIXED64)
        for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xFF).toInt())
        return this
    }

    fun fixed32(tag: Int, value: Int): ProtoWriter {
        key(tag, Wire.FIXED32)
        for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xFF)
        return this
    }

    fun double(tag: Int, value: Double): ProtoWriter = fixed64(tag, java.lang.Double.doubleToRawLongBits(value))
    fun float(tag: Int, value: Float): ProtoWriter = fixed32(tag, java.lang.Float.floatToRawIntBits(value))

    fun bytes(tag: Int, value: ByteArray): ProtoWriter {
        key(tag, Wire.LEN)
        Wire.writeVarint(out, value.size.toLong())
        out.write(value)
        return this
    }

    fun string(tag: Int, value: String): ProtoWriter = bytes(tag, value.toByteArray(Charsets.UTF_8))
    fun message(tag: Int, build: ProtoWriter.() -> Unit): ProtoWriter = bytes(tag, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

class ProtoField(val wire: Int, val num: Long, val data: ByteArray?)

/** A parsed message: tag -> occurrences in wire order. */
class ProtoMessage private constructor(private val fields: Map<Int, List<ProtoField>>) {

    fun has(tag: Int) = fields.containsKey(tag)
    fun tags(): Set<Int> = fields.keys
    fun all(tag: Int): List<ProtoField> = fields[tag] ?: emptyList()
    private fun first(tag: Int): ProtoField? = fields[tag]?.firstOrNull()

    fun long(tag: Int): Long? = first(tag)?.takeIf { it.wire == Wire.VARINT || it.wire == Wire.FIXED64 || it.wire == Wire.FIXED32 }?.num
    fun int(tag: Int): Int? = long(tag)?.toInt()
    fun bool(tag: Int): Boolean? = long(tag)?.let { it != 0L }

    /** Signed int32 that was sent as a (possibly sign-extended 64-bit) varint. */
    fun int32(tag: Int): Int? = long(tag)?.toInt()

    fun double(tag: Int): Double? = first(tag)?.takeIf { it.wire == Wire.FIXED64 }?.let { java.lang.Double.longBitsToDouble(it.num) }
    fun float(tag: Int): Float? = first(tag)?.takeIf { it.wire == Wire.FIXED32 }?.let { java.lang.Float.intBitsToFloat(it.num.toInt()) }

    /** Any numeric field as a double, whatever its wire type (varint, double or float). */
    fun number(tag: Int): Double? {
        val f = first(tag) ?: return null
        return when (f.wire) {
            Wire.VARINT -> f.num.toDouble()
            Wire.FIXED64 -> java.lang.Double.longBitsToDouble(f.num)
            Wire.FIXED32 -> java.lang.Float.intBitsToFloat(f.num.toInt()).toDouble()
            else -> f.data?.toString(Charsets.UTF_8)?.trim()?.toDoubleOrNull()
        }?.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    fun bytes(tag: Int): ByteArray? = first(tag)?.takeIf { it.wire == Wire.LEN }?.data
    fun string(tag: Int): String? = bytes(tag)?.toString(Charsets.UTF_8)
    fun message(tag: Int): ProtoMessage? = bytes(tag)?.let { parse(it) }

    fun bytesList(tag: Int): List<ByteArray> = all(tag).filter { it.wire == Wire.LEN }.mapNotNull { it.data }
    fun strings(tag: Int): List<String> = bytesList(tag).map { it.toString(Charsets.UTF_8) }
    fun messages(tag: Int): List<ProtoMessage> = bytesList(tag).mapNotNull { parse(it) }

    companion object {
        val EMPTY = ProtoMessage(emptyMap())

        /** Strict parse: returns null unless [data] is a complete, well-formed message. */
        fun parseOrNull(data: ByteArray): ProtoMessage? {
            val map = LinkedHashMap<Int, MutableList<ProtoField>>()
            var p = 0
            val n = data.size
            while (p < n) {
                val (key, p1) = Wire.readVarint(data, p) ?: return null
                p = p1
                val tag = (key ushr 3).toInt()
                val wire = (key and 7).toInt()
                if (tag <= 0) return null
                val field = when (wire) {
                    Wire.VARINT -> {
                        val (v, p2) = Wire.readVarint(data, p) ?: return null
                        p = p2
                        ProtoField(wire, v, null)
                    }
                    Wire.FIXED64 -> {
                        if (p + 8 > n) return null
                        var v = 0L
                        for (i in 0 until 8) v = v or ((data[p + i].toLong() and 0xFF) shl (8 * i))
                        p += 8
                        ProtoField(wire, v, null)
                    }
                    Wire.LEN -> {
                        val (len, p2) = Wire.readVarint(data, p) ?: return null
                        if (len < 0 || p2 + len > n) return null
                        val bytes = data.copyOfRange(p2, p2 + len.toInt())
                        p = p2 + len.toInt()
                        ProtoField(wire, bytes.size.toLong(), bytes)
                    }
                    Wire.FIXED32 -> {
                        if (p + 4 > n) return null
                        var v = 0L
                        for (i in 0 until 4) v = v or ((data[p + i].toLong() and 0xFF) shl (8 * i))
                        p += 4
                        ProtoField(wire, v, null)
                    }
                    else -> return null
                }
                map.getOrPut(tag) { ArrayList(1) }.add(field)
            }
            return ProtoMessage(map)
        }

        /** Lenient parse: an empty message when [data] is not a valid protobuf. */
        fun parse(data: ByteArray): ProtoMessage = parseOrNull(data) ?: EMPTY
    }
}
