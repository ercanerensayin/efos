package com.ercan.otomakro

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Eylemleri çalıştırır. Arka plan iş parçacığında çağrılır. */
object Actions {
    private val main = Handler(Looper.getMainLooper())

    fun run(ctx: Context, a: Item, data: Map<String, String>) {
        val def = Catalog.action(a.type) ?: return
        val p = def.resolve(a)
        fun t(k: String) = Sys.magic(ctx, p[k].orEmpty(), data)
        fun num(k: String, d: Int) = p[k]?.trim()?.toIntOrNull() ?: d

        when (a.type) {
            "notify" -> Notif.post(ctx, t("title"), t("text"))

            "toast" -> {
                val s = t("text")
                main.post { Toast.makeText(ctx, s, Toast.LENGTH_LONG).show() }
            }

            "tts" -> speak(ctx, t("text"))

            "vibrate" -> {
                val ms = num("ms", 500).coerceIn(10, 10000).toLong()
                val v = ctx.getSystemService(Vibrator::class.java)
                    ?: throw IllegalStateException("Titreşim motoru yok")
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }

            "ringer" -> {
                val am = audio(ctx)
                am.ringerMode = when (p["mode"]) {
                    "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
            }

            "dnd" -> {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                    ?: throw IllegalStateException("Bildirim yöneticisi yok")
                if (!nm.isNotificationPolicyAccessGranted) throw IllegalStateException("Rahatsız Etme erişimi verilmemiş")
                nm.setInterruptionFilter(
                    when (p["state"]) {
                        "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
                        else -> NotificationManager.INTERRUPTION_FILTER_ALL
                    }
                )
            }

            "volume" -> {
                val am = audio(ctx)
                val stream = when (p["stream"]) {
                    "ring" -> AudioManager.STREAM_RING
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    "alarm" -> AudioManager.STREAM_ALARM
                    else -> AudioManager.STREAM_MUSIC
                }
                val pct = num("percent", 50).coerceIn(0, 100)
                val max = am.getStreamMaxVolume(stream)
                am.setStreamVolume(stream, Math.round(max * pct / 100f), 0)
            }

            "torch" -> {
                val cm = ctx.getSystemService(CameraManager::class.java)
                    ?: throw IllegalStateException("Kamera servisi yok")
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: throw IllegalStateException("Fener bulunamadı")
                val on = when (p["state"]) {
                    "on" -> true
                    "off" -> false
                    else -> !Engine.torchOn
                }
                cm.setTorchMode(id, on)
                Engine.torchOn = on
            }

            "brightness" -> {
                if (!Settings.System.canWrite(ctx)) throw IllegalStateException("Sistem ayarlarını değiştirme izni yok")
                val cr = ctx.contentResolver
                if (p["mode"] == "auto") {
                    Settings.System.putInt(
                        cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    )
                } else {
                    Settings.System.putInt(
                        cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    val v = (255 * num("percent", 50).coerceIn(1, 100) / 100).coerceIn(1, 255)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
                }
            }

            "launch_app" -> {
                val pkg = p["app"].orEmpty()
                val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    ?: throw IllegalStateException("Uygulama bulunamadı: $pkg")
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startOrNotify(ctx, i, "${p["app_label"].orEmpty().ifBlank { pkg }} uygulamasını açmak için dokunun")
            }

            "open_url" -> {
                var url = t("url").trim()
                if (!url.contains("://")) url = "https://$url"
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startOrNotify(ctx, i, "$url adresini açmak için dokunun")
            }

            "media" -> {
                val am = audio(ctx)
                val code = when (p["cmd"]) {
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }

            "sound" -> {
                val type = when (p["kind"]) {
                    "alarm" -> RingtoneManager.TYPE_ALARM
                    "ringtone" -> RingtoneManager.TYPE_RINGTONE
                    else -> RingtoneManager.TYPE_NOTIFICATION
                }
                val r = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(type))
                    ?: throw IllegalStateException("Ses bulunamadı")
                r.play()
                val end = System.currentTimeMillis() + num("seconds", 5).coerceIn(1, 120) * 1000L
                while (r.isPlaying && System.currentTimeMillis() < end) Thread.sleep(200)
                if (r.isPlaying) r.stop()
            }

            "clipboard" -> {
                val s = t("text")
                main.post {
                    ctx.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("OtoMakro", s))
                }
            }

            "wait" -> Thread.sleep(num("seconds", 5).coerceIn(0, 3600) * 1000L)
        }
    }

    private fun audio(ctx: Context): AudioManager =
        ctx.getSystemService(AudioManager::class.java) ?: throw IllegalStateException("Ses servisi yok")

    /** Android 10+ arka plandan ekran açmayı kısıtlar; izin yoksa dokunulabilir bildirim gösterir. */
    private fun startOrNotify(ctx: Context, i: Intent, fallbackText: String) {
        if (Settings.canDrawOverlays(ctx)) {
            ctx.startActivity(i)
        } else {
            Notif.post(ctx, "OtoMakro", fallbackText, i)
        }
    }

    private fun speak(ctx: Context, text: String) {
        if (text.isBlank()) return
        val latch = CountDownLatch(1)
        var tts: TextToSpeech? = null
        main.post {
            tts = TextToSpeech(ctx) { status ->
                val engine = tts
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    val tr = Locale("tr", "TR")
                    if (engine.isLanguageAvailable(tr) >= TextToSpeech.LANG_AVAILABLE) engine.setLanguage(tr)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            latch.countDown()
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            latch.countDown()
                        }
                    })
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "otomakro")
                } else {
                    latch.countDown()
                }
            }
        }
        latch.await(60, TimeUnit.SECONDS)
        main.post { tts?.shutdown() }
    }
}
