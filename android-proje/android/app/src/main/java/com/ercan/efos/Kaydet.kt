package com.ercan.efos

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** İndirilen dosyayı telefonun herkese açık İndirilenler/Videolar klasörüne taşır. */
object Kaydet {
    fun mime(f: File): String {
        val ext = f.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }

    fun toDownloads(ctx: Context, src: File): Uri? {
        val folder = Ortak.str("klasor")
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                put(MediaStore.Downloads.MIME_TYPE, mime(src))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                cr.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                cr.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                cr.delete(uri, null, null); throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder)
            dir.mkdirs()
            var dst = File(dir, src.name)
            var n = 1
            while (dst.exists()) dst = File(dir, "${src.nameWithoutExtension} (${n++}).${src.extension}")
            src.copyTo(dst)
            var result: Uri? = null
            val done = CountDownLatch(1)
            MediaScannerConnection.scanFile(ctx, arrayOf(dst.path), arrayOf(mime(dst))) { _, uri -> result = uri; done.countDown() }
            done.await(5, TimeUnit.SECONDS)
            result
        }
    }
}
