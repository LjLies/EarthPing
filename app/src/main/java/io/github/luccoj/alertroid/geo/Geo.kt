package io.github.luccoj.alertroid.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lon: Double)

object Geo {
    const val EARTH_RADIUS_KM = 6371.0088

    /** Typical crustal wave speeds used when the alert carries none. */
    const val P_WAVE_KMS = 6.0
    const val S_WAVE_KMS = 3.5

    fun distanceKm(a: LatLng, b: LatLng): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Point at [distanceKm] from [from] along initial [bearingDeg]. */
    fun destination(from: LatLng, bearingDeg: Double, distanceKm: Double): LatLng {
        val d = distanceKm / EARTH_RADIUS_KM
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(from.lat); val l1 = Math.toRadians(from.lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLng(Math.toDegrees(p2), ((Math.toDegrees(l2) + 540) % 360) - 180)
    }

    fun circle(center: LatLng, radiusKm: Double, points: Int = 72): List<LatLng> =
        (0 until points).map { destination(center, 360.0 * it / points, radiusKm) }

    /** Ray-casting point-in-polygon on lat/lng (fine for alert-sized areas away from the antimeridian). */
    fun inside(p: LatLng, ring: List<LatLng>): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]; val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat)) {
                val lonAt = a.lon + (p.lat - a.lat) * (b.lon - a.lon) / (b.lat - a.lat)
                if (p.lon < lonAt) inside = !inside
            }
            j = i
        }
        return inside
    }

    fun minDistanceKm(p: LatLng, ring: List<LatLng>): Double =
        if (inside(p, ring)) 0.0 else ring.minOfOrNull { distanceKm(p, it) } ?: Double.MAX_VALUE

    fun hypocentralKm(epicentralKm: Double, depthKm: Double?): Double {
        val d = depthKm ?: 10.0
        return sqrt(epicentralKm * epicentralKm + d * d)
    }

    /**
     * Median Modified Mercalli intensity at hypocentral distance [hypoKm] for magnitude [m]
     * (Allen, Wald & Worden 2012, active crustal regions, hypocentral form).
     */
    fun estimateMmi(m: Double, hypoKm: Double): Double {
        val rm = -0.209 + 2.042 * exp(m - 5)
        val r = hypoKm.coerceAtLeast(0.1)
        var mmi = 2.085 + 1.428 * m - 1.402 * ln(sqrt(r * r + rm * rm))
        if (r > 50) mmi += 0.078 * ln(r / 50)
        return mmi.coerceIn(1.0, 10.0)
    }

    /** Epicentral distance (km) at which [estimateMmi] drops to [target], by bisection. */
    fun radiusForMmi(m: Double, depthKm: Double, target: Double): Double? {
        if (estimateMmi(m, depthKm) < target) return null
        var lo = 0.0; var hi = 2000.0
        repeat(60) {
            val mid = (lo + hi) / 2
            if (estimateMmi(m, hypocentralKm(mid, depthKm)) >= target) lo = mid else hi = mid
        }
        return lo
    }

    private val ROMAN = arrayOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
    fun roman(mmi: Double): String = ROMAN[(Math.round(mmi).toInt().coerceIn(1, 10)) - 1]
}
