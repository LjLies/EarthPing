package io.github.luccoj.alertroid

import android.app.Application
import io.github.luccoj.alertroid.ui.AppVisibility
import org.osmdroid.config.Configuration
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppVisibility.register(this)
        // osmdroid: identify ourselves to the OSM tile servers and cache tiles in private storage.
        Configuration.getInstance().apply {
            userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
            tileFileSystemCacheMaxBytes = 80L * 1024 * 1024
        }
    }
}
