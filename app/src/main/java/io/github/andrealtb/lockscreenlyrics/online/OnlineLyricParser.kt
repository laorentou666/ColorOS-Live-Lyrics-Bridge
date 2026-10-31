// SPDX-License-Identifier: AGPL-3.0-only
// SPlayer-Next / lyric-kit format semantics, adapted to Bridge's single lyric timeline.
package io.github.andrealtb.lockscreenlyrics.online

import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.roundToLong

object OnlineLyricParser {
    private val lineTag = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val msTag = Regex("^\\[(\\d+),(\\d+)]")
    private val yrcWord = Regex("\\((\\d+),(\\d+),\\d+\\)")
    private val qrcWord = Regex("([\\s\\S]*?)\\((\\d+),(\\d+)\\)")
    private val krcWord = Regex("<(\\d+),(\\d+)(?:,\\d+)?>([^<]*)")
    private val inlineTag = Regex("<(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?>")

    @JvmStatic @JvmOverloads fun parse(format: String, text: String, translation: String = "",
        roman: String = "", duration: Long = 0): List<LyricLine> {
        if (text.length > 2_000_000) return emptyList()
        val parsed = when (format) {
            "ttml" -> ttml(text)
            "yrc", "qrc", "krc" -> wordLines(format, text)
            else -> lrc(text)
        }
        fun auxiliary(value: String) = runCatching {
            if (value.contains("LyricContent=") || Regex("(?m)^\\[\\d+,\\d+]").containsMatchIn(value))
                wordLines("qrc", value) else lrc(value)
        }.getOrDefault(emptyList())
        val trans = auxiliary(translation)
        val roma = auxiliary(roman)
        val valid = parsed.filter { it.text.isNotBlank() && it.start >= 0 &&
            (duration <= 0 || it.start <= duration + 20000) }.sortedBy { it.start }
        return valid.mapIndexed { index, line ->
            var previous = line.start
            val words = line.words.filter { it.text.isNotEmpty() }.map { word ->
                val start = word.start.coerceAtLeast(previous)
                previous = start
                word.copy(start = start, end = word.end.coerceAtLeast(start))
            }
            val next = valid.drop(index + 1).firstOrNull { it.start > line.start }?.start
            val end = maxOf(line.start, line.end, words.maxOfOrNull { it.end } ?: 0).let {
                if (it > line.start) it else next ?: duration.takeIf { d -> d > line.start } ?: (line.start + 8000)
            }
            line.copy(end = end, words = words,
                translation = line.translation.ifBlank { align(trans, line.start, index, valid.size) },
                roman = line.roman.ifBlank { align(roma, line.start, index, valid.size) })
        }
    }

    private fun align(lines: List<LyricLine>, start: Long, index: Int, count: Int): String {
        val nearest = lines.minByOrNull { abs(it.start - start) }
        return when {
            nearest != null && abs(nearest.start - start) <= 500 -> nearest.text
            lines.size == count -> lines.getOrNull(index)?.text.orEmpty()
            else -> ""
        }
    }

    private fun tagTime(m: MatchResult): Long = m.groupValues[1].toLong() * 60000 +
        m.groupValues[2].toLong() * 1000 + m.groupValues[3].padEnd(3, '0').take(3).toLong()

    private fun lrc(text: String): List<LyricLine> = text.lineSequence().flatMap { raw ->
        val tags = lineTag.findAll(raw).takeWhile { raw.substring(0, it.range.first)
            .replace(lineTag, "").isBlank() }.toList()
        if (tags.isEmpty()) return@flatMap emptySequence()
        val content = raw.substring(tags.last().range.last + 1)
        val inline = inlineTag.findAll(content).toList()
        val words = inline.zipWithNext().mapNotNull { (tag, next) ->
            val word = content.substring(tag.range.last + 1, next.range.first)
            word.takeIf { it.isNotEmpty() }?.let { LyricWord(it, tagTime(tag), tagTime(next)) }
        }.toMutableList()
        inline.lastOrNull()?.let { tag ->
            content.substring(tag.range.last + 1).takeIf { it.isNotEmpty() }?.let {
                words.add(LyricWord(it, tagTime(tag), tagTime(tag) + 500))
            }
        }
        tags.asSequence().map { tag -> LyricLine(tagTime(tag), inline.lastOrNull()?.let(::tagTime) ?: 0,
            entities(content.replace(inlineTag, "").trim()), words) }
    }.toList()

