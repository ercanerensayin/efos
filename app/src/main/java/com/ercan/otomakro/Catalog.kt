package com.ercan.otomakro

enum class Kind { TEXT, NUMBER, CHOICE, TIME, DAYS, APP }

class Field(
    val key: String,
    val label: String,
    val kind: Kind,
    val options: List<Pair<String, String>> = emptyList(),
    val default: String = "",
    val optional: Boolean = false
)

/** Açıklama yazarken parametrelere kolay erişim. */
class V(private val def: Def, private val map: Map<String, String>) {
    operator fun get(k: String): String = map[k] ?: ""

    /** Seçim alanının görünen etiketi. */
    fun l(k: String): String {
        val f = def.fields.firstOrNull { it.key == k }
        val v = get(k)
        return f?.options?.firstOrNull { it.first == v }?.second ?: v
    }
}

class Def(
    val type: String,
    val title: String,
    val fields: List<Field> = emptyList(),
    private val desc: (V) -> String
) {
    fun resolve(item: Item): Map<String, String> {
        val m = mutableMapOf<String, String>()
        for (f in fields) m[f.key] = f.default
        m.putAll(item.params)
        return m
    }

    fun describe(item: Item): String = try {
        desc(V(this, resolve(item)))
    } catch (e: Exception) {
        title
    }
}

object Catalog {
    val DAY_SHORT = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
    val DAY_FULL = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")

