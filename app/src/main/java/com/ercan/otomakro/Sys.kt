package com.ercan.otomakro

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Cihaz durumunu okuyan küçük yardımcılar. */
object Sys {
    fun battery(ctx: Context): Int {
        val bm = ctx.getSystemService(BatteryManager::class.java) ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun charging(ctx: Context): Boolean =
        ctx.getSystemService(BatteryManager::class.java)?.isCharging == true

    fun screenOn(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isInteractive == true

    fun wifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    @Suppress("DEPRECATION")
    fun ssid(ctx: Context): String {
        return try {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return ""
            val s = (wm.connectionInfo?.ssid ?: "").trim('"')
            if (s == "<unknown ssid>") "" else s
        } catch (e: Exception) {
            ""
        }
    }

    fun headset(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        val types = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    /** Pazartesi = 1 ... Pazar = 7 */
    fun todayIdx(cal: Calendar = Calendar.getInstance()): Int =
        ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1

    fun nowMin(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun mins(hhmm: String?): Int {
        val p = (hhmm ?: "0:0").split(":")
        val h = p.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val m = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return h * 60 + m
    }

    fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** Metindeki {değişken}leri doldurur. */
    fun magic(ctx: Context, s: String, data: Map<String, String>): String {
        if (!s.contains('{')) return s
        val now = Date()
        var out = s
            .replace("{saat}", SimpleDateFormat("HH:mm", Locale.US).format(now))
            .replace("{tarih}", SimpleDateFormat("dd.MM.yyyy", Locale.US).format(now))
            .replace("{bildirim_baslik}", data["title"].orEmpty())
            .replace("{bildirim_metin}", data["text"].orEmpty())
            .replace("{uygulama}", data["app"].orEmpty())
        if (out.contains("{pil}")) out = out.replace("{pil}", battery(ctx).toString())
        if (out.contains("{wifi}")) out = out.replace("{wifi}", ssid(ctx))
        return out
    }
}
