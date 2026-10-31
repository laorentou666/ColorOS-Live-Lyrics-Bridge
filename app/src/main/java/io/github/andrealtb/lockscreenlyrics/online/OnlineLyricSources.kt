// SPDX-License-Identifier: AGPL-3.0-only
// Requests and response mappings ported from SPlayer-Next's built-in lyric providers.
package io.github.andrealtb.lockscreenlyrics.online

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

class LyricRequest {
    private val connections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    private val stopped = AtomicBoolean(false)
    fun check() { if (stopped.get()) throw CancellationException() }
    internal fun attach(connection: HttpURLConnection) { connections.add(connection); check() }
    internal fun detach(connection: HttpURLConnection) { connections.remove(connection) }
    fun cancel() {
        stopped.set(true)
        synchronized(connections) { connections.forEach { it.disconnect() }; connections.clear() }
    }
}

fun interface LyricHttp {
    fun request(url: String, body: String?, headers: Map<String, String>, request: LyricRequest): String
}

class UrlLyricHttp : LyricHttp {
    override fun request(url: String, body: String?, headers: Map<String, String>, request: LyricRequest): String {
        request.check()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            request.attach(connection)
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "ColorOS-Live-Lyrics-Bridge/4.5.3")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val stream = if (connection.contentEncoding == "gzip") GZIPInputStream(connection.inputStream) else connection.inputStream
            val result = String(LyricCrypto.readLimited(stream), Charsets.UTF_8)
            request.check()
            return result
        } finally {
            request.detach(connection)
            connection.disconnect()
        }
    }
}

class OnlineLyricSources @JvmOverloads constructor(private val http: LyricHttp = UrlLyricHttp()) {
    private fun json(vararg pairs: Pair<String, Any>) = JSONObject(linkedMapOf(*pairs))
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
        (0 until length()).mapNotNull { optJSONObject(it) }
    private fun singers(array: JSONArray?) = array.objects().joinToString(" / ") { it.optString("name") }
    private fun keyword(track: LyricTrack) = "${track.title} ${track.artist}".trim()
    private fun best(track: LyricTrack, songs: List<LyricSong>): LyricSong? = songs
        .filter { it.id.isNotBlank() && OnlineLyricSelection.score(track, it.track) >= 0 }
        .maxByOrNull { OnlineLyricSelection.score(track, it.track) }
    private fun get(url: String, request: LyricRequest, headers: Map<String, String> = emptyMap()) =
        JSONObject(http.request(url, null, headers, request))

    private fun netease(path: String, data: JSONObject, request: LyricRequest): JSONObject {
        val header = json("os" to "pc", "appver" to "3.1.3.203419", "osver" to "Microsoft-Windows-10",
            "deviceId" to "bridge", "requestId" to System.currentTimeMillis().toString(),
            "versioncode" to "140", "resolution" to "1920x1080", "channel" to "netease", "__csrf" to "")
        data.put("header", header).put("e_r", false)
        return JSONObject(http.request("https://interfacepc.music.163.com/eapi/${path.removePrefix("/api/")}",
            "params=${LyricCrypto.netease(path, data.toString())}", mapOf(
                "Content-Type" to "application/x-www-form-urlencoded", "Referer" to "https://music.163.com",
                "Cookie" to "os=pc; appver=3.1.3.203419;", "User-Agent" to "Mozilla/5.0"), request))
    }

    fun searchNetease(track: LyricTrack, request: LyricRequest): LyricSong? {
        val data = netease("/api/search/get", json("s" to keyword(track), "type" to 1, "limit" to 20, "offset" to 0), request)
        return best(track, data.optJSONObject("result")?.optJSONArray("songs").objects().map { song ->
            LyricSong(song.optString("id"), "", LyricTrack(song.optString("name"), singers(song.optJSONArray("artists")),
                song.optJSONObject("album")?.optString("name").orEmpty(), song.optLong("duration")))
        })
    }

    private fun result(source: String, format: String, text: String, song: LyricSong, track: LyricTrack,
        translation: String = "", roman: String = ""): OnlineLyric? {
        if (text.isBlank()) return null
        val lines = runCatching { OnlineLyricParser.parse(format, text, translation, roman, track.duration) }.getOrDefault(emptyList())
        return lines.takeIf { it.isNotEmpty() }?.let { OnlineLyric(source, format, OnlineLyricSelection.score(track, song.track), it) }
    }

