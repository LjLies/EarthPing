package io.github.ljlies.earthping

import io.github.ljlies.earthping.geo.S2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S2Test {
    // Reference tokens (level 8) computed independently with a separate S2 implementation.
    private val vectors = listOf(
        Triple(6.2442, -75.5812, "8e443"),
        Triple(-33.4489, -70.6693, "9662d"),
        Triple(0.0, 0.0, "10001"),
        Triple(89.9, 10.0, "45555"),
        Triple(-89.9, -170.0, "a5555"),
        Triple(45.0, 179.999, "58001"),
        Triple(-45.0, -179.999, "a7fff"),
        Triple(35.68, 139.69, "6018f"),
        Triple(-31.7101, -125.6943, "9fa8b"),
        Triple(27.1682, -153.9229, "7e971"),
        Triple(6.4588, -48.352, "8d245"),
        Triple(-79.5602, 2.6769, "b07fd"),
        Triple(-83.2508, -23.8876, "ba975"),
        Triple(-77.426, -147.3433, "a5a77"),
        Triple(-13.5865, 117.6668, "2c3ed"),
        Triple(-67.7156, -99.634, "a4a0d"),
        Triple(22.938, 161.1752, "63f73"),
        Triple(13.8785, -37.195, "09983"),
        Triple(85.7259, -163.2302, "500c7"),
        Triple(64.5243, -75.7407, "4ddb9"),
        Triple(-64.0341, -137.5948, "a41a1"),
        Triple(-34.4733, 113.8055, "2a28d"),
        Triple(-57.4693, 29.3761, "b695b"),
        Triple(25.0044, -45.9369, "8b2c1"),
        Triple(8.594, -157.396, "7a2a1"),
        Triple(-79.2718, -105.8549, "a5279"),
        Triple(32.472, -26.0668, "0b771"),
        Triple(-33.4535, 30.8023, "1e44d"),
        Triple(-8.4268, -72.0839, "91995"),
        Triple(52.9883, 71.638, "42521"),
        Triple(-46.0626, 26.7925, "b5e11"),
        Triple(4.5354, 135.0495, "678a1"),
        Triple(41.3002, -76.3424, "89c57"),
        Triple(86.4315, -137.4963, "500ff"),
        Triple(-14.7379, 92.5707, "2f6f5"),
        Triple(-62.6428, -3.9733, "b9e67"),
        Triple(-82.9427, 60.5577, "b03c1"),
        Triple(47.6228, 26.2893, "4734f"),
        Triple(67.586, -67.0509, "4e7bf"),
        Triple(35.1532, 33.9732, "14dfb"),
        Triple(14.3811, -15.7661, "0ee97"),
        Triple(61.1942, 160.0852, "599e1"),
        Triple(-4.6623, 59.0948, "2317b"),
        Triple(-79.0795, 72.5371, "b0333"),
        Triple(26.4832, 177.5145, "63459"),
        Triple(57.9465, -77.5456, "4dbab"),
        Triple(-20.5575, 60.715, "21605"),
        Triple(-85.9387, -13.7897, "baa15"),
    )

    @Test fun level8Tokens() {
        for ((lat, lon, token) in vectors) assertEquals("token for $lat,$lon", token, S2.token(lat, lon, 8))
    }

    @Test fun knownCities() {
        assertEquals("8e443", S2.token(6.2442, -75.5812))   // Medellín (as seen in Play Services storage)
        assertEquals("9662d", S2.token(-33.4489, -70.6693)) // Santiago
    }

    @Test fun otherLevels() {
        assertEquals("133", S2.token(41.9028, 12.4964, 4))
        assertEquals("132f61b", S2.token(41.9028, 12.4964, 12))
        assertEquals("132f61a6161", S2.token(41.9028, 12.4964, 20))
        assertEquals("132f61a6161a1b21", S2.token(41.9028, 12.4964, 30))
    }

    @Test fun containment() {
        assertTrue(S2.related("133", "132f61b"))
        assertTrue(S2.related("132f61b", "133"))
        assertTrue(S2.related("8e443", "8e443"))
        assertFalse(S2.related("8e443", "9662d"))
        assertFalse(S2.related("", "9662d"))
    }
}
