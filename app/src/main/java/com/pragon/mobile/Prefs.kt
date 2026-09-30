package com.pragon.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper

/** Saved PC address + the token the PC gave us when we paired. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("pragon", Context.MODE_PRIVATE)
    fun host(c: Context): String = sp(c).getString("host", "") ?: ""
    fun port(c: Context): Int = sp(c).getInt("port", 8000)
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun save(c: Context, host: String, port: Int, token: String) =
        sp(c).edit().putString("host", host).putInt("port", port).putString("token", token).apply()
    fun saveToken(c: Context, token: String) = sp(c).edit().putString("token", token).apply()
    // Standalone AI: the user's own Gemini key wins; otherwise the key cached from the paired PC.
    fun aiKey(c: Context): String = sp(c).getString("ai_key", "") ?: ""
    fun pcAiKey(c: Context): String = sp(c).getString("pc_ai_key", "") ?: ""
    fun aiModel(c: Context): String {
        val m = sp(c).getString("ai_model", "") ?: ""
        return if (m.isBlank()) "gemini-2.5-flash" else m
    }
    fun effectiveAiKey(c: Context): String = aiKey(c).ifBlank { pcAiKey(c) }
    fun saveAi(c: Context, key: String, model: String) =
        sp(c).edit().putString("ai_key", key).putString("ai_model", model).apply()
    fun savePcAiKey(c: Context, key: String) = sp(c).edit().putString("pc_ai_key", key).apply()

    /** Forget the PC but keep the user's own AI settings. */
    fun clear(c: Context) {
        val key = aiKey(c)
        val model = sp(c).getString("ai_model", "") ?: ""
        sp(c).edit().clear().putString("ai_key", key).putString("ai_model", model).apply()
    }
}

/** Tiny bridge so the service can show its status in the activity. */
object Bridge {
    @Volatile var status: String = "Not connected"
    @Volatile var listener: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    fun set(s: String) {
        status = s
        main.post { listener?.invoke(s) }
    }
}