    fun daysText(s: String?): String {
        val d = (s ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.sorted()
        if (d.isEmpty() || d.size == 7) return "her gün"
        if (d == listOf(1, 2, 3, 4, 5)) return "hafta içi"
        if (d == listOf(6, 7)) return "hafta sonu"
        return d.joinToString(", ") { DAY_SHORT[it - 1] }
    }

    private fun short(s: String, n: Int = 40) = if (s.length > n) s.take(n) + "…" else s

    // ---------------- TETİKLEYİCİLER ----------------
    val triggers: List<Def> = listOf(
        Def(
            "time", "Belirli saatte",
            listOf(
                Field("time", "Saat", Kind.TIME, default = "08:00"),
                Field("days", "Günler", Kind.DAYS)
            )
        ) { "Saat ${it["time"]} • ${daysText(it["days"])}" },

        Def(
            "interval", "Belirli aralıklarla",
            listOf(Field("minutes", "Kaç dakikada bir", Kind.NUMBER, default = "30"))
        ) { "Her ${it["minutes"]} dakikada bir" },

        Def(
            "battery_level", "Pil seviyesi",
            listOf(
                Field("op", "Durum", Kind.CHOICE, listOf("below" to "Altına düşünce", "above" to "Üstüne çıkınca"), "below"),
                Field("level", "Seviye (%)", Kind.NUMBER, default = "20")
            )
        ) { "Pil %${it["level"]} " + if (it["op"] == "below") "altına düşünce" else "üstüne çıkınca" },

        Def(
            "power", "Şarj takma / çıkarma",
            listOf(Field("state", "Durum", Kind.CHOICE, listOf("connected" to "Takıldığında", "disconnected" to "Çıkarıldığında"), "connected"))
        ) { if (it["state"] == "connected") "Şarj takıldığında" else "Şarj çıkarıldığında" },

        Def(
            "screen", "Ekran / kilit",
            listOf(
                Field(
                    "state", "Durum", Kind.CHOICE,
                    listOf("on" to "Ekran açıldığında", "off" to "Ekran kapandığında", "unlocked" to "Kilit açıldığında"), "on"
                )
            )
        ) { it.l("state") },

        Def(
            "headset", "Kablolu kulaklık",
            listOf(Field("state", "Durum", Kind.CHOICE, listOf("plugged" to "Takıldığında", "unplugged" to "Çıkarıldığında"), "plugged"))
        ) { if (it["state"] == "plugged") "Kulaklık takıldığında" else "Kulaklık çıkarıldığında" },

        Def(
            "wifi", "Wi-Fi bağlantısı",
            listOf(
                Field("state", "Durum", Kind.CHOICE, listOf("connected" to "Bağlanınca", "disconnected" to "Bağlantı kopunca"), "connected"),
                Field("ssid", "Ağ adı (boş = herhangi bir ağ)", Kind.TEXT, optional = true)
            )
        ) {
            val net = if (it["ssid"].isBlank()) "Wi-Fi" else "“${it["ssid"]}”"
            if (it["state"] == "connected") "$net bağlanınca" else "$net bağlantısı kopunca"
        },

        Def(
            "airplane", "Uçak modu",
            listOf(Field("state", "Durum", Kind.CHOICE, listOf("on" to "Açıldığında", "off" to "Kapandığında"), "on"))
        ) { "Uçak modu " + if (it["state"] == "on") "açıldığında" else "kapandığında" },

        Def("boot", "Cihaz açıldığında") { "Cihaz yeniden başlatıldığında" },

        Def(
            "notification", "Bildirim geldiğinde",
            listOf(
                Field("app", "Uygulama", Kind.APP, optional = true),
                Field("contains", "İçerdiği metin (boş = hepsi)", Kind.TEXT, optional = true)
            )
        ) {
            val app = it["app_label"].ifBlank { "Herhangi bir uygulamadan" }
            val c = if (it["contains"].isBlank()) "" else " • “${short(it["contains"], 20)}” içeren"
            "Bildirim: $app$c"
        },

        Def(
            "shake", "Cihazı sallama",
            listOf(Field("sens", "Hassasiyet", Kind.CHOICE, listOf("low" to "Düşük", "mid" to "Orta", "high" to "Yüksek"), "mid"))
        ) { "Cihaz sallanınca (${it.l("sens")} hassasiyet)" }
    )

    // ---------------- KOŞULLAR ----------------
    val constraints: List<Def> = listOf(
        Def(
            "time_range", "Saat aralığı",
            listOf(
                Field("from", "Başlangıç", Kind.TIME, default = "22:00"),
                Field("to", "Bitiş", Kind.TIME, default = "07:00")
            )
        ) { "Saat ${it["from"]} – ${it["to"]} arası" },

        Def("days", "Haftanın günleri", listOf(Field("days", "Günler", Kind.DAYS, default = "1,2,3,4,5"))) {
            "Gün: ${daysText(it["days"])}"
        },

        Def(
            "battery", "Pil seviyesi",
            listOf(
                Field("op", "Durum", Kind.CHOICE, listOf("below" to "Altında", "above" to "Üstünde"), "above"),
                Field("level", "Seviye (%)", Kind.NUMBER, default = "30")
            )
        ) { "Pil %${it["level"]} " + if (it["op"] == "below") "altında" else "üstünde" },

        Def("charging", "Şarj durumu", listOf(Field("state", "Durum", Kind.CHOICE, listOf("yes" to "Şarj oluyor", "no" to "Şarj olmuyor"), "yes"))) {
            it.l("state")
        },

        Def("screen_state", "Ekran durumu", listOf(Field("state", "Durum", Kind.CHOICE, listOf("on" to "Ekran açık", "off" to "Ekran kapalı"), "on"))) {
            it.l("state")
        },

        Def(
            "wifi_state", "Wi-Fi durumu",
            listOf(Field("state", "Durum", Kind.CHOICE, listOf("connected" to "Wi-Fi bağlı", "disconnected" to "Wi-Fi bağlı değil"), "connected"))
        ) { it.l("state") },

        Def(
            "headset_state", "Kulaklık durumu",
            listOf(Field("state", "Durum", Kind.CHOICE, listOf("connected" to "Kulaklık bağlı", "disconnected" to "Kulaklık bağlı değil"), "connected"))
        ) { it.l("state") }
    )

    // ---------------- EYLEMLER ----------------
    val actions: List<Def> = listOf(
        Def(
            "notify", "Bildirim göster",
            listOf(
                Field("title", "Başlık", Kind.TEXT, default = "OtoMakro"),
                Field("text", "Metin  ({saat} {tarih} {pil} {wifi} {bildirim_metin})", Kind.TEXT, default = "Makro çalıştı")
            )
        ) { "Bildirim: ${short(it["title"])}" },

        Def("toast", "Kısa mesaj (toast)", listOf(Field("text", "Metin", Kind.TEXT, default = "Merhaba!"))) {
            "Mesaj: ${short(it["text"])}"
        },

        Def("tts", "Sesli oku", listOf(Field("text", "Okunacak metin", Kind.TEXT, default = "Saat {saat}"))) {
            "Sesli oku: ${short(it["text"])}"
        },

        Def("vibrate", "Titret", listOf(Field("ms", "Süre (milisaniye)", Kind.NUMBER, default = "500"))) {
            "Titret (${it["ms"]} ms)"
        },

        Def(
            "ringer", "Zil modu",
            listOf(Field("mode", "Mod", Kind.CHOICE, listOf("normal" to "Normal", "vibrate" to "Titreşim", "silent" to "Sessiz"), "vibrate"))
        ) { "Zil modu: ${it.l("mode")}" },

        Def(
            "dnd", "Rahatsız Etme",
            listOf(
                Field(
                    "state", "Durum", Kind.CHOICE,
                    listOf("priority" to "Aç (yalnızca öncelikli)", "none" to "Aç (tam sessizlik)", "off" to "Kapat"), "priority"
                )
            )
        ) { "Rahatsız Etme: ${it.l("state")}" },

        Def(
            "volume", "Ses düzeyi",
            listOf(
                Field(
                    "stream", "Ses türü", Kind.CHOICE,
                    listOf("media" to "Medya", "ring" to "Zil", "notification" to "Bildirim", "alarm" to "Alarm"), "media"
                ),
                Field("percent", "Düzey (%)", Kind.NUMBER, default = "50")
            )
        ) { "${it.l("stream")} sesi %${it["percent"]}" },

        Def(
            "torch", "Fener",
            listOf(Field("state", "Durum", Kind.CHOICE, listOf("toggle" to "Aç/Kapat (değiştir)", "on" to "Aç", "off" to "Kapat"), "toggle"))
        ) { "Fener: ${it.l("state")}" },

        Def(
            "brightness", "Ekran parlaklığı",
            listOf(
                Field("mode", "Mod", Kind.CHOICE, listOf("manual" to "Elle", "auto" to "Otomatik"), "manual"),
                Field("percent", "Parlaklık (%) – elle modda", Kind.NUMBER, default = "50")
            )
        ) { if (it["mode"] == "auto") "Parlaklık: otomatik" else "Parlaklık %${it["percent"]}" },

        Def("launch_app", "Uygulama aç", listOf(Field("app", "Uygulama", Kind.APP))) {
            "Aç: ${it["app_label"].ifBlank { it["app"] }}"
        },

        Def("open_url", "Web adresi aç", listOf(Field("url", "Adres", Kind.TEXT, default = "https://"))) {
            "Aç: ${short(it["url"])}"
        },

        Def(
            "media", "Medya kontrolü",
            listOf(Field("cmd", "Komut", Kind.CHOICE, listOf("play_pause" to "Oynat / Duraklat", "next" to "Sonraki", "prev" to "Önceki"), "play_pause"))
        ) { "Medya: ${it.l("cmd")}" },

        Def(
            "sound", "Ses çal",
            listOf(
                Field("kind", "Ses", Kind.CHOICE, listOf("notification" to "Bildirim sesi", "ringtone" to "Zil sesi", "alarm" to "Alarm sesi"), "notification"),
                Field("seconds", "En fazla süre (saniye)", Kind.NUMBER, default = "5")
            )
        ) { "Çal: ${it.l("kind")}" },

        Def("clipboard", "Panoya kopyala", listOf(Field("text", "Metin", Kind.TEXT, default = "{tarih} {saat}"))) {
            "Panoya: ${short(it["text"])}"
        },

        Def("wait", "Bekle", listOf(Field("seconds", "Süre (saniye)", Kind.NUMBER, default = "5"))) {
            "${it["seconds"]} saniye bekle"
        }
    )

    fun trigger(type: String) = triggers.firstOrNull { it.type == type }
    fun constraint(type: String) = constraints.firstOrNull { it.type == type }
    fun action(type: String) = actions.firstOrNull { it.type == type }
}
