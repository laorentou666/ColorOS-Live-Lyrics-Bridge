package io.github.andrealtb.lockscreenlyrics.online

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal class AmllTtmlCache(private val context: Context) {
    private val directory = File(context.cacheDir, "amll-ttml")
    private var totalBytes: Long? = null

    fun read(platform: String, id: String): String? = try {
        entry(platform, id).inputStream().use { String(LyricCrypto.readLimited(it), Charsets.UTF_8) }
    } catch (_: FileNotFoundException) {
        null
    } catch (error: IOException) {
        Log.w(TAG, "Unable to read AMLL TTML cache", error)
        null
    } catch (error: RuntimeException) {
        Log.w(TAG, "Unable to read AMLL TTML cache", error)
        null
    }

    @Synchronized fun write(platform: String, id: String, ttml: String) {
        try {
            val bytes = ttml.toByteArray(Charsets.UTF_8)
            require(bytes.isNotEmpty() && bytes.size <= MAX_ENTRY_BYTES) { "Invalid AMLL TTML cache entry" }
            store(entry(platform, id), bytes)
        } catch (error: IOException) {
            Log.w(TAG, "Unable to write AMLL TTML cache", error)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "AMLL TTML exceeds the cache entry limit", error)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to write AMLL TTML cache", error)
        }
    }

    @Synchronized fun setLimitMb(limitMb: Int) {
        try {
            saveLimitMb(context, limitMb)
            totalBytes = null
            trim()
        } catch (error: IOException) {
            Log.w(TAG, "Unable to update AMLL TTML cache limit", error)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to update AMLL TTML cache limit", error)
        }
    }

    @Synchronized fun clear() {
        try {
            totalBytes = null
            files().forEach { Files.deleteIfExists(it.toPath()) }
            totalBytes = 0L
        } catch (error: IOException) {
            Log.w(TAG, "Unable to clear AMLL TTML cache", error)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to clear AMLL TTML cache", error)
        }
    }

    private fun entry(platform: String, id: String): File =
        File(directory, "${LyricCrypto.hex(LyricCrypto.hash("SHA-256", "$platform:$id"))}.ttml")

    private fun store(file: File, bytes: ByteArray) {
        Files.createDirectories(directory.toPath())
        val previousBytes = file.length()
        val currentBytes = totalBytes ?: files().filter { it.extension == "ttml" }.sumOf(File::length)
        val pending = File(directory, ".pending").toPath()
        try {
            Files.write(pending, bytes)
            Files.move(pending, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending)
        }
        totalBytes = currentBytes - previousBytes + bytes.size
        trim()
    }

    private fun files(): Array<File> = directory.listFiles()
        ?: if (directory.exists()) throw IOException("Unable to list AMLL TTML cache") else emptyArray()

    private fun trim() {
        val limit = readLimitMb(context) * 1_000_000L
        if (totalBytes?.let { it < limit } == true) return
        val entries = files().filter { it.extension == "ttml" }
            .map { Triple(it, it.length(), it.lastModified()) }
        var size = entries.sumOf { it.second }
        totalBytes = size
        if (size < limit) return
        val target = limit * 9 / 10
        for ((file, length) in entries.sortedBy { it.third }) {
            Files.deleteIfExists(file.toPath())
            size -= length
            totalBytes = size
            if (size <= target) break
        }
    }

    companion object {
        const val TAG = "BridgeAmllCache"
        private const val PREFERENCES_NAME = "amll_ttml_cache"
        private const val LIMIT_KEY = "limit_mb"
        private const val DEFAULT_LIMIT_MB = 1000
        private const val MAX_ENTRY_BYTES = 2_000_000

        @JvmStatic fun readLimitMb(context: Context) = context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getInt(LIMIT_KEY, DEFAULT_LIMIT_MB)

        @Synchronized private fun saveLimitMb(context: Context, limitMb: Int) {
            require(limitMb in 1..4000) { "Invalid AMLL TTML cache limit" }
            if (!context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit().putInt(LIMIT_KEY, limitMb).commit()) {
                throw IOException("Unable to save AMLL TTML cache limit")
            }
        }
    }
}
