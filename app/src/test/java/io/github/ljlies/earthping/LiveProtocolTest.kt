package io.github.ljlies.earthping

import io.github.ljlies.earthping.gcm.Checkin
import io.github.ljlies.earthping.gcm.DataMessage
import io.github.ljlies.earthping.gcm.DeviceProfile
import io.github.ljlies.earthping.gcm.McsClient
import io.github.ljlies.earthping.geo.S2
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to Google's real servers: anonymous checkin, then MCS login with an `ea.` topic.
 * Only runs with EARTHPING_LIVE=1 (e.g. `EARTHPING_LIVE=1 ./gradlew testDebugUnitTest`).
 */
class LiveProtocolTest {
    @Test fun checkinAndLogin() {
        assumeTrue(System.getenv("EARTHPING_LIVE") == "1")
        val creds = Checkin.run(DeviceProfile.generic("en_US", "UTC"), null)
        println("checkin ok: id=${java.lang.Long.toHexString(creds.androidId)} interval=${creds.checkinIntervalMs / 3600000} h, clock offset ${Checkin.lastServerTimeOffsetMs} ms")
        val topics = listOf("ea." + S2.token(41.9028, 12.4964), "ea." + S2.token(35.68, 139.69))
        var disconnected: Throwable? = null
        val client = McsClient(creds, topics, 300_000, 34, emptyList(), object : McsClient.Listener {
            override fun onDataMessage(client: McsClient, msg: DataMessage) {
                println("data message from=${msg.from} category=${msg.category} keys=${msg.appData.keys} raw=${msg.rawData?.size}")
            }
            override fun onDisconnected(client: McsClient, error: Throwable?) { disconnected = error }
        })
        client.connect()
        println("login ok on port ${client.port}, server heartbeat ${client.serverHeartbeatMs}, clock offset ${client.serverTimeOffsetMs} ms")
        client.sendHeartbeat()
        Thread.sleep(8000)
        println("streamIdIn=${client.streamIdIn} (>1 means the server answered the heartbeat)")
        client.close()
        Thread.sleep(200)
        assert(client.streamIdIn > 1) { "no answer to heartbeat; disconnected=$disconnected" }
    }
}
