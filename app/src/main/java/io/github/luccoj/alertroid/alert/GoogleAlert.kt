package io.github.luccoj.alertroid.alert

import io.github.luccoj.alertroid.geo.LatLng
import io.github.luccoj.alertroid.geo.S2
import io.github.luccoj.alertroid.proto.ProtoMessage
import java.util.Base64
import java.util.Locale

/** One alert as Google pushes it on the `ea.<cell>` topics. */
data class GoogleAlert(
    val id: String,
    val issuedMs: Long?,
    val updatedMs: Long?,
    val expiresMs: Long?,
    val mode: Mode,
    val type: Type,
    val cells: List<String>,
    val contours: List<Contour>,
    val areaPolygons: List<List<LatLng>>,
    val title: String?,
    val areaText: String?,
    val sender: String?,
    val sourceName: String?,
    val eventCode: String?,
    val quake: Quake?,
    /** Legacy `ea.msg` alerts carry a place name and nothing else. */
    val legacyRegionName: String? = null,
) {
    enum class Mode { LIVE, SILENT, TEST, UNKNOWN }
    enum class Type { EARTHQUAKE, PUBLIC_ALERT, SOS_ALERT, OTHER }

    val isEarthquake get() = type == Type.EARTHQUAKE || quake != null

    /** Every vertex of every area, for "where is this" calculations. */
    fun allRings(): List<List<LatLng>> =
        contours.flatMap { c -> c.rings + c.circles.map { io.github.luccoj.alertroid.geo.Geo.circle(it.center, it.radiusM / 1000.0, 36) } } + areaPolygons

    fun center(): LatLng? {
        quake?.epicenter?.let { return it }
        val pts = allRings().flatten()
        if (pts.isEmpty()) return null
        return LatLng(pts.sumOf { it.lat } / pts.size, pts.sumOf { it.lon } / pts.size)
    }
}

data class Circle(val center: LatLng, val radiusM: Double)

/** level: 1 = MMI 5+ ("take action"), 2 = MMI 4, 3 = MMI 3 ("be aware"). */
data class Contour(val level: Int, val rings: List<List<LatLng>>, val circles: List<Circle>) {
    val mmi: Int get() = when (level) { 1 -> 5; 2 -> 4; 3 -> 3; else -> 3 }
}

data class Quake(
    val magnitude: Double?,
    val epicenter: LatLng?,
    val depthKm: Double?,
    val originMs: Long?,
    val waveSpeedMps: Int?,
    val shakingDurationS: Int?,
    val showMagnitude: Boolean?,
)

object AlertDecoder {

    private fun timestampMs(m: ProtoMessage?): Long? {
        if (m == null) return null
        val s = m.long(1) ?: return null
        if (s <= 0) return null
        val nanos = (m.long(2) ?: 0L).coerceIn(0, 999_999_999)
        return s * 1000 + nanos / 1_000_000
    }

    private fun latLng(m: ProtoMessage?): LatLng? {
        if (m == null) return null
        val lat = m.double(1) ?: return null
        val lon = m.double(2) ?: return null
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LatLng(lat, lon)
    }

