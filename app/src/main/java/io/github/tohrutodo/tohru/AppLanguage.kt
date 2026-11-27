package io.github.tohrutodo.tohru

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

enum class AppLanguage(val value: String, val tag: String?) {
    SYSTEM("system", null),
    VIETNAMESE("vi", "vi"),
    ENGLISH("en", "en"),
    ;

    companion object {
        const val KEY = "app-language"

        fun fromValue(value: String?): AppLanguage =
            entries.firstOrNull { it.value == value } ?: SYSTEM

        fun fromPrefs(prefs: SharedPreferences): AppLanguage =
            fromValue(prefs.getString(KEY, null))
    }
}

// API 33+ applies the language through the system per-app locale. Older
// releases wrap the base context before activities and services inflate.
object AppLocale {
    const val PREFS = "settings"

    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val language = AppLanguage.fromPrefs(base.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        val tag = language.tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    fun apply(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(AppLanguage.KEY, language.value) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        }
    }

    // Android 13+ stores per-app locales in the system, outside the app data.
    // A restored backup or an older install can leave an explicit locale while
    // the app preference says "system", so the preference wins on every start.
    fun sync(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val language = AppLanguage.fromPrefs(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        if (!needsOverride(manager.applicationLocales.toLanguageTags(), language)) return
        manager.applicationLocales = language.tag?.let { LocaleList.forLanguageTags(it) }
            ?: LocaleList.getEmptyLocaleList()
    }

    internal fun needsOverride(currentTags: String, language: AppLanguage): Boolean =
        currentTags != language.tag.orEmpty()
}

fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .firstOrNull()
