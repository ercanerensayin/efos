package com.ercan.efos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private fun t(k: String) = Ortak.t(k)
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()
    private val prefs by lazy { getSharedPreferences("efos", MODE_PRIVATE) }

    private lateinit var urlBox: LinearLayout
    private lateinit var urlEdit: EditText
    private lateinit var pasteBtn: TextView
    private lateinit var btnVideo: TextView
    private lateinit var btnMp3: TextView
    private lateinit var progress: ProgressLine
    private lateinit var lblTitle: TextView
    private lateinit var status: TextView
    private lateinit var btnSniff: TextView
    private lateinit var btnOpen: TextView
    private lateinit var btnUpdate: TextView
    private lateinit var btnSettings: TextView
    private lateinit var lblVersion: TextView

    @Volatile private var ready = false
    @Volatile private var cancelled = false
    @Volatile private var updating = false
    private var busy = false
    private var activeBtn: TextView? = null
    private var audioMode = false
    private var parts = 0
    private var lastUri: Uri? = null
    private var lastMime: String? = null
    private var lastDoneUrl: String? = null
    private var lastRawError: String? = null
    private var lastFailedUrl: String? = null
    private var lastFailedAudio = false
    private var pendingAudio: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ortak.init(this)
        window.statusBarColor = UI.bg
        window.navigationBarColor = UI.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        setContentView(buildUi())
        handleShare(intent)
        showCrashIfAny()
        initEngine()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android 10+ panoya yalnızca pencere odaktayken izin verir.
        if (hasFocus && !busy) paste(force = false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (busy) {
            AlertDialog.Builder(this).setMessage(t("cikisSorusu"))
                .setPositiveButton("Evet") { _, _ -> cancel(); finish() }
                .setNegativeButton("Hayır", null).show()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------------- Arayüz (Windows sürümüyle aynı sıra ve renkler) ----------------
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(28), px(22), px(16))
            setBackgroundColor(UI.bg)
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(LogoView(this@MainActivity), LinearLayout.LayoutParams(px(46), px(46)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), 0, 0, 0) }
        titles.addView(TextView(this).apply {
            text = Ortak.str("ad"); textSize = 21f; typeface = UI.semibold; setTextColor(UI.text)
        })
        titles.addView(TextView(this).apply { text = Ortak.str("slogan"); textSize = 12.5f; setTextColor(UI.sub) })
        header.addView(titles)
        root.addView(header)

        urlBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), 0, px(6), 0)
        }
        urlEdit = EditText(this).apply {
            hint = t("yerTutucu"); textSize = 15f; setSingleLine(); background = null
            setTextColor(UI.text); setHintTextColor(UI.sub)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { onDownload(btnVideo, false); true } else false
            }
            setOnFocusChangeListener { _, _ -> paintUrlBox() }
        }
        pasteBtn = UI.button(this, t("yapistir"), primary = false, dp = dp).apply {
            setPadding(px(12), 0, px(12), 0)
            setOnClickListener { paste(force = true) }
        }
        urlBox.addView(urlEdit, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        urlBox.addView(pasteBtn, LinearLayout.LayoutParams(WRAP_CONTENT, px(38)))
        paintUrlBox()
        root.addView(urlBox, LinearLayout.LayoutParams(MATCH_PARENT, px(52)).apply { topMargin = px(24) })

        val row = LinearLayout(this)
        btnVideo = UI.button(this, t("videoIndir"), primary = true, dp = dp).apply { setOnClickListener { onDownload(this, false) } }
        btnMp3 = UI.button(this, t("mp3Indir"), primary = true, dp = dp).apply { setOnClickListener { onDownload(this, true) } }
        row.addView(btnVideo, LinearLayout.LayoutParams(0, px(54), 1f).apply { rightMargin = px(6) })
        row.addView(btnMp3, LinearLayout.LayoutParams(0, px(54), 1f).apply { leftMargin = px(6) })
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(12) })

        progress = ProgressLine(this)
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, px(6)).apply { topMargin = px(18) })
        lblTitle = TextView(this).apply {
            text = t("hazir"); textSize = 14.5f; typeface = UI.semibold; setTextColor(UI.text)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(lblTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(12) })
        status = TextView(this).apply {
            textSize = 13.5f
            setOnClickListener { showErrorDetailIfAny() }
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(4) })

        btnSniff = UI.button(this, Ortak.tOr("tarayicidaAra", "Tarayıcıda video ara"), primary = false, dp = dp).apply {
            setTextColor(UI.accent)
            typeface = UI.semibold
            setPadding(px(14), 0, px(14), 0)
            visibility = View.GONE
            setOnClickListener { openSniffer() }
        }
        root.addView(btnSniff, LinearLayout.LayoutParams(WRAP_CONTENT, px(40)).apply { topMargin = px(8) })
        setStatus("Hazırlanıyor… (ilk açılışta biraz sürebilir)", UI.sub)

        root.addView(View(this).apply { setBackgroundColor(UI.border) },
            LinearLayout.LayoutParams(MATCH_PARENT, px(1)).apply { topMargin = px(14) })

        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        btnOpen = UI.button(this, t("dosyayiAc"), primary = false, dp = dp).apply {
            setPadding(px(10), 0, px(10), 0); visibility = View.GONE
            setOnClickListener { openLast() }
        }
        btnUpdate = UI.button(this, t("guncelle"), primary = false, dp = dp).apply {
            setPadding(px(10), 0, px(10), 0); setOnClickListener { update(manual = true) }
        }
        btnSettings = UI.button(this, t("ayarlar"), primary = false, dp = dp).apply {
            setPadding(px(10), 0, px(10), 0); setOnClickListener { showSettings() }
        }
        lblVersion = TextView(this).apply { textSize = 11.5f; setTextColor(UI.sub); gravity = Gravity.END }
        footer.addView(btnOpen, LinearLayout.LayoutParams(WRAP_CONTENT, px(40)))
        footer.addView(btnUpdate, LinearLayout.LayoutParams(WRAP_CONTENT, px(40)))
        footer.addView(btnSettings, LinearLayout.LayoutParams(WRAP_CONTENT, px(40)))
        footer.addView(lblVersion, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(6) })

        setButtonsEnabled(false)
        return ScrollView(this).apply { setBackgroundColor(UI.bg); isFillViewport = true; addView(root) }
    }

    private fun paintUrlBox() {
        val focused = ::urlEdit.isInitialized && urlEdit.hasFocus()
        urlBox.background = UI.round(UI.card, 10 * dp, if (focused) UI.accent else UI.border, if (focused) px(2) else px(1))
    }

    private fun setStatus(msg: String, color: Int) { status.text = msg; status.setTextColor(color) }

    private fun setButtonsEnabled(on: Boolean) {
        for (b in listOf(btnVideo, btnMp3)) {
            b.isEnabled = on
            UI.styleButton(b, if (on) UI.accent else UI.disabled, UI.accentDown, 9 * dp)
        }
    }

    private fun setBusy(on: Boolean, active: TextView?) {
        busy = on
        activeBtn = if (on) active else null
        urlEdit.isEnabled = !on
        pasteBtn.isEnabled = !on
        btnUpdate.isEnabled = !on
        btnSettings.isEnabled = !on
        btnSniff.isEnabled = !on
        for (b in listOf(btnVideo, btnMp3)) {
            val isActive = on && b == active
            b.isEnabled = !on || isActive
            b.text = if (isActive) t("iptal") else if (b == btnMp3) t("mp3Indir") else t("videoIndir")
            val fill = when { isActive -> UI.danger; on -> UI.disabled; else -> UI.accent }
            UI.styleButton(b, fill, if (isActive) UI.dangerDown else UI.accentDown, 9 * dp)
        }
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------- Bağlantı alma ----------------
    private fun isUrl(s: String?): Boolean {
        val u = s?.trim()?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        return (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrEmpty() && !s.trim().contains(' ')
    }

    private fun extractUrl(s: String?): String? = s?.let { Regex("https?://\\S+").find(it)?.value }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val u = extractUrl(i.getStringExtra(Intent.EXTRA_TEXT))
            if (u != null && !busy) { urlEdit.setText(u); urlEdit.setSelection(u.length) }
        }
    }

    private fun paste(force: Boolean) {
        if (!force && urlEdit.text.isNotEmpty()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        val u = extractUrl(text)
        if (u != null && (force || u != lastDoneUrl)) { urlEdit.setText(u); urlEdit.setSelection(u.length) }
        else if (force) setStatus(t("panodaBaglantiYok"), UI.sub)
    }

    // ---------------- yt-dlp motoru ----------------
    private fun initEngine() = thread {
        try {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
            ready = true
            runOnUiThread {
                setButtonsEnabled(true); refreshVersion()
                setStatus(t("ipucu"), UI.sub)
            }
            // yt-dlp sık güncellenir; günde bir kez sessizce güncelle.
            if (System.currentTimeMillis() - prefs.getLong("sonGuncelleme", 0) > 24 * 3600_000L) update(manual = false)
        } catch (e: Throwable) {
            runOnUiThread { setStatus("Başlatılamadı: ${e.message}", UI.red) }
        }
    }

    private fun refreshVersion() {
        val v = runCatching { YoutubeDL.getInstance().versionName(applicationContext) }.getOrNull()
        lblVersion.text = if (v.isNullOrEmpty()) "" else "yt-dlp $v"
    }

    private fun update(manual: Boolean) {
        if (!ready || busy || updating) return
        updating = true
        if (manual) {
            setBusy(true, null)
            setStatus(t("guncellemeKontrol"), UI.accent)
        }
        thread {
            try {
                val before = runCatching { YoutubeDL.getInstance().versionName(applicationContext) }.getOrNull()
                val result = runCatching { YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE) }
                if (result.isSuccess) prefs.edit().putLong("sonGuncelleme", System.currentTimeMillis()).apply()
                runOnUiThread {
                    refreshVersion()
                    if (!manual) return@runOnUiThread
                    setBusy(false, null)
                    val after = runCatching { YoutubeDL.getInstance().versionName(applicationContext) }.getOrNull()
                    when {
                        result.isFailure -> setStatus(t("guncellemeBasarisiz"), UI.red)
                        result.getOrNull() == YoutubeDL.UpdateStatus.DONE && before != after ->
                            setStatus(String.format(t("guncellendi").replace("{0}", "%1\$s").replace("{1}", "%2\$s"), before ?: "?", after ?: "?"), UI.green)
                        else -> setStatus(t("herSeyGuncel"), UI.green)
                    }
                }
            } finally {
                updating = false
            }
        }
    }

    // ---------------- Ayarlar: kalite ve oynatma listesi ----------------
    private fun showSettings() {
        if (busy) return
        val q = Ortak.kaliteler
        val qi = prefs.getInt("kalite", 0).coerceIn(0, q.size - 1)
        val liste = prefs.getBoolean("liste", false)
        val items = arrayOf(t("kalite") + ": " + q[qi].first, t("listeyiIndir") + ": " + if (liste) "Açık" else "Kapalı")
        AlertDialog.Builder(this).setTitle(t("ayarlar")).setItems(items) { _, which ->
            if (which == 0) {
                AlertDialog.Builder(this).setTitle(t("kalite"))
                    .setSingleChoiceItems(q.map { it.first }.toTypedArray(), qi) { d, i -> prefs.edit().putInt("kalite", i).apply(); d.dismiss() }
                    .show()
            } else {
                prefs.edit().putBoolean("liste", !liste).apply()
                Toast.makeText(this, t("listeyiIndir") + ": " + if (!liste) "Açık" else "Kapalı", Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    // ---------------- İndirme ----------------
    private fun onDownload(sender: TextView, audio: Boolean) {
        if (busy) { if (sender == activeBtn) cancel(); return }
        if (!ready) return
        if (updating) {
            setStatus("yt-dlp güncelleniyor, birkaç saniye sonra tekrar deneyin.", UI.sub)
            return
        }
        val url = urlEdit.text.toString().trim()
        if (!isUrl(url)) { setStatus(t("gecersizBaglanti"), UI.red); urlEdit.requestFocus(); return }
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingAudio = audio
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            return
        }
        start(sender, audio, url)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val audio = pendingAudio ?: return
        pendingAudio = null
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) onDownload(if (audio) btnMp3 else btnVideo, audio)
        else setStatus("Dosyayı kaydetmek için depolama izni gerekiyor.", UI.red)
    }

    private fun cancel() {
        cancelled = true
        runCatching { YoutubeDL.getInstance().destroyProcessById(PROC) }
    }

    private fun openSniffer() {
        if (busy) return
        val targetUrl = lastFailedUrl ?: urlEdit.text.toString().trim()
        if (!isUrl(targetUrl)) return
        MedyaYakalayici.ac(this, targetUrl) { medya ->
            val extra = mutableListOf<String>()
            if (!medya.referer.isNullOrBlank()) {
                extra += listOf("--referer", medya.referer)
            }
            if (medya.userAgent.isNotBlank()) {
                extra += listOf("--user-agent", medya.userAgent)
            }
            if (!medya.cookie.isNullOrBlank()) {
                extra += listOf("--add-header", "Cookie:${medya.cookie}")
            }
            val sender = if (lastFailedAudio) btnMp3 else btnVideo
            setStatus(Ortak.tOr("videoBulundu", "Video akışı yakalandı, indiriliyor…"), UI.accent)
            start(sender, lastFailedAudio, medya.streamUrl, extra, originUrl = targetUrl)
        }
    }

    private fun start(
        sender: TextView,
        audio: Boolean,
        url: String,
        extraArgs: List<String> = emptyList(),
        originUrl: String = url
    ) {
        val tmp = File(cacheDir, "indir").apply { deleteRecursively(); mkdirs() }
        val q = Ortak.kaliteler
        val format = q[prefs.getInt("kalite", 0).coerceIn(0, q.size - 1)].second
        val args = Ortak.args("ortak")
        args += Ortak.args(if (prefs.getBoolean("liste", false)) "listeAcik" else "listeKapali")
        args += listOf("-P", tmp.absolutePath, "-o", Ortak.cikti)
        args += Ortak.args(if (audio) "mp3" else "video").map { it.replace("{format}", format) }
        args += extraArgs
        val request = YoutubeDLRequest(url).addCommands(args)

        audioMode = audio; cancelled = false; parts = 0
        lastUri = null; lastMime = null; lastRawError = null
        btnOpen.visibility = View.GONE
        btnSniff.visibility = View.GONE
        progress.value = 0f
        lblTitle.text = t("inceleniyor")
        setStatus(t("bilgiAliniyor"), UI.sub)
        setBusy(true, sender)

        thread {
            val files = mutableListOf<String>()
            var error: String? = null
            try {
                YoutubeDL.getInstance().execute(request, PROC) { _, _, line ->
                    if (line.startsWith("FILE|")) synchronized(files) { files += line.substring(5).trim() }
                    runOnUiThread { onLine(line) }
                }
            } catch (e: YoutubeDL.CanceledException) {
                cancelled = true
            } catch (e: Throwable) {
                if (!cancelled) error = e.message?.lines()?.lastOrNull { it.contains("ERROR:") }?.substringAfter("ERROR:")?.trim()
                    ?: e.message ?: e.toString()
            }
            // Başarılıysa dosyaları İndirilenler/Videolar'a taşı.
            var saved = 0
            if (!cancelled && error == null) {
                for (path in synchronized(files) { files.toList() }) {
                    val f = File(path)
                    if (!f.exists()) continue
                    try {
                        val uri = Kaydet.toDownloads(applicationContext, f)
                        if (uri != null) { lastUri = uri; lastMime = Kaydet.mime(f); saved++ }
                    } catch (e: Throwable) { error = "Kaydedilemedi: ${e.message}" }
                }
                if (saved == 0 && error == null) error = t("indirilemedi")
            }
            tmp.deleteRecursively()
            runOnUiThread { onFinished(error, originUrl, audio) }
        }
    }

    private fun known(s: String) = s.isNotEmpty() && !s.contains("Unknown") && !s.contains("N/A") && s != "NA"

    private fun onLine(line: String) {
        when {
            line.startsWith("PROG|") -> {
                val p = line.split('|')
                val pct = p.getOrNull(1)?.trim()?.trimEnd('%')?.toDoubleOrNull() ?: return
                progress.value = (pct / 100).toFloat()
                var text = "${t("indiriliyor")}  %${String.format(Locale.US, "%.0f", pct)}"
                val speed = p.getOrElse(2) { "" }.trim(); val eta = p.getOrElse(3) { "" }.trim()
                if (known(speed)) text += "  ·  $speed"
                if (known(eta)) text += "  ·  $eta ${t("kaldi")}"
                if (!audioMode && parts >= 2) text += "  ·  ${t("sesParcasi")}"
                setStatus(text, UI.accent)
            }
            line.startsWith("TITLE|") -> { lblTitle.text = line.substring(6); parts = 0 }
            line.startsWith("[download] Destination") -> parts++
            line.startsWith("[Merger]") -> { progress.value = 1f; setStatus(t("birlestiriliyor"), UI.accent) }
            line.startsWith("[ExtractAudio]") -> { progress.value = 1f; setStatus(t("donusturuluyor"), UI.accent) }
            line.contains("has already been downloaded") -> progress.value = 1f
        }
    }

    private fun onFinished(error: String?, url: String, audio: Boolean) {
        setBusy(false, null)
        when {
            cancelled -> {
                progress.value = 0f
                if (lblTitle.text == t("inceleniyor")) lblTitle.text = t("hazir")
                setStatus(t("iptalEdildi"), UI.sub)
            }
            error == null -> {
                progress.value = 1f
                setStatus(t("tamamlandi").replace("{0}", "İndirilenler/" + Ortak.str("klasor")), UI.green)
                btnOpen.visibility = if (lastUri != null) View.VISIBLE else View.GONE
                btnSniff.visibility = View.GONE
                lastDoneUrl = url
                urlEdit.setText("")
            }
            else -> {
                progress.value = 0f
                if (lblTitle.text == t("inceleniyor")) lblTitle.text = t("indirilemedi")
                lastRawError = error
                lastFailedUrl = url
                lastFailedAudio = audio
                val msg = Ortak.friendly(error) + " (" + Ortak.tOr("hataAyrinti", "Ayrıntı için dokunun") + ")"
                setStatus(msg, UI.red)
                val low = error.lowercase(Locale.ROOT)
                btnSniff.visibility = if (low.contains("unsupported url")) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showErrorDetailIfAny() {
        val raw = lastRawError ?: return
        AlertDialog.Builder(this)
            .setTitle(t("indirilemedi"))
            .setMessage(raw.take(3000))
            .setPositiveButton("Kopyala") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("EFOS hata", raw))
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun openLast() {
        val uri = lastUri ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, lastMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, "Bu dosyayı açacak uygulama yok.", Toast.LENGTH_SHORT).show() }
    }

    // ---------------- Çökme raporu ----------------
    private fun showCrashIfAny() {
        val f = File(filesDir, "cokme.txt")
        if (!f.exists()) return
        val text = f.readText(); f.delete()
        AlertDialog.Builder(this).setTitle("Uygulama geçen sefer kapandı")
            .setMessage(text.take(3000))
            .setPositiveButton("Kopyala") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("EFOS hata", text))
            }
            .setNegativeButton("Kapat", null).show()
    }

    private companion object { const val PROC = "efos" }
}
