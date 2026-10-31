package io.github.andrealtb.lockscreenlyrics.online

import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

fun interface LyricFetcher {
    fun fetch(track: LyricTrack, request: LyricRequest): CompletableFuture<List<OnlineLyric>>
}

/** One current track: generation checks cover A → B → A and late network completions. */
class OnlineLyricSession(private val fetcher: LyricFetcher, private val changed: Consumer<OnlineLyric?>) {
    private var identity = ""
    private var generation = 0L
    private var request: LyricRequest? = null
    private var candidates = emptyList<OnlineLyric>()
    private var order = OnlineLyricSelection.SOURCES
    private var selected: OnlineLyric? = null

    @Synchronized fun observe(key: String, track: LyricTrack): OnlineLyric? {
        if (identity == key) return selected
        request?.cancel()
        identity = key
        val token = ++generation
        candidates = emptyList()
        selected = null
        val scope = LyricRequest()
        request = scope
        if (track.title.isBlank()) return null
        try {
            fetcher.fetch(track, scope).whenComplete { results, _ ->
                synchronized(this) {
                    if (generation == token) {
                        candidates = results.orEmpty()
                        select()
                    }
                }
            }
        } catch (_: Exception) {
            scope.cancel()
        }
        return selected
    }

    @Synchronized fun current(key: String): OnlineLyric? = selected.takeIf { key == identity }
    @Synchronized fun token(): Long = generation
    @Synchronized fun setOrder(value: String?) {
        order = OnlineLyricSelection.order(value)
        select()
    }
    private fun select() {
        val next = OnlineLyricSelection.best(candidates, order)
        if (next != selected) {
            selected = next
            changed.accept(next)
        }
    }
}
