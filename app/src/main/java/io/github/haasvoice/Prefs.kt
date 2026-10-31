package io.github.haasvoice

import android.content.Context

enum class Engine(val label: String) {
    HAAS("Haas-ja（端末内・オフライン）"),
    STANDARD("標準（Androidの音声認識）"),
}

object Prefs {
    private const val FILE = "settings"
    private const val KEY_ENGINE = "engine"
    private const val KEY_KEEP_LOADED = "keep_model_loaded"

    fun engine(context: Context): Engine {
        val name = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ENGINE, null)
        return Engine.entries.firstOrNull { it.name == name } ?: Engine.HAAS
    }

    fun keepModelLoaded(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_LOADED, false)

    fun setKeepModelLoaded(context: Context, keep: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_LOADED, keep).apply()
    }

    fun setEngine(context: Context, engine: Engine) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_ENGINE, engine.name).apply()
    }
}
