package com.ercan.otomakro

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class EditActivity : Activity() {
    private lateinit var macro: Macro
    private lateinit var nameEt: EditText
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra("id", -1L)
        macro = Store.get(this, id) ?: Macro(System.currentTimeMillis(), "Yeni makro")
        title = if (id == -1L) "Yeni makro" else "Makroyu düzenle"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        nameEt = EditText(this).apply {
            setText(macro.name)
            hint = "Makro adı"
            setSingleLine(true)
            textSize = 17f
        }

        val bottom = row().apply { setPadding(dp(12), dp(4), dp(12), dp(12)) }
        bottom.addView(pill("İptal", C_MUTED) { finish() }, wrap().apply { setMargins(0, 0, dp(10), 0) })
        bottom.addView(primary("Kaydet") { save() }, weight1())
        root.addView(bottom, matchWrap())

        setContentView(root)
        render()
    }

    private fun render() {
        body.removeAllViews()
        val nameCard = card()
        nameCard.addView(label("Makro adı", 13f, color = C_MUTED))
        (nameEt.parent as? ViewGroup)?.removeView(nameEt)
        nameCard.addView(nameEt, matchWrap())
        body.addView(nameCard)

        section(
            "⚡  Tetikleyiciler", "Bunlardan herhangi biri olduğunda…", C_TRIGGER,
            macro.triggers, Catalog.triggers
        )
        section(
            "✓  Koşullar (isteğe bağlı)", "…ve bunların hepsi doğruysa…", C_COND,
            macro.constraints, Catalog.constraints
        )
        section(
            "▶  Eylemler", "…bunları sırayla yap.", C_ACTION,
            macro.actions, Catalog.actions
        )
    }

    private fun section(title: String, hint: String, color: Int, items: MutableList<Item>, defs: List<Def>) {
        val c = card()
        c.addView(label(title, 16f, bold = true, color = color))
        c.addView(label(hint, 13f, color = C_MUTED).apply { setPadding(0, dp(2), 0, dp(8)) })

        items.forEachIndexed { i, item ->
            val def = defs.firstOrNull { it.type == item.type }
            val r = row().apply {
                setPadding(dp(10), dp(8), dp(4), dp(8))
                background = rounded(Color.argb(18, Color.red(color), Color.green(color), Color.blue(color)), 0, 10)
            }
            val tv = TextView(this).apply {
                text = def?.describe(item) ?: item.type
                textSize = 15f
                setTextColor(C_TEXT)
            }
            r.addView(tv, weight1())
            if (items.size > 1) {
                r.addView(small("↑") {
                    if (i > 0) {
                        items.add(i - 1, items.removeAt(i))
                        render()
                    }
                })
            }
            r.addView(small("✕") {
                items.removeAt(i)
                render()
            })
            r.setOnClickListener {
                if (def != null) paramDialog(def, item) { render() }
            }
            c.addView(r, matchWrap().apply { setMargins(0, 0, 0, dp(6)) })
        }

        c.addView(pill("+  Ekle", color) { chooser(defs, items) }, wrap().apply { setMargins(0, dp(4), 0, 0) })
        body.addView(c)
    }

    private fun small(s: String, onClick: () -> Unit): Button = Button(this).apply {
        text = s
        textSize = 16f
        setTextColor(C_MUTED)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setOnClickListener { onClick() }
    }

    private fun chooser(defs: List<Def>, items: MutableList<Item>) {
        AlertDialog.Builder(this)
            .setTitle("Seçin")
            .setItems(defs.map { it.title }.toTypedArray()) { _, w ->
                val def = defs[w]
                val item = Item(def.type)
                paramDialog(def, item) {
                    items.add(item)
                    render()
                    Perms.askFor(this, def.type, def.resolve(item))
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun paramDialog(def: Def, item: Item, onOk: () -> Unit) {
        if (def.fields.isEmpty()) {
            onOk()
            return
        }
        val values = def.resolve(item).toMutableMap()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), dp(6))
        }
        val collectors = mutableListOf<() -> Unit>()

        for (f in def.fields) {
            box.addView(label(f.label, 13f, color = C_MUTED).apply { setPadding(0, dp(12), 0, dp(2)) })
            when (f.kind) {
                Kind.TEXT, Kind.NUMBER -> {
                    val et = EditText(this).apply {
                        setText(values[f.key].orEmpty())
                        inputType = if (f.kind == Kind.NUMBER) {
                            InputType.TYPE_CLASS_NUMBER
                        } else {
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        }
                    }
                    box.addView(et, matchWrap())
                    collectors.add { values[f.key] = et.text.toString().trim() }
                }

                Kind.CHOICE -> {
                    val sp = Spinner(this)
                    sp.adapter = ArrayAdapter(
                        this, android.R.layout.simple_spinner_dropdown_item, f.options.map { it.second }
                    )
                    val idx = f.options.indexOfFirst { it.first == values[f.key] }
                    if (idx >= 0) sp.setSelection(idx)
                    box.addView(sp, matchWrap())
                    collectors.add {
                        val pos = sp.selectedItemPosition
                        if (pos in f.options.indices) values[f.key] = f.options[pos].first
                    }
                }

                Kind.TIME -> {
                    val bt = valueButton(values[f.key].orEmpty().ifBlank { "08:00" })
                    bt.setOnClickListener {
                        val cur = Sys.mins(values[f.key])
                        TimePickerDialog(this, { _, h, m ->
                            val s = String.format(Locale.US, "%02d:%02d", h, m)
                            values[f.key] = s
                            bt.text = s
                        }, cur / 60, cur % 60, true).show()
                    }
                    box.addView(bt, matchWrap())
                }

                Kind.DAYS -> {
                    val bt = valueButton(Catalog.daysText(values[f.key]))
                    bt.setOnClickListener {
                        val sel = BooleanArray(7)
                        values[f.key].orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }
                            .forEach { if (it in 1..7) sel[it - 1] = true }
                        AlertDialog.Builder(this)
                            .setTitle("Günler (hiçbiri = her gün)")
                            .setMultiChoiceItems(Catalog.DAY_FULL.toTypedArray(), sel) { _, w, checked -> sel[w] = checked }
                            .setPositiveButton("Tamam") { _, _ ->
                                val s = (1..7).filter { sel[it - 1] }.joinToString(",")
                                values[f.key] = s
                                bt.text = Catalog.daysText(s)
                            }
                            .setNegativeButton("Vazgeç", null)
                            .show()
                    }
                    box.addView(bt, matchWrap())
                }

                Kind.APP -> {
                    val lbl = values[f.key + "_label"].orEmpty()
                    val bt = valueButton(
                        lbl.ifBlank { if (f.optional) "(Herhangi bir uygulama)" else "Uygulama seçin…" }
                    )
                    bt.setOnClickListener {
                        pickApp(f.optional) { pkg, name ->
                            values[f.key] = pkg
                            values[f.key + "_label"] = if (pkg.isEmpty()) "" else name
                            bt.text = name
                        }
                    }
                    box.addView(bt, matchWrap())
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(def.title)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Tamam") { _, _ ->
                collectors.forEach { it() }
                val missing = def.fields.firstOrNull { f ->
                    !f.optional && f.kind == Kind.APP && values[f.key].isNullOrBlank()
                }
                if (missing != null) {
                    Toast.makeText(this, "Lütfen “${missing.label}” seçin", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                item.params.clear()
                item.params.putAll(values)
                onOk()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun valueButton(s: String): Button = Button(this).apply {
        text = s
        setAllCaps(false)
        textSize = 16f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    private fun pickApp(optional: Boolean, cb: (String, String) -> Unit) {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase(Locale("tr")) }
        val all = if (optional) listOf("" to "(Herhangi bir uygulama)") + apps else apps
        AlertDialog.Builder(this)
            .setTitle("Uygulama seçin")
            .setItems(all.map { it.second }.toTypedArray()) { _, w -> cb(all[w].first, all[w].second) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun save() {
        val name = nameEt.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Makroya bir ad verin", Toast.LENGTH_SHORT).show()
            return
        }
        if (macro.actions.isEmpty()) {
            Toast.makeText(this, "En az bir eylem ekleyin", Toast.LENGTH_SHORT).show()
            return
        }
        macro.name = name
        Store.save(this, macro)
        MacroService.refresh(this)
        Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
        finish()
    }
}
