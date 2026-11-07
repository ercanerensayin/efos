package com.ercan.otomakro

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Makroları JSON dosyasında, günlüğü düz metin dosyasında saklar. */
object Store {
    private var cache: MutableList<Macro>? = null

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "macros.json")
    private fun logFile(ctx: Context) = File(ctx.applicationContext.filesDir, "log.txt")

    private fun load(ctx: Context): MutableList<Macro> {
        cache?.let { return it }
        val list = mutableListOf<Macro>()
        val f = file(ctx)
        if (f.exists()) {
            try {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) list.add(Macro.fromJson(a.getJSONObject(i)))
            } catch (e: Exception) {
                log(ctx, "Makro dosyası okunamadı: ${e.message}")
            }
        }
        cache = list
        return list
    }

    private fun persist(ctx: Context) {
        val a = JSONArray()
        for (m in load(ctx)) a.put(m.toJson())
        val f = file(ctx)
        val tmp = File(f.parentFile, "macros.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(f)
    }

    @Synchronized
    fun all(ctx: Context): List<Macro> = load(ctx).map { it.copyDeep() }

    @Synchronized
    fun get(ctx: Context, id: Long): Macro? = load(ctx).firstOrNull { it.id == id }?.copyDeep()

    @Synchronized
    fun save(ctx: Context, m: Macro) {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.id == m.id }
        if (idx >= 0) list[idx] = m.copyDeep() else list.add(m.copyDeep())
        persist(ctx)
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        load(ctx).removeAll { it.id == id }
        persist(ctx)
    }

    @Synchronized
    fun setEnabled(ctx: Context, id: Long, enabled: Boolean) {
        load(ctx).firstOrNull { it.id == id }?.enabled = enabled
        persist(ctx)
    }

    // ---------------- Günlük ----------------
    private val logLock = Any()

    fun log(ctx: Context, msg: String) {
        synchronized(logLock) {
            try {
                val f = logFile(ctx)
                val line = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date()) + "  " + msg
                val lines = (if (f.exists()) f.readLines() else emptyList()) + line
                f.writeText(lines.takeLast(300).joinToString("\n") + "\n")
            } catch (_: Exception) {
            }
        }
    }

    fun readLog(ctx: Context): String = synchronized(logLock) {
        val f = logFile(ctx)
        if (!f.exists()) "" else f.readLines().filter { it.isNotBlank() }.reversed().joinToString("\n")
    }

    fun clearLog(ctx: Context) = synchronized(logLock) { logFile(ctx).delete() }

    // ---------------- Örnek makrolar ----------------
    fun addSamples(ctx: Context) {
        val base = System.currentTimeMillis()
        val samples = listOf(
            Macro(
                base + 1, "Şarj takılınca haber ver",
                triggers = mutableListOf(Item.of("power", "state" to "connected")),
                actions = mutableListOf(
                    Item.of("notify", "title" to "Şarj başladı", "text" to "Pil şu an %{pil}"),
                    Item.of("tts", "text" to "Şarj başladı, pil yüzde {pil}")
                )
            ),
            Macro(
                base + 2, "Gece titreşim modu",
                triggers = mutableListOf(Item.of("time", "time" to "23:00", "days" to "")),
                actions = mutableListOf(
                    Item.of("ringer", "mode" to "vibrate"),
                    Item.of("notify", "title" to "Gece modu", "text" to "Telefon titreşime alındı")
                )
            ),
            Macro(
                base + 3, "Sabah normal mod",
                triggers = mutableListOf(Item.of("time", "time" to "07:30", "days" to "1,2,3,4,5")),
                actions = mutableListOf(Item.of("ringer", "mode" to "normal"))
            ),
            Macro(
                base + 4, "Düşük pil uyarısı",
                triggers = mutableListOf(Item.of("battery_level", "op" to "below", "level" to "20")),
                constraints = mutableListOf(Item.of("charging", "state" to "no")),
                actions = mutableListOf(
                    Item.of("vibrate", "ms" to "800"),
                    Item.of("tts", "text" to "Pil yüzde {pil}, lütfen şarja takın")
                )
            ),
            Macro(
                base + 5, "Salla → fener",
                triggers = mutableListOf(Item.of("shake", "sens" to "mid")),
                constraints = mutableListOf(Item.of("screen_state", "state" to "on")),
                actions = mutableListOf(Item.of("torch", "state" to "toggle"))
            ),
            Macro(
                base + 6, "Kulaklık takılınca müzik",
                triggers = mutableListOf(Item.of("headset", "state" to "plugged")),
                actions = mutableListOf(
                    Item.of("volume", "stream" to "media", "percent" to "60"),
                    Item.of("media", "cmd" to "play_pause")
                )
            )
        )
        for (m in samples) save(ctx, m)
    }
}
