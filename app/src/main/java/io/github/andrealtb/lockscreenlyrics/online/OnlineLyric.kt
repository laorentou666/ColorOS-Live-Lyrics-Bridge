// SPDX-License-Identifier: AGPL-3.0-only
// Kotlin port of SPlayer-Next's common/lyric matching rules; see docs/ONLINE_LYRICS.md.
package io.github.andrealtb.lockscreenlyrics.online

import java.util.Locale
import kotlin.math.abs

data class LyricTrack(val title: String, val artist: String, val album: String, val duration: Long)
data class LyricSong(val id: String, val mid: String, val track: LyricTrack)
data class LyricWord(val text: String, val start: Long, val end: Long)
data class LyricLine(
    val start: Long, val end: Long, val text: String, val words: List<LyricWord> = emptyList(),
    val translation: String = "", val roman: String = ""
)

data class OnlineLyric(val source: String, val format: String, val score: Int, val lines: List<LyricLine>) {
    // A line-only TTML document has the same quality as LRC; actual word timing decides quality.
    val quality: Int get() = if (lines.any { it.words.isNotEmpty() }) {
        if (format == "ttml") 2 else 1
    } else 0

    fun lineLyric(): String = lines.joinToString("\n") { "[${stamp(it.start)}]${it.text}" }

    /** Keep absolute word timestamps and the final end marker consumed by Bridge's renderer. */
    fun rawLyric(): String = lines.joinToString("\n") { line ->
        buildString {
            append("[${stamp(line.start)}]")
            if (line.words.isEmpty()) append(line.text) else line.words.forEach { word ->
                append("<${stamp(word.start)}>${word.text}")
            }
            // An end marker on plain LRC would enable word highlighting in the existing renderer.
            if (line.words.isNotEmpty()) append("<${stamp(line.end)}>")
        }
    }

    fun translationLyric(): String = lines.mapNotNull { line ->
        line.translation.ifBlank { line.roman }.takeIf { it.isNotBlank() }
            ?.let { "[${stamp(line.start)}]$it" }
    }.joinToString("\n")

    companion object {
        @JvmStatic fun stamp(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d.%03d",
            ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60, ms.coerceAtLeast(0) % 1000)
    }
}

object OnlineLyricSelection {
    @JvmField val SOURCES = listOf("amll", "netease", "qqmusic", "kugou")
    private val punctuation = Regex("[、&;，,/|()·・\\s\\-_'\"`~!?？！.。]+")
    private fun normalize(s: String) = punctuation.replace(s.lowercase(Locale.ROOT), "")
    private fun artists(s: String) = s.split(Regex("[、&;，,/|·・]+")).map(::normalize).filter { it.isNotEmpty() }
    private fun contains(a: String, b: String) = a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))

    /** SPlayer's hard filters precede its title/artist/album/duration score. */
    @JvmStatic fun score(track: LyricTrack, candidate: LyricTrack): Int {
        val name = normalize(track.title)
        val other = normalize(candidate.title)
        val exact = name.isNotEmpty() && name == other
        if (!exact && (!contains(name, other) || minOf(name.length, other.length).toDouble() /
                    maxOf(name.length, other.length) < 0.34)) return -1
        val timed = track.duration > 0 && candidate.duration > 0
        val delta = abs(track.duration - candidate.duration)
        if (timed && delta > 20000) return -1
        val expected = artists(track.artist)
        val actual = artists(candidate.artist) + normalize(candidate.artist)
        val artistExact = expected.any { it in actual }
        val artistContains = expected.any { a -> a.length >= 2 && actual.any { contains(a, it) } }
        if (expected.isNotEmpty() && !artistExact && !artistContains) return -1
        if (!exact && !artistExact && !artistContains && !(timed && delta <= 5000)) return -1
        return (if (exact) 10 else 4) + (if (artistExact) 5 else if (artistContains) 2 else 0) +
            (if (normalize(track.album).isNotEmpty() && normalize(track.album) == normalize(candidate.album)) 2 else 0) +
            (if (timed && delta <= 5000) 3 else 0)
    }

    @JvmStatic fun order(value: String?): List<String> =
        ((value ?: "").split(',').filter { it in SOURCES } + SOURCES).distinct()

    /** Stable tie-breaking is independent of completion order and uses the latest preference. */
    @JvmStatic fun best(results: List<OnlineLyric>, order: List<String>): OnlineLyric? = results
        .filter { it.score >= 0 && it.lines.isNotEmpty() }
        .sortedWith(compareByDescending<OnlineLyric> { it.quality }.thenByDescending { it.score }
            .thenBy { order.indexOf(it.source).let { rank -> if (rank < 0) order.size else rank } })
        .firstOrNull()
}
