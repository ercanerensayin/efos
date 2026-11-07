package com.ercan.otomakro

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        MacroService.refresh(c)
        if (i.action == Intent.ACTION_BOOT_COMPLETED) Engine.fire(c, "boot")
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("id", -1L)
        val idx = i.getIntExtra("idx", 0)
        val code = i.getIntExtra("code", -1)
        Scheduler.clearNext(c, code)
        Engine.fireSpecific(c, id, idx)
        Scheduler.rescheduleAll(c)
    }
}

/** "Bildirim geldiğinde" tetikleyicisi için. Kullanıcının bildirim erişimi vermesi gerekir. */
class NotifListener : NotificationListenerService() {
    private val recent = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val ex = sbn.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Aynı bildirimin art arda güncellenmesini bir kez say
        val key = sbn.packageName + "|" + title + "|" + text
        val now = System.currentTimeMillis()
        val last = recent[key]
        if (last != null && now - last < 5000) return
        recent[key] = now
        if (recent.size > 50) recent.entries.removeAll { now - it.value > 60_000 }

        Engine.fire(
            this, "notification",
            mapOf(
                "pkg" to sbn.packageName,
                "title" to title,
                "text" to text,
                "app" to Sys.appLabel(this, sbn.packageName)
            )
        )
    }
}
