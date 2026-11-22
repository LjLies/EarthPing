package io.github.ljlies.earthping.gcm

import io.github.ljlies.earthping.proto.ProtoMessage
import io.github.ljlies.earthping.proto.ProtoWriter
import io.github.ljlies.earthping.proto.Wire
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class LoginRejectedException(message: String) : IOException(message)

data class DataMessage(
    val from: String?,
    val category: String?,
    val persistentId: String?,
    val appData: Map<String, ByteArray>,
    val rawData: ByteArray?,
    val receivedAtMs: Long,
) {
    fun appString(key: String) = appData[key]?.toString(Charsets.UTF_8)
}

/**
 * Client for Google's Mobile Connection Server (mtalk.google.com), the long-lived TLS socket
 * that carries Android push messages. Earthquake and crisis alerts are delivered to whoever
 * subscribes to `ea.<S2 level-8 cell token>` topics in the LoginRequest.
 *
 * Threading: [connect] blocks the caller; afterwards a dedicated reader thread delivers frames.
 * Writes are serialized, so heartbeats may be sent from any thread.
 */
class McsClient(
    private val creds: Credentials,
    val topics: List<String>,
    private val heartbeatMs: Long,
    private val sdkInt: Int,
    private val pendingPersistentIds: List<String>,
    private val listener: Listener,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
) {
    interface Listener {
        fun onDataMessage(client: McsClient, msg: DataMessage)
        fun onDisconnected(client: McsClient, error: Throwable?)
        fun onFrame(client: McsClient) {}
    }

    companion object {
        const val HOST = "mtalk.google.com"
        val PORTS = intArrayOf(5228, 443)
        private const val VERSION = 41
        private const val MAX_FRAME = 4 * 1024 * 1024

        const val TAG_PING = 0
        const val TAG_ACK = 1
        const val TAG_LOGIN_REQ = 2
        const val TAG_LOGIN_RESP = 3
        const val TAG_CLOSE = 4
        const val TAG_IQ = 7
        const val TAG_DATA = 8

        private const val IQ_SET = 1
        private const val EXT_SELECTIVE_ACK = 12
        private const val CONTROL_CATEGORY = "com.google.android.gsf.gtalkservice"

        @Volatile var preferredPort = PORTS[0]
    }

    @Volatile private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val writeLock = Any()
    @Volatile private var closed = false
    @Volatile var streamIdIn = 0
        private set

    @Volatile var lastRxElapsed = 0L
        private set
    @Volatile var serverHeartbeatMs: Long? = null
        private set
    @Volatile var serverTimeOffsetMs: Long? = null
        private set
    @Volatile var port = 0
        private set

    val isOpen get() = socket != null && !closed

    private fun now() = android.os.SystemClock.elapsedRealtime()

    private fun open(port: Int): SSLSocket {
        val raw = socketFactory.createSocket()
        try {
            raw.keepAlive = true
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(HOST, port), 15_000)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, HOST, port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }
            ssl.soTimeout = 20_000
            ssl.startHandshake()
            return ssl
        } catch (e: Exception) {
            try { raw.close() } catch (_: IOException) {}
            throw e
        }
    }

    private fun loginRequest(): ByteArray {
        val w = ProtoWriter()
        w.string(1, "android-$sdkInt")
        w.string(2, "mcs.android.com")
        val id = java.lang.Long.toUnsignedString(creds.androidId)
        w.string(3, id)
        w.string(4, id)
        w.string(5, java.lang.Long.toUnsignedString(creds.securityToken))
        w.string(6, "android-" + java.lang.Long.toHexString(creds.androidId))
        w.message(8) { string(1, "new_vc"); string(2, "1") }
        w.message(8) { string(1, "hbping"); string(2, heartbeatMs.toString()) }
        pendingPersistentIds.takeLast(100).forEach { w.string(10, it) }
        w.bool(12, false)          // adaptive heartbeat
        w.bool(14, true)           // use_rmq2
        w.varint(16, 2)            // auth service: ANDROID_ID
        w.varint(17, 1)            // network type
        topics.forEach { w.string(29, it) }
        return w.toByteArray()
    }

    /** Opens the socket and logs in. Throws on failure; on success starts the reader thread. */
    fun connect() {
        val ports = intArrayOf(preferredPort) + PORTS.filter { it != preferredPort }
        var lastError: Exception? = null
        var s: SSLSocket? = null
        for (p in ports) {
            if (closed) throw IOException("Closed")
            try {
                s = open(p)
                port = p
                preferredPort = p
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (s == null) throw (lastError as? IOException ?: IOException("Cannot reach $HOST", lastError))
        socket = s
        if (closed) { close(); throw IOException("Closed") }
        val ins = BufferedInputStream(s.inputStream, 16384)
        val out = s.outputStream
        input = ins
        output = out
        val login = loginRequest()
        val tSend = System.currentTimeMillis()
        synchronized(writeLock) {
            val frame = java.io.ByteArrayOutputStream()
            frame.write(VERSION)
            frame.write(TAG_LOGIN_REQ)
            Wire.writeVarint(frame, login.size.toLong())
            frame.write(login)
            out.write(frame.toByteArray())
            out.flush()
        }
        val version = ins.read()
        if (version < 0) throw IOException("Connection closed during login")
        if (version !in 38..63) throw IOException("Unexpected MCS version $version (is a proxy intercepting TLS?)")
        val (tag, payload) = readFrame(ins)
        val tRecv = System.currentTimeMillis()
        if (tag == TAG_CLOSE) throw IOException("Server closed the connection during login")
        if (tag != TAG_LOGIN_RESP) throw IOException("Expected LoginResponse, got tag $tag")
        streamIdIn = 1
        val resp = ProtoMessage.parse(payload)
        resp.message(3)?.let { err ->
            val code = err.int(1) ?: 0
            if (code != 0) throw LoginRejectedException("Login rejected (code $code${err.string(2)?.let { ": $it" } ?: ""})")
        }
        resp.message(7)?.long(3)?.takeIf { it > 0 }?.let { serverHeartbeatMs = it }
        resp.long(8)?.takeIf { it > 946684800000L }?.let { serverTimeOffsetMs = it - (tSend + tRecv) / 2 }
        lastRxElapsed = now()
        s.soTimeout = 0  // block indefinitely; liveness is checked with heartbeats
        Thread({ readLoop(ins) }, "mcs-reader").apply { isDaemon = true }.start()
    }

    private fun readFrame(ins: InputStream): Pair<Int, ByteArray> {
        val tag = ins.read()
        if (tag < 0) throw IOException("Connection closed by server")
        val len = Wire.readVarint(ins)
        if (len < 0 || len > MAX_FRAME) throw IOException("Bad frame length $len")
        val buf = ByteArray(len.toInt())
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("Connection closed mid-frame")
            off += n
        }
        return tag to buf
    }

    private fun readLoop(ins: InputStream) {
        var error: Throwable? = null
        try {
            while (!closed) {
                val (tag, payload) = readFrame(ins)
                lastRxElapsed = now()
                streamIdIn++
                listener.onFrame(this)
                when (tag) {
                    TAG_PING -> send(TAG_ACK, ProtoWriter().varint(2, streamIdIn).toByteArray())
                    TAG_ACK, TAG_IQ -> Unit
                    TAG_DATA -> handleData(payload)
                    TAG_CLOSE -> throw IOException("Server sent Close")
                    TAG_LOGIN_RESP -> Unit
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            error = t
        }
        val wasClosed = closed
        close()
        listener.onDisconnected(this, if (wasClosed) null else error)
    }

    private fun handleData(payload: ByteArray) {
        val m = ProtoMessage.parse(payload)
        val category = m.string(5)
        val appData = LinkedHashMap<String, ByteArray>()
        m.messages(7).forEach { kv -> kv.string(1)?.let { k -> appData[k] = kv.bytes(2) ?: ByteArray(0) } }
        val pid = m.string(9)
        if (!pid.isNullOrEmpty()) sendSelectiveAck(pid)
        if (category == CONTROL_CATEGORY) {
            if (appData["IdleNotification"] != null || m.messages(7).any { it.string(1) == "IdleNotification" }) replyIdle()
            return
        }
        val raw = m.bytes(21) ?: appData["rawData"]
        listener.onDataMessage(this, DataMessage(m.string(3), category, pid, appData, raw, System.currentTimeMillis()))
    }

    private fun replyIdle() {
        val w = ProtoWriter()
            .string(3, "gcm@android.com")
            .string(5, CONTROL_CATEGORY)
            .message(7) { string(1, "IdleNotification"); string(2, "false") }
            .varint(11, streamIdIn)
            .varint(17, 0)
            .varint(18, System.currentTimeMillis() / 1000)
        send(TAG_DATA, w.toByteArray())
    }

    private fun sendSelectiveAck(pid: String) {
        val w = ProtoWriter()
            .varint(2, IQ_SET)
            .string(3, "")
            .message(7) { varint(1, EXT_SELECTIVE_ACK); message(2) { string(1, pid) } }
            .varint(10, streamIdIn)
        send(TAG_IQ, w.toByteArray())
    }

    fun sendHeartbeat() = send(TAG_PING, ProtoWriter().varint(2, streamIdIn).toByteArray())

    private fun send(tag: Int, payload: ByteArray) {
        val out = output ?: throw IOException("Not connected")
        synchronized(writeLock) {
            val frame = java.io.ByteArrayOutputStream(payload.size + 6)
            frame.write(tag)
            Wire.writeVarint(frame, payload.size.toLong())
            frame.write(payload)
            out.write(frame.toByteArray())
            out.flush()
        }
    }

    fun close() {
        closed = true
        val s = socket
        socket = null
        try { s?.close() } catch (_: IOException) {}
    }
}
