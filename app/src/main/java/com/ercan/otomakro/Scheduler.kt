package com.ercan.otomakro

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** "Belirli saatte" ve "aralıklarla" tetikleyicileri için alarm kurar. */
object Scheduler {
    private const val PREF = "scheduler"

    private fun code(id: Long, idx: Int): Int = ((id % 1_000_000L).toInt()) * 16 + (idx and 15)

    private fun pending(ctx: Context, code: Int, id: Long, idx: Int, flag: Int): PendingIntent? {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .putExtra("id", id)
            .putExtra("idx", idx)
            .putExtra("code", code)
        return PendingIntent.getBroadcast(ctx, code, i, flag or PendingIntent.FLAG_IMMUTABLE)
    }

    @Synchronized
    fun rescheduleAll(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        // Eski alarmları iptal et
        for (c in sp.getStringSet("codes", emptySet()) ?: emptySet()) {
            val code = c.toIntOrNull() ?: continue
            pending(app, code, 0L, 0, PendingIntent.FLAG_NO_CREATE)?.let {
                am.cancel(it)
                it.cancel()
            }
        }

        val codes = mutableSetOf<String>()
        val ed = sp.edit()
        val now = System.currentTimeMillis()
        for (m in Store.all(app)) {
            if (!m.enabled) continue
            m.triggers.forEachIndexed { idx, t ->
                val code = code(m.id, idx)
                val at: Long = when (t.type) {
                    "time" -> nextTime(t) ?: return@forEachIndexed
                    "interval" -> {
                        val minutes = (Catalog.trigger("interval")!!.resolve(t)["minutes"]?.toIntOrNull() ?: 30).coerceAtLeast(1)
                        val stored = sp.getLong("next_$code", 0L)
                        val v = if (stored > now) stored else now + minutes * 60_000L
                        ed.putLong("next_$code", v)
                        v
                    }
                    else -> return@forEachIndexed
                }
                val pi = pending(app, code, m.id, idx, PendingIntent.FLAG_UPDATE_CURRENT) ?: return@forEachIndexed
                setAlarm(am, at, pi)
                codes.add(code.toString())
            }
        }
        ed.putStringSet("codes", codes).apply()
    }

    fun clearNext(ctx: Context, code: Int) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove("next_$code").apply()
    }

    private fun setAlarm(am: AlarmManager, at: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun nextTime(t: Item): Long? {
        val p = Catalog.trigger("time")?.resolve(t) ?: return null
        val total = Sys.mins(p["time"])
        val days = p["days"].orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
        val now = System.currentTimeMillis()
        for (d in 0..7) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, d)
            c.set(Calendar.HOUR_OF_DAY, total / 60)
            c.set(Calendar.MINUTE, total % 60)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= now + 1000) continue
            if (days.isEmpty() || Sys.todayIdx(c) in days) return c.timeInMillis
        }
        return null
    }
}
