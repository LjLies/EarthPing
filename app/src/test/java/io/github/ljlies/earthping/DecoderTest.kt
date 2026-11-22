package io.github.ljlies.earthping

import io.github.ljlies.earthping.alert.AlertDecoder
import io.github.ljlies.earthping.alert.GoogleAlert
import io.github.ljlies.earthping.geo.Geo
import io.github.ljlies.earthping.geo.LatLng
import io.github.ljlies.earthping.proto.ProtoMessage
import io.github.ljlies.earthping.proto.ProtoWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DecoderTest {
    private fun fixture(name: String): ByteArray =
        Base64.getMimeDecoder().decode(javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes())

    /** Real alert: southern Panama, 2026-10-31 (USGS us7000tujf). */
    @Test fun realEarthquakeAlert() {
        val alerts = AlertDecoder.decodeBatch(fixture("quake_panama_20261031_rawdata.b64"), "en")
        assertEquals(1, alerts.size)
        val a = alerts[0]
        assertEquals("8fad9-2026-10-31T15:50:25.645Z", a.id)
        assertEquals(GoogleAlert.Type.EARTHQUAKE, a.type)
        assertEquals(GoogleAlert.Mode.LIVE, a.mode)
        val q = a.quake!!
        assertEquals(5.0, q.magnitude!!, 1e-9)
        assertEquals(7.4815, q.epicenter!!.lat, 1e-3)
        assertEquals(-80.2544, q.epicenter!!.lon, 1e-3)
        assertEquals(9.999, q.depthKm!!, 1e-6)
        assertEquals(1793461820420L, q.originMs)       // 15:50:20.420 UTC
        assertEquals(1793461828486L, a.issuedMs)       // issued 8 s later
        assertEquals(listOf(1, 2, 3), a.contours.map { it.level })
        assertTrue(a.contours.all { it.rings.size == 1 && it.rings[0].size == 8 })
        // The epicentre is inside the MMI5+ contour.
        assertTrue(Geo.inside(q.epicenter!!, a.contours[0].rings[0]))
    }

    /** Real crisis alert: Hurricane Simon (SOS alert with a compressed S2Polygon area). */
    @Test fun realCrisisAlert() {
        val a = AlertDecoder.decodeBatch(fixture("crisis_hurricane_simon_rawdata.b64"), "it").single()
        assertEquals("cmid:610b3195d52aaa9e", a.id)
        assertEquals(GoogleAlert.Type.SOS_ALERT, a.type)
        assertFalse(a.isEarthquake)
        assertEquals("Uragano Simon", a.title)
        assertEquals(1, a.areaPolygons.size)
        val ring = a.areaPolygons[0]
        assertEquals(22, ring.size)
        // Reference vertices from an independent decoder (checked against s2geometry).
        assertEquals(15.58764, ring[0].lat, 1e-4); assertEquals(-99.23191, ring[0].lon, 1e-4)
        assertEquals(16.92332, ring[1].lat, 1e-4); assertEquals(-98.51906, ring[1].lon, 1e-4)
        assertEquals(15.55844, ring[21].lat, 1e-4); assertEquals(-99.90181, ring[21].lon, 1e-4)
        val en = AlertDecoder.decodeBatch(fixture("crisis_hurricane_simon_rawdata.b64"), "en").single()
        assertEquals("Hurricane Simon", en.title)
    }

    @Test fun testModeAndGarbage() {
        val info = ProtoWriter().float(1, 6.1f).message(2) { double(1, 6.4); double(2, -75.5) }
        val alert = ProtoWriter().message(1) { string(1, "x") }.varint(2, 2).varint(3, 5).message(14) { bytes(1, info.toByteArray()) }
        val a = AlertDecoder.decodeBatch(ProtoWriter().bytes(2, alert.toByteArray()).toByteArray()).single()
        assertEquals(GoogleAlert.Mode.TEST, a.mode)
        assertEquals(6.1, a.quake!!.magnitude!!, 1e-9)
        assertNull(a.quake!!.originMs)
        assertTrue(AlertDecoder.decodeBatch(byteArrayOf(1, 2, 3, 4, 5)).isEmpty())
        val rnd = java.util.Random(1)
        repeat(500) {
            val b = ByteArray(rnd.nextInt(200)).also { rnd.nextBytes(it) }
            AlertDecoder.decodeBatch(b) // must never throw
        }
    }

    @Test fun protoRoundTrip() {
        val b = ProtoWriter().varint(1, -1L).string(2, "héllo").fixed64(3, Long.MIN_VALUE).double(4, 1.5).toByteArray()
        val m = ProtoMessage.parseOrNull(b)!!
        assertEquals(-1L, m.long(1))
        assertEquals("héllo", m.string(2))
        assertEquals(Long.MIN_VALUE, m.long(3))
        assertEquals(1.5, m.double(4)!!, 0.0)
        assertNull(ProtoMessage.parseOrNull(byteArrayOf(0x0A, 0x05, 1)))
    }

    @Test fun intensityAndWaves() {
        assertEquals(6.9, Geo.estimateMmi(6.0, 14.0), 0.1)
        assertEquals(3.7, Geo.estimateMmi(5.0, 50.0), 0.1)
        val p = Geo.destination(LatLng(10.0, 20.0), 90.0, 100.0)
        assertEquals(100.0, Geo.distanceKm(LatLng(10.0, 20.0), p), 0.01)
        val r = Geo.radiusForMmi(6.0, 10.0, 5.0)!!
        assertEquals(5.0, Geo.estimateMmi(6.0, Geo.hypocentralKm(r, 10.0)), 0.01)
    }

    @Test fun checkinRequestShape() {
        val req = io.github.ljlies.earthping.gcm.Checkin.buildRequest(
            io.github.ljlies.earthping.gcm.DeviceProfile.generic("en_US", "UTC"), null)
        val m = ProtoMessage.parseOrNull(req)!!
        assertEquals(0L, m.long(2))
        assertEquals(3, m.int(14))
        assertNotNull(m.message(4)?.message(1)?.string(1))
        assertEquals(1, m.message(4)?.int(12))
    }
}
