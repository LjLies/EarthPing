package io.github.ljlies.earthping

import android.content.Context
import android.os.UserManager
import java.io.File

/**
 * All app state lives in device-protected storage, so that the service can start and receive
 * alerts after a reboot, before the phone is unlocked for the first time.
 */
object Storage {
    @Volatile private var cached: Pair<Context, Context>? = null

    fun of(ctx: Context): Context {
        val app = ctx.applicationContext ?: ctx
        cached?.let { if (it.first === app) return it.second }
        val de = app.createDeviceProtectedStorageContext() ?: app
        cached = app to de
        return de
    }

    fun unlocked(ctx: Context): Boolean = ctx.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    /** Moves state written by older versions out of credential-protected storage. */
    fun migrate(ctx: Context): List<String> {
        val app = ctx.applicationContext
        val target = of(app)
        if (target === app || !unlocked(app)) return emptyList()
        val moved = ArrayList<String>()
        for (name in listOf(Prefs.FILE)) {
            if (File(app.dataDir, "shared_prefs/$name.xml").exists() && target.moveSharedPreferencesFrom(app, name)) moved += "$name.xml"
        }
        for (name in listOf("eventlog.txt", "history.json")) {
            val src = File(app.filesDir, name)
            if (!src.exists()) continue
            val dst = File(target.filesDir, name)
            try {
                if (!src.renameTo(dst)) { src.copyTo(dst, overwrite = true); src.delete() }
                moved += name
            } catch (_: Exception) {}
        }
        File(app.filesDir, "osmdroid").takeIf { it.exists() }?.deleteRecursively()
        File(app.cacheDir, "osmdroid-tiles").takeIf { it.exists() }?.deleteRecursively()
        return moved
    }
}
