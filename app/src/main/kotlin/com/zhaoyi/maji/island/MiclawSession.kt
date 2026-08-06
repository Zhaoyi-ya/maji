package com.zhaoyi.maji.island

import android.content.Context
import org.json.JSONObject

data class MiclawSession(
    val serviceToken: String = "",
    val passToken: String = "",
    val cUserId: String = "",
    val userId: String = "",
    val savedAt: Long = System.currentTimeMillis(),
) {
    val canRefresh: Boolean get() = passToken.isNotBlank() && userId.isNotBlank()
    val isUsable: Boolean get() = serviceToken.isNotBlank() || canRefresh

    fun toJson(): String = JSONObject().apply {
        put("serviceToken", serviceToken)
        put("passToken", passToken)
        put("cUserId", cUserId)
        put("userId", userId)
        put("savedAt", savedAt)
    }.toString()
}

object MiclawSessionStore {
    private const val PREFS = "miclaw_session"
    private const val KEY = "session"

    fun load(context: Context): MiclawSession? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            MiclawSession(
                serviceToken = o.optString("serviceToken"),
                passToken = o.optString("passToken"),
                cUserId = o.optString("cUserId"),
                userId = o.optString("userId"),
                savedAt = o.optLong("savedAt", 0),
            ).takeIf { it.isUsable }
        }.getOrNull()
    }

    fun save(context: Context, session: MiclawSession) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, session.toJson()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
