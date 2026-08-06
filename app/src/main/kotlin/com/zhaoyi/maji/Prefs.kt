package com.zhaoyi.maji

import android.content.Context
import android.content.SharedPreferences

/**
 * Single facade over the app's SharedPreferences files. Centralises the
 * scattered `context.getSharedPreferences("...", MODE_PRIVATE)` calls that
 * were previously inline in `DetailScreen` (`titlePrefs`),
 * `DevTestPage` (`prefs` for AI settings) and `CookieManagePage`
 * (`cookiePrefs`).
 *
 * Each `Category` has its own file so that `clear()` only wipes one
 * concern at a time. All files are created lazily on first access.
 */
object Prefs {
    private const val FILE_TITLE = "title_cache"
    private const val FILE_AI = "ai_settings"
    private const val FILE_COOKIE = "cookie_meta"
    private const val FILE_GENERAL = "general"
    private const val FILE_BACKUP = "backup"
    private const val FILE_NOTIFY = "notify"

    enum class Category(val file: String) {
        TITLE(FILE_TITLE),
        AI(FILE_AI),
        COOKIE(FILE_COOKIE),
        GENERAL(FILE_GENERAL),
        BACKUP(FILE_BACKUP),
        NOTIFY(FILE_NOTIFY),
    }

    fun get(context: Context, category: Category): SharedPreferences =
        context.getSharedPreferences(category.file, Context.MODE_PRIVATE)
}
