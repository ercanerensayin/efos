package com.ercan.efos

import android.content.Context
import android.graphics.Color
import org.json.JSONObject
import java.util.Locale

/** ortak/efos.json dosyasını okur (Windows sürümü de aynı dosyayı kullanır). */
object Ortak {
    private lateinit var r: JSONObject

    fun init(ctx: Context) {
        if (::r.isInitialized) return
        r = JSONObject(ctx.assets.open("efos.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    fun str(k: String): String = r.getString(k)
    fun t(k: String): String = r.getJSONObject("metinler").getString(k)
    fun tOr(k: String, fallback: String): String = r.getJSONObject("metinler").optString(k, fallback)
    fun renk(k: String): Int = Color.parseColor(r.getJSONObject("renkler").getString(k))
    val cikti: String get() = r.getJSONObject("ytdlp").getString("cikti")

    fun args(k: String): MutableList<String> {
        val a = r.getJSONObject("ytdlp").getJSONArray(k)
        return MutableList(a.length()) { a.getString(it) }
    }

    val kaliteler: List<Pair<String, String>>
        get() {
            val a = r.getJSONObject("ytdlp").getJSONArray("kaliteler")
            return List(a.length()) { a.getJSONObject(it).let { o -> o.getString("ad") to o.getString("format") } }
        }

    /** yt-dlp hata metnini anlaşılır Türkçe mesaja çevirir. */
    fun friendly(err: String?): String {
        if (err.isNullOrBlank()) return str("bilinmeyenHata")
        val low = err.lowercase(Locale.ROOT)
        val rules = r.getJSONArray("hatalar")
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val ara = rule.getJSONArray("ara")
            for (j in 0 until ara.length()) {
                if (low.contains(ara.getString(j).lowercase(Locale.ROOT))) return rule.getString("mesaj")
            }
        }
        return "Hata: $err"
    }
}
