package io.github.luccoj.alertroid.alert

import android.content.Context
import io.github.luccoj.alertroid.Subscription
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val timeMs: Long,
    val id: String,
    val kind: String,
    val summary: String,
    val where: String,
    val magnitude: Double?,
    val distanceKm: Double?,
    val note: String?,
)

/** The last alerts received (kept on the device only). */
object AlertHistory {
    private const val MAX = 100
    private val entries = ArrayList<HistoryEntry>()
    private var loaded = false
    private lateinit var file: java.io.File

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        file = java.io.File(ctx.filesDir, "history.json")
        loaded = true
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    entries += HistoryEntry(o.getLong("t"), o.getString("id"), o.getString("k"), o.getString("s"), o.getString("w"),
                        if (o.has("m")) o.getDouble("m") else null, if (o.has("d")) o.getDouble("d") else null, o.optString("n").ifEmpty { null })
                }
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun add(ctx: Context, a: GoogleAlert, sub: Subscription?, note: String? = null, local: Boolean = false, distanceKm: Double? = null) {
        init(ctx)
        val kind = when {
            a.isEarthquake -> "earthquake"
            a.type == GoogleAlert.Type.PUBLIC_ALERT -> "public alert"
            a.type == GoogleAlert.Type.SOS_ALERT -> "SOS alert"
            else -> "alert"
        }
        val summary = a.quake?.let { q ->
            listOfNotNull(q.magnitude?.let { String.format(java.util.Locale.US, "M%.1f", it) }, q.depthKm?.let { "depth ${Math.round(it)} km" }).joinToString(" · ")
        }?.ifEmpty { null } ?: listOfNotNull(a.title ?: a.legacyRegionName, a.areaText).joinToString(" · ").ifEmpty { a.id }
        val where = when {
            local || sub?.primary == true -> "my location"
            sub != null -> sub.place.name
            else -> "?"
        }
        entries.add(0, HistoryEntry(System.currentTimeMillis(), a.id, kind, summary, where, a.quake?.magnitude, distanceKm, note))
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
                })
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {}
    }
}
