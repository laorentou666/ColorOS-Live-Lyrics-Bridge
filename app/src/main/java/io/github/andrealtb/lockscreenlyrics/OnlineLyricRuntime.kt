package io.github.andrealtb.lockscreenlyrics

import android.annotation.SuppressLint
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
import io.github.andrealtb.lockscreenlyrics.online.*
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Supplies the existing lyricInfo entry point and retains native metadata for fallback. */
class OnlineLyricRuntime @JvmOverloads constructor(fetcher: LyricFetcher? = null) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, ArrayBlockingQueue(48),
        { task -> Thread(task, "bridge-online-lyrics").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val sources = OnlineLyricSources()
    private var key = ""
    private var mediaKey = ""
    private var player = ""
    private var metadata: MediaMetadata? = null
    private var refresh: Consumer<MediaMetadata>? = null
    private val session = OnlineLyricSession(fetcher ?: LyricFetcher { track, request -> sources.fetchAll(track, request, executor) },
        Consumer { candidate -> scheduleRefresh(candidate) })

    private fun track(metadata: MediaMetadata) = LyricTrack(
        metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty() },
        metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().ifBlank {
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty().ifBlank { metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty() }
        }, metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(), metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))

    private fun identity(mediaKey: String, metadata: MediaMetadata): String = listOf(mediaKey,
        metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(), track(metadata).toString()).joinToString("\u0000")

    @Synchronized fun observe(player: String, mediaKey: String, incoming: MediaMetadata,
        refresh: Consumer<MediaMetadata>): MediaMetadata {
        this.player = player
        this.mediaKey = mediaKey
        key = identity(mediaKey, incoming)
        // Replayed online metadata should keep the latest original payload available for fallback.
        if (!isOnline(incoming)) metadata = incoming
        this.refresh = refresh
        val candidate = session.observe(key, track(incoming))
        return candidate?.let { inject(incoming, it) } ?: incoming
    }

    @Synchronized fun apply(mediaKey: String, incoming: MediaMetadata): MediaMetadata =
        session.current(identity(mediaKey, incoming))?.let { inject(incoming, it) } ?: incoming

    fun setOrder(value: String?) = session.setOrder(value)

    @SuppressLint("WrongConstant") // ColorOS extends MediaMetadata with the lyricInfo key.
    private fun isOnline(value: MediaMetadata) = runCatching {
        JSONObject(value.getString(LyricInfoContract.METADATA_KEY).orEmpty()).optString("source").startsWith("bridge-online:")
    }.getOrDefault(false)

    private fun scheduleRefresh(candidate: OnlineLyric?) {
        val token = session.token()
        handler.post {
            synchronized(this) {
                val base = metadata
                if (session.token() == token && base != null && session.current(key) == candidate) {
                    refresh?.accept(candidate?.let { inject(base, it) } ?: base)
                }
            }
        }
    }

    @SuppressLint("WrongConstant") // Read and write the same vendor lyricInfo contract.
    private fun inject(base: MediaMetadata, lyric: OnlineLyric): MediaMetadata {
        val track = track(base)
        val native = LyricInfoContract.parse(base.getString(LyricInfoContract.METADATA_KEY))
        val payload = JSONObject().put("songName", track.title).put("artist", track.artist).put("album", track.album)
            .put("songId", base.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty())
            .put("lyric", lyric.lineLyric()).put("rawLyric", lyric.rawLyric()).put("translationLyric", lyric.translationLyric())
            .put("provider", player).put("source", "bridge-online:${lyric.source}")
            .put("trackKey", native?.trackKey?.takeIf { it.isNotBlank() } ?: "$mediaKey:${track.title}:${track.artist}")
            .put("sessionGeneration", native?.sessionGeneration ?: 0)
        return MediaMetadata.Builder(base).putString(LyricInfoContract.METADATA_KEY, payload.toString()).build()
    }
}
