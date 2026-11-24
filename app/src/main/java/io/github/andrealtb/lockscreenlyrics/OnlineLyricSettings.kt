package io.github.andrealtb.lockscreenlyrics

import android.content.Context
import android.content.Intent
import io.github.andrealtb.lockscreenlyrics.online.OnlineLyricSelection

object OnlineLyricSettings {
    const val KEY = "online_lyric_source_order"
    const val ACTION = "io.github.andrealtb.lockscreenlyrics.action.ONLINE_LYRIC_ORDER_CHANGED"
    const val CACHE_LIMIT_KEY = "amll_ttml_cache_limit_mb"
    const val CACHE_CLEAR_ACTION = "io.github.andrealtb.lockscreenlyrics.action.AMLL_CACHE_CLEAR"
    private const val DEFAULT_CACHE_LIMIT_MB = 1000
    @JvmStatic fun read(context: Context): String = OnlineLyricSelection.order(context.getSharedPreferences(
        LyricUiSettings.PREFERENCES_NAME, Context.MODE_PRIVATE).getString(KEY, null)).joinToString(",")
    @JvmStatic fun save(context: Context, value: String) {
        context.getSharedPreferences(LyricUiSettings.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY, OnlineLyricSelection.order(value).joinToString(",")).apply()
    }
    @JvmStatic fun publish(context: Context) {
        context.sendBroadcast(Intent(ACTION).setPackage("com.android.systemui")
            .putExtra(KEY, read(context)).putExtra(CACHE_LIMIT_KEY, readCacheLimit(context)))
    }
    @JvmStatic fun readCacheLimit(context: Context) = context
        .getSharedPreferences(LyricUiSettings.PREFERENCES_NAME, Context.MODE_PRIVATE)
        .let { preferences ->
            if (preferences.contains(CACHE_LIMIT_KEY)) preferences.getInt(CACHE_LIMIT_KEY, DEFAULT_CACHE_LIMIT_MB)
            else context.getSharedPreferences("amll_ttml_cache", Context.MODE_PRIVATE)
                .getInt("limit_mb", DEFAULT_CACHE_LIMIT_MB)
        }
    @JvmStatic fun saveCacheLimit(context: Context, limitMb: Int) {
        context.getSharedPreferences(LyricUiSettings.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putInt(CACHE_LIMIT_KEY, limitMb).apply()
    }
    @JvmStatic fun publishCacheClear(context: Context) {
        context.sendBroadcast(Intent(CACHE_CLEAR_ACTION).setPackage("com.android.systemui"))
    }
}
