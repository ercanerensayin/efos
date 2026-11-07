package com.ercan.otomakro

import android.content.Context

object Conditions {
    fun check(ctx: Context, c: Item): Boolean {
        val def = Catalog.constraint(c.type) ?: return true
        val p = def.resolve(c)
        return when (c.type) {
            "time_range" -> {
                val now = Sys.nowMin()
                val f = Sys.mins(p["from"])
                val t = Sys.mins(p["to"])
                if (f <= t) now in f until t else (now >= f || now < t)
            }
            "days" -> {
                val s = p["days"].orEmpty()
                s.isBlank() || s.split(",").mapNotNull { it.trim().toIntOrNull() }.contains(Sys.todayIdx())
            }
            "battery" -> {
                val b = Sys.battery(ctx)
                val l = p["level"]?.toIntOrNull() ?: 50
                if (p["op"] == "below") b < l else b > l
            }
            "charging" -> Sys.charging(ctx) == (p["state"] == "yes")
            "screen_state" -> Sys.screenOn(ctx) == (p["state"] == "on")
            "wifi_state" -> Sys.wifi(ctx) == (p["state"] == "connected")
            "headset_state" -> Sys.headset(ctx) == (p["state"] == "connected")
            else -> true
        }
    }
}
