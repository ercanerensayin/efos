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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class YakalananMedya(
    val streamUrl: String,
    val referer: String?,
    val userAgent: String,
    val cookie: String?
)

/**
 * yt-dlp'nin doğrudan desteklemediği gömülü oynatıcı (iframe / JS) sayfalarından
 * .m3u8, .mpd veya .mp4 medya akışını WebView ağ isteklerinden yakalar.
 */
object MedyaYakalayici {
    private val ENGELLI_PARCALAR = listOf(
        "doubleclick", "googlesyndication", "googleads", "adservice",
        "analytics", "favicon", "thumbnail", "thumb", "poster", "preview"
    )

    private fun medyaAkisiMi(url: String): Boolean {
        val low = url.lowercase(Locale.ROOT)
        if (!low.startsWith("http://") && !low.startsWith("https://")) return false
        if (ENGELLI_PARCALAR.any { low.contains(it) }) return false
        val path = runCatching { Uri.parse(low).path ?: "" }.getOrDefault("")
        if (path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".vtt") ||
            path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
            path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".js") ||
            path.endsWith(".css")) {
            return false
        }
        return path.endsWith(".m3u8") ||
            path.endsWith(".mpd") ||
            path.endsWith(".mp4") ||
            path.endsWith(".m4v") ||
            low.contains(".m3u8?") ||
            low.contains(".mpd?")
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun ac(activity: Activity, pageUrl: String, onFound: (YakalananMedya) -> Unit) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val yakalandi = AtomicBoolean(false)
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
        val infoLbl = TextView(activity).apply {
            text = Ortak.tOr("videoAraniyor", "Sayfada video aranıyor… Gerekirse oynat tuşuna basın")
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
        }
        val defaultUa = webView.settings.userAgentString
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        fun temizle() {
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

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                if (!yakalandi.get() && medyaAkisiMi(reqUrl) && yakalandi.compareAndSet(false, true)) {
                    val headers = request.requestHeaders ?: emptyMap()
                    val referer = headers["Referer"] ?: headers["referer"] ?: guncelSayfa
                    val ua = headers["User-Agent"] ?: headers["user-agent"] ?: defaultUa
                    val cookie = runCatching {
                        cm.getCookie(reqUrl)?.takeIf { it.isNotBlank() }
                            ?: cm.getCookie(guncelSayfa)?.takeIf { it.isNotBlank() }
                    }.getOrNull()
                    val bulunan = YakalananMedya(reqUrl, referer, ua, cookie)
                    activity.runOnUiThread {
                        dialog.dismiss()
                        onFound(bulunan)
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