    fun fetchNetease(song: LyricSong, track: LyricTrack, request: LyricRequest): OnlineLyric? {
        val body = netease("/api/song/lyric/v1", json("id" to song.id, "cp" to false, "tv" to 0,
            "lv" to 0, "rv" to 0, "kv" to 0, "yv" to 0, "ytv" to 0, "yrv" to 0), request)
        fun lyric(key: String) = body.optJSONObject(key)?.optString("lyric").orEmpty()
        val translation = lyric("ytlrc").ifBlank { lyric("tlyric") }
        val roman = lyric("yromalrc").ifBlank { lyric("romalrc") }
        return result("netease", "yrc", lyric("yrc"), song, track, translation, roman)
            ?: result("netease", "lrc", lyric("lrc"), song, track, translation, roman)
    }

    private fun qq(module: String, method: String, param: JSONObject, request: LyricRequest, signed: Boolean = false): JSONObject {
        val comm = json("ct" to 11, "cv" to 14090008, "v" to 14090008, "chid" to "10003505",
            "os_ver" to "15", "phonetype" to "24122RKC7C", "tmeAppID" to "qqmusic", "nettype" to "NETWORK_WIFI",
            "udid" to "0", "OpenUDID" to "0", "QIMEI36" to "0", "uin" to "0")
        if (signed) comm.put("tmeAppID", "qqmusiclight").put("cv", "1003006").put("v", "1003006")
        val body = json("comm" to comm, "request" to json("module" to module, "method" to method, "param" to param)).toString()
        val endpoint = if (signed) "musics.fcg?sign=${LyricCrypto.qqSign(body)}" else "musicu.fcg"
        val response = JSONObject(http.request("https://u.y.qq.com/cgi-bin/$endpoint", body, mapOf(
            "Content-Type" to "application/json", "Referer" to "https://y.qq.com",
            "User-Agent" to "QQMusic 14090008(android 15)", "Cookie" to "tmeLoginType=-1;"), request))
        require(response.optInt("code", -1) == 0 && response.optJSONObject("request")?.optInt("code", -1) == 0)
        return response.getJSONObject("request").getJSONObject("data")
    }

