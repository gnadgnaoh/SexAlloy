package io.github.nexalloy.revanced.whatsapp.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal object WaStore {
    private const val REVOKED = "nexalloy_wa_revoked"
    private const val EDITS = "nexalloy_wa_edits"

    private lateinit var revoked: SharedPreferences
    private lateinit var editPrefs: SharedPreferences
    private val revokedCache = ConcurrentHashMap<String, Boolean>()
    private val editCache = ConcurrentHashMap<String, List<Pair<String, Long>>>()

    fun init(ctx: Context) {
        if (::revoked.isInitialized) return
        revoked = ctx.getSharedPreferences(REVOKED, Context.MODE_PRIVATE)
        editPrefs = ctx.getSharedPreferences(EDITS, Context.MODE_PRIVATE)
    }

    fun markRevoked(id: String) {
        revokedCache[id] = true
        if (::revoked.isInitialized) revoked.edit().putLong(id, System.currentTimeMillis()).apply()
    }

    fun isRevoked(id: String): Boolean = revokedCache.getOrPut(id) {
        ::revoked.isInitialized && revoked.contains(id)
    }

    fun addEdit(id: String, text: String, at: Long) {
        if (!::editPrefs.isInitialized) return
        val list = read(id)
        if (list.length() > 0 && list.optJSONObject(list.length() - 1)?.optString("t") == text) return
        list.put(JSONObject().put("t", text).put("at", at))
        editPrefs.edit().putString(id, list.toString()).apply()
        editCache.remove(id)
    }

    private fun read(id: String): JSONArray = try {
        JSONArray(editPrefs.getString(id, null) ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    fun edits(id: String): List<Pair<String, Long>> {
        if (!::editPrefs.isInitialized) return emptyList()
        return editCache.getOrPut(id) {
            val list = read(id)
            (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { it.optString("t") to it.optLong("at") } }
        }
    }
}
