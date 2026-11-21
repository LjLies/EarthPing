package io.github.luccoj.alertroid.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The bits of S2 geometry Alertroid needs, written from the S2 cell-id specification:
 *  - lat/lng -> cell id (quadratic projection + Hilbert curve) -> token at a level;
 *  - containment between cell tokens;
 *  - decoding of S2Polygon::EncodeCompressed (used by Google crisis alert areas).
 */
object S2 {
    const val MAX_LEVEL = 30
    private const val SWAP = 1
    private const val INVERT = 2

    // ij (i<<1|j) -> Hilbert position, per orientation.
    private val IJ_TO_POS = arrayOf(intArrayOf(0, 1, 3, 2), intArrayOf(0, 3, 1, 2), intArrayOf(2, 3, 1, 0), intArrayOf(2, 1, 3, 0))
    private val POS_TO_ORIENT = intArrayOf(SWAP, 0, 0, SWAP or INVERT)

    private fun uvToSt(u: Double) = if (u >= 0) 0.5 * sqrt(1 + 3 * u) else 1 - 0.5 * sqrt(1 - 3 * u)
    fun stToUv(s: Double) = if (s >= 0.5) (4 * s * s - 1) / 3.0 else (1 - 4 * (1 - s) * (1 - s)) / 3.0

    private fun stToIj(s: Double): Int {
        val v = floor((1 shl MAX_LEVEL) * s).toLong()
        return v.coerceIn(0, (1L shl MAX_LEVEL) - 1).toInt()
    }

    /** Leaf (level 30) cell id for a point given in degrees. */
    fun leafCellId(latDeg: Double, lonDeg: Double): Long {
        val lat = Math.toRadians(latDeg.coerceIn(-90.0, 90.0))
        val lon = Math.toRadians(lonDeg)
        val x = cos(lat) * cos(lon)
        val y = cos(lat) * sin(lon)
        val z = sin(lat)
        val ax = kotlin.math.abs(x); val ay = kotlin.math.abs(y); val az = kotlin.math.abs(z)
        var face = if (ax > ay) { if (ax > az) 0 else 2 } else { if (ay > az) 1 else 2 }
        val comp = when (face) { 0 -> x; 1 -> y; else -> z }
        if (comp < 0) face += 3
        val (u, v) = when (face) {
            0 -> y / x to z / x
            1 -> -x / y to z / y
            2 -> -x / z to -y / z
            3 -> z / x to y / x
            4 -> z / y to -x / y
            else -> -y / z to -x / z
        }
        val i = stToIj(uvToSt(u))
        val j = stToIj(uvToSt(v))
        var orient = face and SWAP
        var pos = 0L
        for (k in MAX_LEVEL - 1 downTo 0) {
            val ij = (((i ushr k) and 1) shl 1) or ((j ushr k) and 1)
            val p = IJ_TO_POS[orient][ij]
            pos = (pos shl 2) or p.toLong()
            orient = orient xor POS_TO_ORIENT[p]
        }
        return (face.toLong() shl 61) or (pos shl 1) or 1L
    }

    fun parent(id: Long, level: Int): Long {
        val lsb = 1L shl (2 * (MAX_LEVEL - level))
        return (id and -lsb) or lsb
    }

    fun toToken(id: Long): String {
        if (id == 0L) return "X"
        val hex = java.lang.Long.toHexString(id).padStart(16, '0')
        return hex.trimEnd('0')
    }