    fun searchQq(track: LyricTrack, request: LyricRequest): LyricSong? {
        val searchId = (java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 21) * 18014398509481984L +
            java.util.concurrent.ThreadLocalRandom.current().nextLong(4194305) * 4294967296L + System.currentTimeMillis() % 86400000).toString()
        val data = qq("music.search.SearchCgiService", "DoSearchForQQMusicLite", json("search_id" to searchId,
            "remoteplace" to "search.android.keyboard", "query" to keyword(track), "page_num" to 1, "num_per_page" to 25,
            "search_type" to 0, "highlight" to 0, "nqc_flag" to 0, "page_id" to 1, "grp" to 1), request, true)
        return best(track, data.optJSONObject("body")?.optJSONArray("item_song").objects().map { song ->
            val album = song.optJSONObject("album")
            LyricSong(song.optString("id"), song.optString("mid"), LyricTrack(song.optString("title").ifBlank { song.optString("name") },
                singers(song.optJSONArray("singer")), album?.optString("name").orEmpty().ifBlank { album?.optString("title").orEmpty() },
                song.optLong("interval") * 1000))
        })
    }

    fun fetchQq(song: LyricSong, track: LyricTrack, request: LyricRequest): OnlineLyric? {
        fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())
        val param = json("albumName" to b64(song.track.album), "crypt" to 1, "ct" to 19, "cv" to 2111,
            "interval" to song.track.duration / 1000, "lrc_t" to 0, "qrc" to 1, "qrc_t" to 0,
            "roma" to 1, "roma_t" to 0, "singerName" to b64(song.track.artist), "songID" to song.id.toLong(),
            "songName" to b64(song.track.title), "trans" to 1, "trans_t" to 0, "type" to 0)
        fun call() = qq("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", param, request)
        fun decrypt(body: JSONObject, key: String) = runCatching { LyricCrypto.qrc(body.optString(key)) }.getOrDefault("")
        val body = call()
        val trans = decrypt(body, "trans")
        val roman = decrypt(body, "roma")
        val format = if (body.optInt("qrc_t") == 0) "lrc" else "qrc"
        result("qqmusic", format, decrypt(body, "lyric"), song, track, trans, roman)?.let { return it }
        if (format == "lrc") return null
        param.put("qrc", 0)
        return result("qqmusic", "lrc", decrypt(call(), "lyric"), song, track, trans, roman)
    }

    fun searchKugou(track: LyricTrack, request: LyricRequest): LyricSong? {
        val query = "keyword=${encode(keyword(track))}&page=1&pagesize=25"
        val mobile = runCatching { get("https://mobilecdn.kugou.com/api/v3/search/song?$query&format=json&showtype=1", request)
            .optJSONObject("data")?.optJSONArray("info").objects() }.getOrDefault(emptyList())
        val songs = if (mobile.isNotEmpty()) mobile.flatMap { listOf(it) + it.optJSONArray("group").objects() }.map {
            LyricSong(it.optString("hash"), "", LyricTrack(OnlineLyricParser.entities(it.optString("songname").ifBlank { it.optString("filename") }),
                OnlineLyricParser.entities(it.optString("singername")), OnlineLyricParser.entities(it.optString("album_name")), it.optLong("duration") * 1000))
        } else {
            val data = get("https://songsearch.kugou.com/song_search_v2?$query&userid=0&clientver=&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1", request)
            data.optJSONObject("data")?.optJSONArray("lists").objects().flatMap { listOf(it) + it.optJSONArray("Grp").objects() }.map {
                LyricSong(it.optString("FileHash"), "", LyricTrack(OnlineLyricParser.entities(it.optString("SongName")),
                    OnlineLyricParser.entities(singers(it.optJSONArray("Singers"))), OnlineLyricParser.entities(it.optString("AlbumName")), it.optLong("Duration") * 1000))
            }
        }
        return best(track, songs.distinctBy { it.id })
    }

    fun fetchKugou(song: LyricSong, track: LyricTrack, request: LyricRequest): OnlineLyric? {
        val headers = mapOf("KG-RC" to "1", "KG-THash" to "expand_search_manager.cpp:852736169:451",
            "User-Agent" to "KuGou2012-9020-ExpandSearchManager")
        val search = get("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&lrctxt=1&keyword=${encode(song.track.title)}&hash=${encode(song.id)}&timelength=${song.track.duration / 1000}", request, headers)
        val candidate = search.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val fmt = if (candidate.optInt("krctype") == 1 && candidate.optInt("contenttype") != 1) "krc" else "lrc"
        val data = get("https://lyrics.kugou.com/download?ver=1&client=pc&charset=utf8&id=${encode(candidate.optString("id"))}&accesskey=${encode(candidate.getString("accesskey"))}&fmt=$fmt", request, headers)
        val content = data.optString("content")
        val format = data.optString("fmt", fmt)
        val text = if (format == "krc") LyricCrypto.krc(content) else String(Base64.getDecoder().decode(content), Charsets.UTF_8)
        return result("kugou", format, text, song, track)
    }

    fun fetchTtml(platform: String, song: LyricSong, track: LyricTrack, request: LyricRequest): OnlineLyric? {
        val ids = if (platform == "qqmusic") listOf(song.mid, song.id).filter { it.isNotBlank() }.distinct() else listOf(song.id)
        for (id in ids) {
            request.check()
            val found = runCatching {
                val query = if (platform == "netease") "ncmMusicId" else "qqMusicId"
                val text = get("https://api.amll.dev/v1/lyrics/get?$query=${encode(id)}", request)
                    .optJSONObject("data")?.optString("lyrics").orEmpty()
                result("amll", "ttml", text, song, track)
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    /** Launch platform searches together; share matched IDs with the concurrent AMLL branches. */
    fun fetchAll(track: LyricTrack, request: LyricRequest, executor: Executor): CompletableFuture<List<OnlineLyric>> {
        fun <T> async(block: () -> T): CompletableFuture<T?> = CompletableFuture.supplyAsync({
            request.check()
            runCatching(block).getOrNull()
        }, executor)
        val netease = async { searchNetease(track, request) }
        val qq = async { searchQq(track, request) }
        val kugou = async { searchKugou(track, request)?.let { fetchKugou(it, track, request) } }
        val neteaseLyric = netease.thenCompose { song -> async { song?.let { fetchNetease(it, track, request) } } }
        val qqLyric = qq.thenCompose { song -> async { song?.let { fetchQq(it, track, request) } } }
        val neteaseTtml = netease.thenCompose { song -> async { song?.let { fetchTtml("netease", it, track, request) } } }
        val qqTtml = qq.thenCompose { song -> async { song?.let { fetchTtml("qqmusic", it, track, request) } } }
        val tasks = listOf(neteaseLyric, qqLyric, kugou, neteaseTtml, qqTtml)
        return CompletableFuture.allOf(*tasks.toTypedArray()).thenApply { tasks.mapNotNull { it.join() } }
    }
}
