package io.github.luccoj.alertroid

import android.content.Context
import kotlin.random.Random

/** Builds the topic subscriptions: the user's own cell plus three watched places ("decoys"). */
object Places {
    /** Automatic decoys: populated, seismically active places spread around the world. */
    val AUTO_DECOYS = listOf(
        Place("Tokyo", 35.68, 139.69), Place("Istanbul", 41.01, 28.98), Place("Lima", -12.05, -77.04),
        Place("Santiago", -33.45, -70.67), Place("Mexico City", 19.43, -99.13), Place("Manila", 14.60, 120.98),
        Place("Jakarta", -6.21, 106.85), Place("Kathmandu", 27.72, 85.32), Place("Tehran", 35.69, 51.39),
        Place("Los Angeles", 34.05, -118.24), Place("San Francisco", 37.77, -122.42), Place("Anchorage", 61.22, -149.90),
        Place("Wellington", -41.29, 174.78), Place("Taipei", 25.03, 121.57), Place("Quito", -0.18, -78.47),
        Place("Bogotá", 4.71, -74.07), Place("Guatemala City", 14.63, -90.51), Place("San Salvador", 13.69, -89.22),
        Place("Athens", 37.98, 23.73), Place("Naples", 40.85, 14.27), Place("Bucharest", 44.43, 26.10),
        Place("Lisbon", 38.72, -9.14), Place("Algiers", 36.75, 3.06), Place("Islamabad", 33.68, 73.05),
        Place("Christchurch", -43.53, 172.64), Place("Seattle", 47.61, -122.33), Place("Vancouver", 49.28, -123.12),
        Place("Port-au-Prince", 18.59, -72.31), Place("Caracas", 10.48, -66.90), Place("Padang", -0.95, 100.35),
        Place("Davao", 7.19, 125.46), Place("Sendai", 38.27, 140.87), Place("Izmir", 38.42, 27.14),
        Place("Reykjavík", 64.15, -21.94), Place("Almaty", 43.24, 76.89), Place("Valparaíso", -33.05, -71.62),
        Place("San José (CR)", 9.93, -84.08), Place("Managua", 12.11, -86.24), Place("Kingston", 18.02, -76.80),
        Place("Kunming", 25.04, 102.71), Place("Chengdu", 30.57, 104.07), Place("Dushanbe", 38.56, 68.79),
    )

    fun subscriptions(context: Context): List<Subscription> {
        val prefs = Prefs(context)
        val out = ArrayList<Subscription>()
        prefs.effectiveLocation()?.let { out += Subscription(Place("", it.lat, it.lon), primary = true) }
        val tokens = out.map { it.token }.toMutableSet()
        for (d in prefs.userDecoys) {
            val s = Subscription(d, primary = false)
            if (tokens.add(s.token)) out += s
        }
        val needed = 3 - prefs.userDecoys.size
        if (needed > 0) {
            var idx = prefs.autoDecoyIndices.filter { it in AUTO_DECOYS.indices }
            // Pick (once) random automatic decoys, and replace any that collide with a real cell.
            val chosen = ArrayList<Int>()
            for (i in idx) {
                if (chosen.size >= needed) break
                if (AUTO_DECOYS[i].token !in tokens) { chosen += i; tokens += AUTO_DECOYS[i].token }
            }
            val pool = AUTO_DECOYS.indices.shuffled(Random(System.nanoTime())).iterator()
            while (chosen.size < needed && pool.hasNext()) {
                val i = pool.next()
                if (i !in chosen && AUTO_DECOYS[i].token !in tokens) { chosen += i; tokens += AUTO_DECOYS[i].token }
            }
            if (chosen != idx) prefs.autoDecoyIndices = chosen
            idx = chosen
            idx.forEach { out += Subscription(AUTO_DECOYS[it], primary = false) }
        }
        return out
    }

    fun isAutoDecoy(place: Place) = AUTO_DECOYS.any { it == place }
}