    fun fromToken(token: String): Long? {
        if (token.isEmpty() || token.length > 16) return null
        return try {
            java.lang.Long.parseUnsignedLong(token.padEnd(16, '0'), 16)
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Token of the cell at [level] containing the point. Google's alerts use level 8 (~38 km). */
    fun token(latDeg: Double, lonDeg: Double, level: Int = 8): String = toToken(parent(leafCellId(latDeg, lonDeg), level))

    private fun lsb(id: Long) = id and -id

    /** True if one of the two cells contains the other (S2 cells nest). */
    fun related(a: String, b: String): Boolean {
        val ia = fromToken(a) ?: return false
        val ib = fromToken(b) ?: return false
        if (ia == 0L || ib == 0L) return false
        val la = lsb(ia); val lb = lsb(ib)
        val aMin = ia - (la - 1); val aMax = ia + (la - 1)
        val bMin = ib - (lb - 1); val bMax = ib + (lb - 1)
        return java.lang.Long.compareUnsigned(aMin, bMax) <= 0 && java.lang.Long.compareUnsigned(bMin, aMax) <= 0
    }

    private fun faceUvToLatLng(face: Int, u: Double, v: Double): LatLng {
        val (x, y, z) = when (face) {
            0 -> Triple(1.0, u, v)
            1 -> Triple(-u, 1.0, v)
            2 -> Triple(-u, -v, 1.0)
            3 -> Triple(-1.0, -v, -u)
            4 -> Triple(v, -1.0, -u)
            else -> Triple(v, u, -1.0)
        }
        return xyzToLatLng(x, y, z)
    }

    private fun xyzToLatLng(x: Double, y: Double, z: Double) =
        LatLng(Math.toDegrees(atan2(z, hypot(x, y))), Math.toDegrees(atan2(y, x)))

    /** Splits the even bits of [n] into the first value and the odd bits into the second. */
    private fun deinterleave(n: Long): Pair<Int, Int> {
        var a = 0L; var b = 0L
        for (i in 0 until 32) {
            a = a or (((n ushr (2 * i)) and 1L) shl i)
            b = b or (((n ushr (2 * i + 1)) and 1L) shl i)
        }
        return a.toInt() to b.toInt()
    }

    /** Second-order delta decoder with 32-bit wrap-around arithmetic. */
    private class Delta2 {
        private var m = 0
        private val mem = IntArray(2)
        fun decode(k0: Int): Int {
            var k = k0
            if (m < 2) m++
            for (i in m - 1 downTo 0) {
                k += mem[i]
                mem[i] = k
            }
            return k
        }
    }

    private fun zigzag(n: Int): Int = (n ushr 1) xor -(n and 1)

    /**
     * Decodes a compressed S2Polygon (encoding version 4) into rings of lat/lng vertices.
     * Returns null when [b] is not in that encoding or is malformed.
     */
    fun decodeCompressedPolygon(b: ByteArray): List<List<LatLng>>? {
        try {
            if (b.size < 3 || b[0].toInt() != 4) return null
            val level = b[1].toInt() and 0xFF
            if (level > MAX_LEVEL) return null
            var p = 2
            fun varint(): Long {
                val (v, np) = io.github.luccoj.alertroid.proto.Wire.readVarint(b, p) ?: throw IllegalArgumentException("varint")
                p = np
                return v
            }
            val loops = varint().toInt()
            if (loops !in 0..10_000) return null
            val rings = ArrayList<List<LatLng>>(loops)
            repeat(loops) {
                val n = varint().toInt()
                if (n !in 0..1_000_000) return null
                val faces = ArrayList<Int>(n)
                while (faces.size < n) {
                    val v = varint()
                    val count = (v / 6).toInt()
                    if (count <= 0 || faces.size + count > n) return null
                    repeat(count) { faces.add((v % 6).toInt()) }
                }
                val dp = Delta2(); val dq = Delta2()
                val scale = (1L shl level).toDouble()
                val pts = ArrayList<LatLng>(n)
                for (i in 0 until n) {
                    val pi: Int; val qi: Int
                    if (i == 0) {
                        val nb = (level + 7) / 8 * 2
                        if (p + nb > b.size) return null
                        var raw = 0L
                        for (k in 0 until nb) raw = raw or ((b[p + k].toLong() and 0xFF) shl (8 * k))
                        p += nb
                        val (a, c) = deinterleave(raw)
                        pi = dp.decode(a); qi = dq.decode(c)
                    } else {
                        val (zp, zq) = deinterleave(varint())
                        pi = dp.decode(zigzag(zp)); qi = dq.decode(zigzag(zq))
                    }
                    pts.add(faceUvToLatLng(faces[i], stToUv((pi + 0.5) / scale), stToUv((qi + 0.5) / scale)))
                }
                val offCenter = varint().toInt()
                repeat(offCenter) {
                    val idx = varint().toInt()
                    if (idx !in 0 until n || p + 24 > b.size) return null
                    val xyz = DoubleArray(3) { k ->
                        var bits = 0L
                        for (t in 0 until 8) bits = bits or ((b[p + 8 * k + t].toLong() and 0xFF) shl (8 * t))
                        java.lang.Double.longBitsToDouble(bits)
                    }
                    p += 24
                    pts[idx] = xyzToLatLng(xyz[0], xyz[1], xyz[2])
                }
                val props = varint()
                varint() // depth
                if (props and 2L != 0L) p += 33 // encoded bound
                rings.add(pts)
            }
            return rings
        } catch (e: IllegalArgumentException) {
            return null
        } catch (e: IndexOutOfBoundsException) {
            return null
        }
    }
}
