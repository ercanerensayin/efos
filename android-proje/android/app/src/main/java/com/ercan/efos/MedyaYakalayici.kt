package com.ercan.efos

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class YakalananMedya(
    val streamUrl: String,
    val referer: String?,
    val origin: String?,
    val userAgent: String,
    val cookie: String?
)

/**
 * yt-dlp'nin doğrudan desteklemediği gömülü oynatıcı (iframe / JS) sayfalarından
 * .m3u8, .mpd, .mp4 veya uzantısız HLS/DASH medya akışlarını yakalar.
 */
object MedyaYakalayici {
    private val ENGELLI_PARCALAR = listOf(
        "doubleclick", "googlesyndication", "googleads", "adservice", "pagead",
        "analytics", "googletagmanager", "facebook.net", "mc.yandex",
        "favicon", "thumbnail", "thumb", "poster", "preview", "sprite"
    )

    private val STATIK_UZANTILAR = listOf(
        ".ts", ".m4s", ".vtt", ".srt", ".ass",
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".ico", ".bmp",
        ".js", ".mjs", ".css", ".woff", ".woff2", ".ttf", ".otf", ".eot", ".map"
    )

    private fun engelliMi(low: String): Boolean {
        if (!low.startsWith("http://") && !low.startsWith("https://")) return true
        return ENGELLI_PARCALAR.any { low.contains(it) }
    }

    private fun dogrudanMedyaMi(url: String): Boolean {
        val low = url.lowercase(Locale.ROOT)
        if (engelliMi(low)) return false
        val path = runCatching { Uri.parse(low).path ?: "" }.getOrDefault("")
        if (STATIK_UZANTILAR.any { path.endsWith(it) }) return false
        return path.endsWith(".m3u8") ||
            path.endsWith(".mpd") ||
            path.endsWith(".mp4") ||
            path.endsWith(".m4v") ||
            low.contains(".m3u8?") ||
            low.contains(".mpd?") ||
            low.contains("format=m3u8") ||
            low.contains("mime=video")
    }

    /** Uzantısız veya .txt/.json gibi gizlenmiş HLS/DASH adaylarını arka planda kontrol etmek için seçer. */
    private fun adayMedyaMi(url: String, headers: Map<String, String>): Boolean {
        val low = url.lowercase(Locale.ROOT)
        if (engelliMi(low)) return false
        val path = runCatching { Uri.parse(low).path ?: "" }.getOrDefault("")
        if (STATIK_UZANTILAR.any { path.endsWith(it) }) return false
        if (path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".php")) return false

        val accept = (headers["Accept"] ?: headers["accept"] ?: "").lowercase(Locale.ROOT)
        if (accept.contains("video/") || accept.contains("mpegurl") || accept.contains("dash+xml")) return true

