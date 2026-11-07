package com.ercan.otomakro

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "OtoMakro"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val add = primary("+  Yeni makro") { startActivity(Intent(this, EditActivity::class.java)) }
        root.addView(add, matchWrap().apply { setMargins(dp(12), dp(4), dp(12), dp(12)) })
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        MacroService.refresh(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Günlük").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, "İzinler")
        menu.add(0, 3, 2, "Örnek makroları ekle")
        menu.add(0, 4, 3, "Yardım")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> showLog()
            2 -> Perms.showAll(this)
            3 -> {
                Store.addSamples(this)
                MacroService.refresh(this)
                render()
            }
            4 -> showHelp()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun render() {
        list.removeAllViews()
        val macros = Store.all(this)
        if (macros.isEmpty()) {
            val c = card()
            c.addView(label("Henüz makro yok", 18f, bold = true))
            c.addView(
                label(
                    "Makro = Tetikleyici → (Koşullar) → Eylemler.\n" +
                        "Örneğin: “Şarj takılınca” → “pil %80'in altındaysa” → “bildirim göster”.",
                    14f, color = C_MUTED
                ).apply { setPadding(0, dp(6), 0, dp(10)) }
            )
            c.addView(pill("Örnek makroları ekle", C_ACCENT) {
                Store.addSamples(this)
                MacroService.refresh(this)
                render()
            }, wrap())
            list.addView(c)
            return
        }
        for (m in macros) list.addView(macroCard(m))
    }

    private fun macroCard(m: Macro): LinearLayout {
        val c = card()
        val head = row()
        head.addView(label(m.name, 17f, bold = true), weight1())
        val sw = Switch(this).apply {
            isChecked = m.enabled
            setOnCheckedChangeListener { _, checked ->
                Store.setEnabled(this@MainActivity, m.id, checked)
                MacroService.refresh(this@MainActivity)
                c.alpha = if (checked) 1f else 0.55f
            }
        }
        head.addView(sw, wrap())
        c.addView(head)
        c.alpha = if (m.enabled) 1f else 0.55f

        fun line(prefix: String, color: Int, items: List<String>, sep: String) {
            if (items.isEmpty()) return
            c.addView(label("$prefix  " + items.joinToString(sep), 14f, color = color).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
        line("⚡", C_TRIGGER, m.triggers.map { Catalog.trigger(it.type)?.describe(it) ?: it.type }, "  veya  ")
        line("✓", C_COND, m.constraints.map { Catalog.constraint(it.type)?.describe(it) ?: it.type }, "  ve  ")
        line("▶", C_ACTION, m.actions.map { Catalog.action(it.type)?.describe(it) ?: it.type }, "  →  ")
        if (m.triggers.isEmpty()) line("⚡", C_MUTED, listOf("Tetikleyici yok – yalnızca elle çalışır"), "")

        val btns = row().apply { setPadding(0, dp(10), 0, 0); gravity = Gravity.END }
        btns.addView(pill("Çalıştır", C_ACTION) {
            Engine.run(this, m, "elle", emptyMap(), force = true)
            Toast.makeText(this, "«${m.name}» çalıştırıldı", Toast.LENGTH_SHORT).show()
        }, wrap().apply { setMargins(0, 0, dp(8), 0) })
        btns.addView(pill("Düzenle", C_ACCENT) {
            startActivity(Intent(this, EditActivity::class.java).putExtra("id", m.id))
        }, wrap().apply { setMargins(0, 0, dp(8), 0) })
        btns.addView(pill("Sil", C_DANGER) {
            AlertDialog.Builder(this)
                .setTitle("Silinsin mi?")
                .setMessage("«${m.name}» makrosu silinecek.")
                .setPositiveButton("Sil") { _, _ ->
                    Store.delete(this, m.id)
                    MacroService.refresh(this)
                    render()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }, wrap())
        c.addView(btns)
        return c
    }

    private fun showLog() {
        val text = Store.readLog(this).ifBlank { "Henüz kayıt yok." }
        AlertDialog.Builder(this)
            .setTitle("Günlük (en yeni üstte)")
            .setMessage(text)
            .setPositiveButton("Kapat", null)
            .setNeutralButton("Temizle") { _, _ -> Store.clearLog(this) }
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Nasıl kullanılır?")
            .setMessage(
                "1) “Yeni makro” ile bir makro oluşturun.\n" +
                    "2) Tetikleyici ekleyin (ne olunca?). Birden fazla eklerseniz herhangi biri yeter.\n" +
                    "3) İsterseniz koşul ekleyin (hangi durumda?). Koşulların hepsi sağlanmalı.\n" +
                    "4) Eylemleri ekleyin (ne yapılsın?). Sırayla çalışırlar; araya “Bekle” koyabilirsiniz.\n\n" +
                    "Metinlerde kullanılabilen değişkenler:\n" +
                    "{saat} {tarih} {pil} {wifi} {bildirim_baslik} {bildirim_metin} {uygulama}\n\n" +
                    "Servisin kapanmaması için İzinler menüsünden pil optimizasyonu muafiyetini açın. " +
                    "Xiaomi, Huawei, Oppo gibi cihazlarda ayrıca “Otomatik başlatma” iznini verin."
            )
            .setPositiveButton("Tamam", null)
            .show()
    }
}