    private fun wordLines(format: String, input: String): List<LyricLine> {
        // QQ wraps multiline lyric text inside an XML attribute, with both literal and escaped newlines.
        val text = if (format == "qrc" && input.contains("<![CDATA[")) {
            Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>").find(input)?.groupValues?.get(1) ?: input
        } else if (format == "qrc" && input.contains("LyricContent=")) {
            entities(Regex("LyricContent=\"([\\s\\S]*?)\"\\s*/?>").find(input)?.groupValues?.get(1) ?: input)
        } else input
        val result = text.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            val head = msTag.find(line)
            val timeHead = if (head == null && format == "krc") lineTag.find(line) else null
            if (head == null && timeHead == null) return@mapNotNull null
            val start = head?.groupValues?.get(1)?.toLong() ?: tagTime(timeHead!!)
            val duration = head?.groupValues?.get(2)?.toLong() ?: 0
            val body = line.substring((head ?: timeHead!!).range.last + 1)
            val words = when (format) {
                "yrc" -> yrcWord.findAll(body).toList().let { tags -> tags.mapIndexed { index, tag ->
                    LyricWord(entities(body.substring(tag.range.last + 1, tags.getOrNull(index + 1)?.range?.first ?: body.length)),
                        tag.groupValues[1].toLong(), tag.groupValues[1].toLong() + tag.groupValues[2].toLong())
                }.asSequence() }
                "qrc" -> qrcWord.findAll(body).map { LyricWord(entities(it.groupValues[1]),
                    it.groupValues[2].toLong(), it.groupValues[2].toLong() + it.groupValues[3].toLong()) }
                else -> krcWord.findAll(body).map { LyricWord(entities(it.groupValues[3]),
                    start + it.groupValues[1].toLong(), start + it.groupValues[1].toLong() + it.groupValues[2].toLong()) }
            }.toList()
            if (words.isEmpty()) null else LyricLine(start, start + duration, words.joinToString("") { it.text }, words)
        }.toList()
        if (format != "krc") return result
        val language = Regex("(?m)^\\[language:(.*)]\\s*$").find(text)?.groupValues?.get(1) ?: return result
        return runCatching {
            val json = if (language.startsWith('{')) language else String(Base64.getDecoder().decode(language), Charsets.UTF_8)
            val content = JSONObject(json).optJSONArray("content") ?: return result
            var lines = result
            for (i in 0 until content.length()) {
                val entry = content.getJSONObject(i)
                val rows = entry.optJSONArray("lyricContent") ?: continue
                lines = lines.mapIndexed { index, line ->
                    val row = rows.optJSONArray(index)
                    val value = if (row == null) rows.optString(index, "") else (0 until row.length()).joinToString("") { row.optString(it) }
                    if (entry.optInt("type", -1) == 1) line.copy(translation = entities(value))
                    else if (entry.optInt("type", -1) == 0) line.copy(roman = entities(value)) else line
                }
            }
            lines
        }.getOrDefault(result)
    }

    internal fun xml(text: String): Element {
        require(!text.contains("<!DOCTYPE", ignoreCase = true))
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement
    }

    private fun Element.attr(name: String): String = (0 until attributes.length)
        .map { attributes.item(it) }.firstOrNull { (it.localName ?: it.nodeName) == name }?.nodeValue.orEmpty()
    private fun Element.elements(): List<Element> = (0 until childNodes.length)
        .map { childNodes.item(it) }.filterIsInstance<Element>()
    private fun Element.descendants(): List<Element> = elements().flatMap { listOf(it) + it.descendants() }
    private fun Element.tag() = localName ?: tagName.substringAfter(':')
    private fun clean(text: String) = text.replace(Regex("\\s+"), " ").trim()
    private fun mainText(node: Node): String = if (node is Element) {
        if (node.attr("role") in listOf("x-bg", "x-translation", "x-roman") ||
            node.attr("ruby") in listOf("text", "textContainer")) ""
        else (0 until node.childNodes.length).joinToString("") { mainText(node.childNodes.item(it)) }
    } else node.nodeValue.orEmpty()

    /** Flatten duet voices; retain background lines only outside every lead-vocal interval. */
    private fun ttml(text: String): List<LyricLine> {
        val root = xml(text)
        val frameRate = root.attr("frameRate").toDoubleOrNull() ?: 30.0
        val tickRate = root.attr("tickRate").toDoubleOrNull() ?: 1.0
        fun time(value: String): Long? {
            if (value.isBlank()) return null
            val unit = Regex("^([0-9.]+)(ms|h|m|s|f|t)$").matchEntire(value)
            if (unit != null) return (unit.groupValues[1].toDouble() * when (unit.groupValues[2]) {
                "ms" -> 1.0; "h" -> 3600000.0; "m" -> 60000.0; "f" -> 1000.0 / frameRate
                "t" -> 1000.0 / tickRate; else -> 1000.0
            }).roundToLong()
            val parts = value.split(':').map { it.toDouble() }
            return if (parts.size == 4) (parts[0] * 3600000 + parts[1] * 60000 + parts[2] * 1000 + parts[3] / frameRate * 1000).roundToLong()
            else (parts.fold(0.0) { a, b -> a * 60 + b } * 1000).roundToLong()
        }
        val all = root.descendants()
        val sidecars = all.filter { it.tag() == "text" && it.attr("for").isNotBlank() }.groupBy { it.attr("for") }
        val backgrounds = mutableListOf<LyricLine>()
        fun sidecar(key: String, roman: Boolean, background: Boolean): String = sidecars[key].orEmpty().filter {
            var parent = it.parentNode
            var kind = ""
            while (parent is Element && kind.isEmpty()) {
                if (parent.tag() in listOf("translation", "transliteration")) kind = parent.tag()
                parent = parent.parentNode
            }
            kind == if (roman) "transliteration" else "translation"
        }.sortedBy { if ((it.parentNode as? Element)?.attr("lang")?.startsWith("zh") == true) 0 else 1 }
            .firstOrNull()?.let { text ->
                if (background) clean(text.descendants().filter { it.attr("role") == "x-bg" }
                    .joinToString(" ") { it.textContent }) else clean(mainText(text))
            }.orEmpty()

        fun paragraph(p: Element, fallbackStart: Long = 0, fallbackEnd: Long = 0,
            bg: Boolean = false, parentKey: String = ""): LyricLine? {
            val begin = time(p.attr("begin")) ?: fallbackStart
            val finish = time(p.attr("end")) ?: time(p.attr("dur"))?.let { begin + it } ?: fallbackEnd
            val key = p.attr("key").ifBlank { p.attr("id") }.ifBlank { parentKey }
            val words = mutableListOf<LyricWord>()
            val translations = mutableListOf<Pair<String, String>>()
            val romans = mutableListOf<String>()
            var hasWordTiming = false
            fun walk(element: Element, inheritedStart: Long, inheritedEnd: Long) {
                for (i in 0 until element.childNodes.length) {
                    val node = element.childNodes.item(i)
                    if (node is Element) {
                        when {
                            node.attr("role") == "x-bg" -> paragraph(node, begin, finish, true, key)?.let(backgrounds::add)
                            node.attr("role") == "x-translation" -> translations.add(node.attr("lang") to clean(node.textContent))
                            node.attr("role") == "x-roman" -> romans.add(clean(node.textContent))
                            node.attr("ruby") in listOf("text", "textContainer") -> Unit
                            else -> {
                                val start = time(node.attr("begin")) ?: inheritedStart
                                val end = time(node.attr("end")) ?: time(node.attr("dur"))?.let { start + it } ?: inheritedEnd
                                if (node.attr("begin").isNotBlank() && (node.attr("end").isNotBlank() || node.attr("dur").isNotBlank())) hasWordTiming = true
                                walk(node, start, end)
                            }
                        }
                    } else if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                        val raw = node.nodeValue.orEmpty()
                        if (raw.isBlank() && raw.contains('\n')) continue
                        val value = raw.replace(Regex("\\s+"), " ")
                        if (value.isBlank() && words.isNotEmpty()) {
                            words[words.lastIndex] = words.last().copy(text = words.last().text + value)
                        } else if (value.isNotEmpty()) words.add(LyricWord(value, inheritedStart, inheritedEnd))
                    }
                }
            }
            walk(p, begin, finish)
            if (words.isEmpty()) return null
            words[0] = words[0].copy(text = words[0].text.trimStart())
            words[words.lastIndex] = words.last().copy(text = words.last().text.trimEnd())
            val content = words.joinToString("") { it.text }
            if (content.isBlank()) return null
            val start = if (hasWordTiming) {
                val first = words.minOf { it.start }
                if (p.attr("begin").isBlank()) first else minOf(begin, first)
            } else begin
            val end = maxOf(finish, words.maxOf { it.end })
            return LyricLine(start, end, content, if (hasWordTiming) words else emptyList(),
                sidecar(key, false, bg).ifBlank { translations.sortedBy { if (it.first.startsWith("zh")) 0 else 1 }.firstOrNull()?.second.orEmpty() },
                sidecar(key, true, bg).ifBlank { romans.firstOrNull().orEmpty() })
        }
        val leads = mutableListOf<LyricLine>()
        for (p in all.filter { it.tag() == "body" }.flatMap { it.descendants() }.filter { it.tag() == "p" }) {
            if (p.attr("role") in listOf("x-translation", "x-roman")) continue
            val line = paragraph(p, bg = p.attr("role") == "x-bg") ?: continue
            if (p.attr("role") == "x-bg") backgrounds.add(line) else leads.add(line)
        }
        return leads + backgrounds.filter { bg ->
            val start = bg.words.minOfOrNull { it.start } ?: bg.start
            val end = bg.words.maxOfOrNull { it.end } ?: bg.end
            leads.none { lead ->
                val leadStart = lead.words.minOfOrNull { it.start } ?: lead.start
                val leadEnd = lead.words.maxOfOrNull { it.end } ?: lead.end
                start < leadEnd && end > leadStart
            }
        }.map { it.copy(start = it.words.minOfOrNull { w -> w.start } ?: it.start,
            end = it.words.maxOfOrNull { w -> w.end } ?: it.end) }
    }

    internal fun entities(text: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);").replace(text) { m ->
        when (val key = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
            else -> runCatching { String(Character.toChars(if (key.startsWith("#x")) key.drop(2).toInt(16) else key.drop(1).toInt())) }.getOrDefault(m.value)
        }
    }
}
