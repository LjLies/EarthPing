package io.github.ljlies.earthping

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Diagnostic log shown in the app. Kept on the device only, in a small file so that it
 * survives restarts (the interesting events often happen while the app is in the background).
 */
object EventLog {
    private const val MAX = 600
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "eventlog").apply { isDaemon = true } }
    private var file: File? = null
    private var dirty = 0

    fun init(ctx: Context) {
        synchronized(lines) {
            if (file != null) return
            val f = File(ctx.filesDir, "eventlog.txt")
            file = f
            try {
                if (f.exists()) {
                    val old = f.readLines().takeLast(MAX)
                    val current = lines.toList()
                    lines.clear()
                    lines.addAll(old)
                    lines.addAll(current)
                }
            } catch (_: Exception) {}
        }
    }

    fun log(msg: String) {
        Log.i("EarthPing", msg)
        synchronized(lines) {
            lines.addLast("${fmt.format(Date())}  $msg")
            while (lines.size > MAX) lines.removeFirst()
            dirty++
        }
        writer.execute { flush() }
    }

    private fun flush() {
        val f = file ?: return
        val snapshot = synchronized(lines) {
            if (dirty == 0) return
            dirty = 0
            lines.toList()
        }
        try {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(snapshot.joinToString("\n", postfix = "\n"))
            tmp.renameTo(f)
        } catch (_: Exception) {}
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear(); dirty++ }
        writer.execute { flush() }
    }
}