    /** Decodes a `rawData` AlertBatch. Malformed alerts are skipped, never fatal. */
    fun decodeBatch(raw: ByteArray, language: String = Locale.getDefault().language): List<GoogleAlert> {
        val batch = ProtoMessage.parseOrNull(raw) ?: return emptyList()
        return batch.bytesList(2).mapNotNull { b ->
            try {
                ProtoMessage.parseOrNull(b)?.let { decodeAlert(it, language) }
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    fun decodeAlert(a: ProtoMessage, language: String): GoogleAlert? {
        val header = a.message(1)
        val id = header?.string(1) ?: header?.string(3) ?: return null
        val mode = when (a.int(2)) {
            0, 3 -> GoogleAlert.Mode.LIVE
            1 -> GoogleAlert.Mode.SILENT
            2 -> GoogleAlert.Mode.TEST
            null -> GoogleAlert.Mode.LIVE
            else -> GoogleAlert.Mode.UNKNOWN
        }
        val type = when (a.int(3)) {
            5 -> GoogleAlert.Type.EARTHQUAKE
            1 -> GoogleAlert.Type.PUBLIC_ALERT
            2 -> GoogleAlert.Type.SOS_ALERT
            else -> GoogleAlert.Type.OTHER
        }

        // Area: S2 cells, intensity contours (polygons and/or circles), or a compressed S2Polygon.
        val cells = ArrayList<String>()
        val contours = ArrayList<Contour>()
        val polygons = ArrayList<List<LatLng>>()
        a.message(6)?.let { region ->
            region.messages(1).forEach { cells += it.strings(1) }
            region.messages(2).forEach { group ->
                group.messages(1).forEach { c ->
                    val rings = c.messages(2).map { ring -> ring.messages(1).mapNotNull { latLng(it) } }.filter { it.size >= 3 }
                    val circles = c.messages(3).mapNotNull { circ ->
                        val center = latLng(circ.message(1)) ?: return@mapNotNull null
                        val r = circ.number(2) ?: return@mapNotNull null
                        if (r > 0) Circle(center, r) else null
                    }
                    contours += Contour(c.int(1) ?: 0, rings, circles)
                }
            }
            region.bytes(3)?.let { S2.decodeCompressedPolygon(it) }?.let { polygons += it.filter { r -> r.size >= 3 } }
        }

        // Localized texts: {1 lang, 2 public {1 headline, 2 area, 3 sender} | 3 sos {2 title}}
        data class Text(val title: String?, val area: String?, val sender: String?)
        val texts = LinkedHashMap<String, Text>()
        a.messages(9).forEach { t ->
            val lang = t.string(1) ?: return@forEach
            val pub = t.message(2)
            val text = if (pub != null) Text(pub.string(1), pub.string(2), pub.string(3))
            else Text(t.message(3)?.string(2), null, null)
            if (!text.title.isNullOrBlank()) texts[lang] = text
        }
        val text = texts[language] ?: texts.entries.firstOrNull { it.key.startsWith("$language-") || it.key.startsWith("${language}_") }?.value
            ?: texts["en"] ?: texts.values.firstOrNull()

        val quake = a.message(14)?.message(1)?.let { q ->
            Quake(
                magnitude = q.number(1)?.let { Math.round(it * 10) / 10.0 },
                epicenter = latLng(q.message(2)),
                depthKm = q.int32(3)?.takeIf { it >= 0 }?.let { it / 1000.0 },
                originMs = timestampMs(q.message(4)),
                waveSpeedMps = q.int32(5)?.takeIf { it > 0 },
                shakingDurationS = q.int32(6)?.takeIf { it > 0 },
                showMagnitude = q.bool(8),
            )
        }

        return GoogleAlert(
            id = id,
            issuedMs = timestampMs(header?.message(2)),
            updatedMs = timestampMs(a.message(13)),
            expiresMs = timestampMs(a.message(5)),
            mode = mode,
            type = if (quake != null) GoogleAlert.Type.EARTHQUAKE else type,
            cells = cells,
            contours = contours,
            areaPolygons = polygons,
            title = text?.title,
            areaText = text?.area,
            sender = text?.sender,
            sourceName = a.message(12)?.string(1),
            eventCode = a.message(7)?.string(1),
            quake = quake,
        )
    }

    /** Older `ea.msg` app-data format (web-safe base64): only an id, type, cells and a place name. */
    fun decodeLegacy(eaMsg: String): GoogleAlert? {
        val bytes = try {
            Base64.getUrlDecoder().decode(eaMsg.trim().trimEnd('='))
        } catch (e: IllegalArgumentException) {
            return null
        }
        val g = ProtoMessage.parseOrNull(bytes) ?: return null
        val al = g.message(7) ?: return null
        val topic = g.string(3) ?: ""
        val id = al.string(1) ?: return null
        val isTest = g.int(1) == 2 || topic.split('.').contains("tst")
        return GoogleAlert(
            id = id, issuedMs = timestampMs(al.message(3)), updatedMs = null, expiresMs = null,
            mode = if (isTest) GoogleAlert.Mode.TEST else GoogleAlert.Mode.LIVE,
            type = GoogleAlert.Type.EARTHQUAKE,
            cells = al.strings(6), contours = emptyList(), areaPolygons = emptyList(),
            title = null, areaText = null, sender = null, sourceName = null, eventCode = null, quake = null,
            legacyRegionName = al.string(5),
        )
    }
}
