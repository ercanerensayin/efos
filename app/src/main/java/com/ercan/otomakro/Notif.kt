package com.ercan.otomakro

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger

object Notif {
    const val CH_SERVICE = "service"
    const val CH_MACRO = "macro"
    private val counter = AtomicInteger(100)

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Arka plan servisi", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MACRO, "Makro bildirimleri", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(ctx: Context, title: String, text: String, intent: Intent? = null) {
        if (!canPost(ctx)) throw IllegalStateException("Bildirim izni verilmemiş")
        ensure(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val id = counter.incrementAndGet()
        val target = intent ?: Intent(ctx, MainActivity::class.java)
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, id, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CH_MACRO)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(id, n)
    }
}
