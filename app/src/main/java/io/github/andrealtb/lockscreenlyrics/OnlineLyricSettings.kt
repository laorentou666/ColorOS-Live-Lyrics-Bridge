package io.github.andrealtb.lockscreenlyrics

import android.content.Context
import android.content.Intent
import io.github.andrealtb.lockscreenlyrics.online.OnlineLyricSelection

object OnlineLyricSettings {
    const val KEY = "online_lyric_source_order"
    const val ACTION = "io.github.andrealtb.lockscreenlyrics.action.ONLINE_LYRIC_ORDER_CHANGED"
    @JvmStatic fun read(context: Context): String = OnlineLyricSelection.order(context.getSharedPreferences(
        LyricUiSettings.PREFERENCES_NAME, Context.MODE_PRIVATE).getString(KEY, null)).joinToString(",")
    @JvmStatic fun save(context: Context, value: String) {
        context.getSharedPreferences(LyricUiSettings.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY, OnlineLyricSelection.order(value).joinToString(",")).apply()
    }
    @JvmStatic fun publish(context: Context) {
        context.sendBroadcast(Intent(ACTION).setPackage("com.android.systemui").putExtra(KEY, read(context)))
    }
}
