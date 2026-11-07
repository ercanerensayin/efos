package com.ercan.otomakro

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Özel izinlerin durumu ve ayar ekranlarına yönlendirme. */
object Perms {
    class P(val key: String, val title: String, val why: String, val ok: (Context) -> Boolean)

    val list = listOf(
        P("post", "Bildirim gösterme", "Makro bildirimleri ve servis bildirimi için.") { Notif.canPost(it) },
        P("listener", "Bildirim erişimi", "“Bildirim geldiğinde” tetikleyicisi için.") { notifListener(it) },
        P("dnd", "Rahatsız Etme erişimi", "Sessiz mod ve Rahatsız Etme eylemleri için.") {
            it.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
        },
        P("write", "Sistem ayarlarını değiştirme", "Ekran parlaklığı eylemi için.") { Settings.System.canWrite(it) },
        P("overlay", "Diğer uygulamaların üzerinde gösterme", "Arka plandan uygulama / web adresi açabilmek için.") {
            Settings.canDrawOverlays(it)
        },
        P("battery", "Pil optimizasyonundan muaf", "Telefonun servisi kapatmaması için (önerilir).") {
            it.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(it.packageName) == true
        },
        P("location", "Konum", "Wi-Fi ağ adını (SSID) okuyabilmek için.") {
            it.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    )

    fun notifListener(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true

    fun open(act: Activity, key: String) {
        val pkgUri = Uri.parse("package:" + act.packageName)
        try {
            when (key) {
                "post" -> if (Build.VERSION.SDK_INT >= 33) {
                    act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
                }
                "listener" -> act.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                "dnd" -> act.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                "write" -> act.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri))
                "overlay" -> act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri))
                "battery" -> act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri))
                "location" -> act.requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 11
                )
            }
        } catch (e: Exception) {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
        }
    }

    fun showAll(act: Activity) {
        val items = list.map { (if (it.ok(act)) "✅  " else "❌  ") + it.title + "\n      " + it.why }
        AlertDialog.Builder(act)
            .setTitle("İzinler (ayarlamak için dokunun)")
            .setItems(items.toTypedArray()) { _, w -> open(act, list[w].key) }
            .setPositiveButton("Kapat", null)
            .show()
    }

    /** Eklenen tetikleyici/eylem bir izin gerektiriyorsa kullanıcıya sorar. */
    fun askFor(act: Activity, type: String, params: Map<String, String> = emptyMap()) {
        val key = when (type) {
            "notification" -> "listener"
            "dnd" -> "dnd"
            "ringer" -> if (params["mode"] == "silent") "dnd" else null
            "brightness" -> "write"
            "launch_app", "open_url" -> "overlay"
            "wifi" -> if (!params["ssid"].isNullOrBlank()) "location" else null
            "notify" -> "post"
            else -> null
        } ?: return
        val p = list.first { it.key == key }
        if (p.ok(act)) return
        AlertDialog.Builder(act)
            .setTitle("İzin gerekiyor")
            .setMessage("“${p.title}” izni gerekli.\n\n${p.why}")
            .setPositiveButton("Ayarları aç") { _, _ -> open(act, key) }
            .setNegativeButton("Sonra", null)
            .show()
    }
}
