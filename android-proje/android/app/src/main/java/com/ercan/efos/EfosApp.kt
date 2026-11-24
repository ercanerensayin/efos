package com.ercan.efos

import android.app.Application
import java.io.File

/** Çökme olursa izini dosyaya yazar; uygulama bir sonraki açılışta gösterir. */
class EfosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { File(filesDir, "cokme.txt").writeText(e.stackTraceToString()) }
            previous?.uncaughtException(t, e)
        }
    }
}