        val ipuclari = listOf(
            "m3u8", "m3u", "mpd", "hls", "dash", "master", "playlist", "chunklist",
            "manifest", "stream", "video", "play", "media", "cdn", ".txt"
        )
        return ipuclari.any { low.contains(it) }
    }

    private fun originCikar(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = u.scheme ?: return null
        val host = u.host ?: return null
        val port = if (u.port != -1) ":${u.port}" else ""
        return "$scheme://$host$port"
    }

    /** Sunucunun Content-Type başlığına veya ilk 512 baytına (#EXTM3U / <MPD) bakarak akışı doğrular. */
    private fun icerikMedyaMi(url: String, referer: String?, ua: String, cookie: String?): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 2500
                readTimeout = 2500
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Range", "bytes=0-511")
                if (!referer.isNullOrBlank()) {
                    setRequestProperty("Referer", referer)
                    originCikar(referer)?.let { setRequestProperty("Origin", it) }
                }
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
            }
            val code = conn.responseCode
            if (code !in 200..299) return false

            val ct = (conn.contentType ?: "").lowercase(Locale.ROOT)
            if (ct.contains("mpegurl") || ct.contains("dash+xml")) return true

            if (ct.startsWith("video/")) {
                // Çok küçük reklam/önizleme parçalarını (<100 KB) ele.
                val len = conn.contentLengthLong
                val cr = conn.getHeaderField("Content-Range") // örn: bytes 0-511/12345678
                val total = cr?.substringAfter('/', "")?.toLongOrNull() ?: len
                return total < 0 || total > 100_000L
            }

            val buf = ByteArray(512)
            val n = conn.inputStream.use { it.read(buf) }
            if (n <= 0) return false
            val head = String(buf, 0, n, Charsets.UTF_8).trimStart()
            head.startsWith("#EXTM3U") || head.contains("<MPD")
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun ac(activity: Activity, pageUrl: String, onFound: (YakalananMedya) -> Unit) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val yakalandi = AtomicBoolean(false)
        val taranan = AtomicInteger(0)
        val kontrolEdilenler = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val pool = Executors.newFixedThreadPool(3)
        var guncelSayfa = pageUrl
        val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UI.bg)
        }

        val topBar = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UI.card)
            setPadding(px(14), px(10), px(10), px(10))
        }
        val baseHint = Ortak.tOr("videoAraniyor", "Sayfada video aranıyor… Gerekirse oynat tuşuna basın")
        val infoLbl = TextView(activity).apply {
            text = baseHint
            textSize = 13f
            setTextColor(UI.text)
        }
        val closeBtn = UI.button(activity, "Kapat", primary = false, dp = dp).apply {
            setPadding(px(12), 0, px(12), 0)
            setOnClickListener { dialog.dismiss() }
        }
        topBar.addView(infoLbl, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        topBar.addView(closeBtn, LinearLayout.LayoutParams(WRAP_CONTENT, px(36)))
        root.addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val webView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // Sitelerin WebView'ı engellememesi için "; wv)" işaretini temizle.
            settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
            webChromeClient = WebChromeClient()
        }
        val defaultUa = webView.settings.userAgentString
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        fun yakala(streamUrl: String, referer: String?, ua: String, cookie: String?) {
            if (!yakalandi.compareAndSet(false, true)) return
            val finalReferer = if (referer.isNullOrBlank()) guncelSayfa else referer
            val bulunan = YakalananMedya(
                streamUrl = streamUrl,
                referer = finalReferer,
                origin = originCikar(finalReferer),
                userAgent = ua,
                cookie = cookie
            )
            activity.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                onFound(bulunan)
            }
        }

        fun temizle() {
            yakalandi.set(true)
            runCatching { pool.shutdownNow() }
            runCatching {
                webView.stopLoading()
                webView.onPause()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!url.isNullOrBlank()) guncelSayfa = url
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                val low = u.lowercase(Locale.ROOT)
                // Reklam / uygulama mağazası yönlendirmelerini engelle.
                if (!low.startsWith("http://") && !low.startsWith("https://")) return true
                if (ENGELLI_PARCALAR.any { low.contains(it) }) return true
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                if (yakalandi.get()) return null

                val headers = request.requestHeaders ?: emptyMap()
                val referer = headers["Referer"] ?: headers["referer"] ?: guncelSayfa
                val ua = headers["User-Agent"] ?: headers["user-agent"] ?: defaultUa
                val cookie = runCatching {
                    cm.getCookie(reqUrl)?.takeIf { it.isNotBlank() }
                        ?: cm.getCookie(guncelSayfa)?.takeIf { it.isNotBlank() }
                }.getOrNull()

                if (dogrudanMedyaMi(reqUrl)) {
                    yakala(reqUrl, referer, ua, cookie)
                } else if (adayMedyaMi(reqUrl, headers) && kontrolEdilenler.add(reqUrl)) {
                    val count = taranan.incrementAndGet()
                    if (count % 5 == 1) {
                        activity.runOnUiThread {
                            if (dialog.isShowing) infoLbl.text = "$baseHint ($count)"
                        }
                    }
                    runCatching {
                        pool.execute {
                            if (!yakalandi.get() && icerikMedyaMi(reqUrl, referer, ua, cookie)) {
                                yakala(reqUrl, referer, ua, cookie)
                            }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        root.addView(webView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)
        dialog.setOnDismissListener { temizle() }
        dialog.show()
        webView.loadUrl(pageUrl)
    }
}