package com.altrexis.venus.plugins

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PluginManager(context: Context) {
    private val prefs = context.getSharedPreferences("venus_plugins", Context.MODE_PRIVATE)
    fun all(): List<JSONObject> {
        val a = JSONArray(prefs.getString("items", "[]"))
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    fun add(name: String, httpsUrl: String): Boolean {
        if (name.isBlank() || !httpsUrl.startsWith("https://")) return false
        val a = JSONArray()
        all().filterNot { it.optString("url") == httpsUrl }.forEach { a.put(it) }
        a.put(JSONObject().put("name", name.take(80)).put("url", httpsUrl))
        prefs.edit().putString("items", a.toString()).apply()
        return true
    }
    fun remove(httpsUrl: String) {
        val a = JSONArray()
        all().filterNot { it.optString("url") == httpsUrl }.forEach { a.put(it) }
        prefs.edit().putString("items", a.toString()).apply()
    }
}
