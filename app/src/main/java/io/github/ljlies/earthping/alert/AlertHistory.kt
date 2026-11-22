package io.github.ljlies.earthping.alert

import android.content.Context
import io.github.ljlies.earthping.Subscription
import io.github.ljlies.earthping.geo.Geo
import io.github.ljlies.earthping.geo.LatLng
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    /** When the first alert for this event arrived. */
    val timeMs: Long,
    val id: String,
    val kind: String,
    val summary: String,
    val where: String,
    val magnitude: Double?,
    val distanceKm: Double?,
    val note: String?,
    /** Alerts that revise each other share this key (the first alert's id). */
    val event: String = id,
    val lat: Double? = null,
    val lon: Double? = null,
    val depthKm: Double? = null,
    val originMs: Long? = null,
    /** Estimated intensity at [where]. */
    val mmi: Double? = null,
    /** How many revisions arrived after the first alert, and when the last one did. */
    val revisions: Int = 0,
    val updatedMs: Long? = null,
) {
    val position: LatLng? get() = if (lat != null && lon != null) LatLng(lat, lon) else null
    val isEarthquake get() = kind == "earthquake"
}

/**
 * The last alerts received (kept on the device only). Revisions of an alert update its entry,
 * so each entry shows the latest parameters.
 */
object AlertHistory {
    private const val MAX = 100
    private val entries = ArrayList<HistoryEntry>()
    private var loaded = false
    private lateinit var file: java.io.File

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        file = java.io.File(io.github.ljlies.earthping.Storage.of(ctx).filesDir, "history.json")
        loaded = true
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    fun d(k: String) = if (o.has(k)) o.getDouble(k) else null
                    fun l(k: String) = if (o.has(k)) o.getLong(k) else null
                    val id = o.getString("id")
                    entries += HistoryEntry(o.getLong("t"), id, o.getString("k"), o.getString("s"), o.getString("w"),
                        d("m"), d("d"), o.optString("n").ifEmpty { null },
                        event = o.optString("e").ifEmpty { id }, lat = d("lat"), lon = d("lon"), depthKm = d("z"),
                        originMs = l("o"), mmi = d("i"), revisions = o.optInt("r", 0), updatedMs = l("u"))
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Records an alert. [event] groups revisions (defaults to the alert id); [at] is where the
     * alert matters to the user, for the intensity estimate.
     */
    @Synchronized
    fun add(ctx: Context, a: GoogleAlert, sub: Subscription?, note: String? = null, local: Boolean = false,
            distanceKm: Double? = null, event: String? = null, at: LatLng? = null) {
        init(ctx)
        val kind = when {
            a.isEarthquake -> "earthquake"
            a.type == GoogleAlert.Type.PUBLIC_ALERT -> "public alert"
            a.type == GoogleAlert.Type.SOS_ALERT -> "SOS alert"
            else -> "alert"
        }
        val q = a.quake
        val summary = q?.let {
            listOfNotNull(it.magnitude?.let { m -> String.format(java.util.Locale.US, "M%.1f", m) }, it.depthKm?.let { d -> "depth ${Math.round(d)} km" }).joinToString(" · ")
        }?.ifEmpty { null } ?: listOfNotNull(a.title ?: a.legacyRegionName, a.areaText).joinToString(" · ").ifEmpty { a.id }
        val where = when {
            local || sub?.primary == true -> "my location"
            sub != null -> sub.place.name
            else -> "?"
        }
        val center = a.center()
        val place = at ?: sub?.place?.latLng
        val mmi = if (q?.magnitude != null && q.epicenter != null && place != null)
            Geo.estimateMmi(q.magnitude, Geo.hypocentralKm(Geo.distanceKm(place, q.epicenter), q.depthKm)) else null
        val now = System.currentTimeMillis()
        val key = event ?: a.id
        val fresh = HistoryEntry(now, a.id, kind, summary, where, q?.magnitude, distanceKm, note,
            event = key, lat = center?.lat, lon = center?.lon, depthKm = q?.depthKm, originMs = q?.originMs, mmi = mmi)
        val i = entries.indexOfFirst { it.event == key && now - it.timeMs < 24 * 3600_000L }
        if (i >= 0) {
            val old = entries[i]
            entries[i] = fresh.copy(timeMs = old.timeMs, note = old.note, where = old.where.takeIf { it != "?" } ?: where,
                distanceKm = distanceKm ?: old.distanceKm, mmi = mmi ?: old.mmi,
                lat = fresh.lat ?: old.lat, lon = fresh.lon ?: old.lon,
                revisions = old.revisions + 1, updatedMs = now)
        } else {
            entries.add(0, fresh)
        }
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        save()
    }

    @Synchronized
    fun list(): List<HistoryEntry> = entries.toList()

    @Synchronized
    fun clear(ctx: Context) {
        init(ctx)
        entries.clear()
        save()
    }

    private fun save() {
        try {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().apply {
                    put("t", e.timeMs); put("id", e.id); put("k", e.kind); put("s", e.summary); put("w", e.where)
                    e.magnitude?.let { put("m", it) }; e.distanceKm?.let { put("d", it) }; e.note?.let { put("n", it) }
                    if (e.event != e.id) put("e", e.event)
                    e.lat?.let { put("lat", it) }; e.lon?.let { put("lon", it) }; e.depthKm?.let { put("z", it) }
                    e.originMs?.let { put("o", it) }; e.mmi?.let { put("i", it) }
                    if (e.revisions > 0) put("r", e.revisions); e.updatedMs?.let { put("u", it) }
                })
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {}
    }
}
