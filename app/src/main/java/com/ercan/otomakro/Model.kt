package com.ercan.otomakro

import org.json.JSONArray
import org.json.JSONObject

/** Bir tetikleyici, koşul veya eylem: tür adı + parametreler. */
class Item(var type: String, val params: MutableMap<String, String> = mutableMapOf()) {

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("type", type)
        val p = JSONObject()
        for ((k, v) in params) p.put(k, v)
        o.put("params", p)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Item {
            val p = mutableMapOf<String, String>()
            val po = o.optJSONObject("params")
            if (po != null) {
                val keys = po.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    p[k] = po.optString(k)
                }
            }
            return Item(o.getString("type"), p)
        }

        fun of(type: String, vararg kv: Pair<String, String>): Item = Item(type, mutableMapOf(*kv))
    }
}

class Macro(
    var id: Long,
    var name: String,
    var enabled: Boolean = true,
    val triggers: MutableList<Item> = mutableListOf(),
    val constraints: MutableList<Item> = mutableListOf(),
    val actions: MutableList<Item> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("enabled", enabled)
        o.put("triggers", arr(triggers))
        o.put("constraints", arr(constraints))
        o.put("actions", arr(actions))
        return o
    }

    fun copyDeep(): Macro = fromJson(toJson())

    companion object {
        private fun arr(list: List<Item>): JSONArray {
            val a = JSONArray()
            for (i in list) a.put(i.toJson())
            return a
        }

        private fun list(a: JSONArray?): MutableList<Item> {
            val out = mutableListOf<Item>()
            if (a == null) return out
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(Item.fromJson(o))
            }
            return out
        }

        fun fromJson(o: JSONObject): Macro = Macro(
            o.getLong("id"),
            o.optString("name", "Makro"),
            o.optBoolean("enabled", true),
            list(o.optJSONArray("triggers")),
            list(o.optJSONArray("constraints")),
            list(o.optJSONArray("actions"))
        )
    }
}
