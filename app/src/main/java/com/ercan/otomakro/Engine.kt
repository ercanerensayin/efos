package com.ercan.otomakro

import android.content.Context
import java.util.concurrent.Executors

/** Olayları makrolarla eşleştirir, koşulları kontrol eder, eylemleri sırayla çalıştırır. */
object Engine {
    @Volatile
    var lastBattery = -1

    @Volatile
    var torchOn = false

    private val pool = Executors.newCachedThreadPool()

    fun fire(ctx: Context, type: String, data: Map<String, String> = emptyMap()) {
        val app = ctx.applicationContext
        for (m in Store.all(app)) {
            if (!m.enabled) continue
            val hit = m.triggers.firstOrNull { it.type == type && matches(it, data) } ?: continue
            val src = Catalog.trigger(hit.type)?.describe(hit) ?: type
            run(app, m, src, data, force = false)
        }
    }

    /** Zamanlayıcıdan gelen, belirli bir makroya ait tetikleme. */
    fun fireSpecific(ctx: Context, macroId: Long, idx: Int) {
        val app = ctx.applicationContext
        val m = Store.get(app, macroId) ?: return
        if (!m.enabled) return
        val t = m.triggers.getOrNull(idx)
        val src = if (t != null) Catalog.trigger(t.type)?.describe(t) ?: "zaman" else "zaman"
        run(app, m, src, emptyMap(), force = false)
    }

    fun run(ctx: Context, m: Macro, src: String, data: Map<String, String>, force: Boolean) {
        val app = ctx.applicationContext
        if (!force) {
            val failed = m.constraints.firstOrNull { !safeCheck(app, it) }
            if (failed != null) {
                val d = Catalog.constraint(failed.type)?.describe(failed) ?: failed.type
                Store.log(app, "«${m.name}» atlandı – koşul sağlanmadı: $d")
                return
            }
        }
        Store.log(app, "«${m.name}» çalıştı ← $src")
        pool.execute {
            for (a in m.actions) {
                try {
                    Actions.run(app, a, data)
                } catch (e: Throwable) {
                    val d = Catalog.action(a.type)?.title ?: a.type
                    Store.log(app, "  ✗ $d: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun safeCheck(ctx: Context, c: Item): Boolean = try {
        Conditions.check(ctx, c)
    } catch (e: Exception) {
        true
    }

    private fun matches(t: Item, d: Map<String, String>): Boolean {
        val def = Catalog.trigger(t.type) ?: return false
        val p = def.resolve(t)
        return when (t.type) {
            "battery_level" -> {
                val lvl = p["level"]?.toIntOrNull() ?: return false
                val cur = d["level"]?.toIntOrNull() ?: return false
                val prev = d["prev"]?.toIntOrNull() ?: -1
                when {
                    prev < 0 -> false
                    p["op"] == "below" -> prev >= lvl && cur < lvl
                    else -> prev <= lvl && cur > lvl
                }
            }
            "power", "screen", "headset", "airplane" -> p["state"] == d["state"]
            "wifi" -> {
                val want = p["ssid"].orEmpty().trim()
                p["state"] == d["state"] && (want.isEmpty() || want.equals(d["ssid"].orEmpty(), ignoreCase = true))
            }
            "notification" -> {
                val app = p["app"].orEmpty()
                val c = p["contains"].orEmpty().trim()
                val body = d["title"].orEmpty() + " " + d["text"].orEmpty()
                (app.isEmpty() || app == d["pkg"]) && (c.isEmpty() || body.contains(c, ignoreCase = true))
            }
            "shake" -> {
                val g = d["g"]?.toFloatOrNull() ?: return false
                val thr = when (p["sens"]) {
                    "low" -> 3.2f
                    "high" -> 2.0f
                    else -> 2.6f
                }
                g >= thr
            }
            "boot" -> true
            else -> false
        }
    }
}
