package io.github.tohrutodo.tohru.ui

import android.content.SharedPreferences
import androidx.core.content.edit

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    BLACK;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK, BLACK -> true
    }

    companion object {
        const val KEY = "theme-mode"

        fun fromValue(value: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SYSTEM

        fun fromPrefs(prefs: SharedPreferences): ThemeMode =
            fromValue(prefs.getString(KEY, null))

        fun save(prefs: SharedPreferences, mode: ThemeMode) {
            prefs.edit { putString(KEY, mode.name.lowercase()) }
        }
    }
}
