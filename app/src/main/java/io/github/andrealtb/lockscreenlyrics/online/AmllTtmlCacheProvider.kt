package io.github.andrealtb.lockscreenlyrics.online

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.Executors

class AmllTtmlCacheProvider : ContentProvider() {
    private val writer = Executors.newSingleThreadExecutor()

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val caller = Binder.getCallingUid()
        if (caller != Process.myUid() && caller != Process.SYSTEM_UID && callingPackage != "com.android.systemui") {
            throw SecurityException("AMLL TTML cache is only accessible to Bridge and SystemUI")
        }
        val key = uri.pathSegments.singleOrNull()?.takeIf(KEY_PATTERN::matches)
            ?: throw FileNotFoundException("Invalid AMLL TTML cache entry")
        val app = requireNotNull(context)
        val file = File(directory(app), "$key.ttml")
        return when (mode) {
            "r" -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            "w" -> {
                val revision = generation
                val (readSide, writeSide) = ParcelFileDescriptor.createReliablePipe()
                writer.execute {
                    try {
                        val bytes = LyricCrypto.readLimited(ParcelFileDescriptor.AutoCloseInputStream(readSide))
                        if (bytes.isNotEmpty()) store(app, file, bytes, revision)
                    } catch (error: IOException) {
                        Log.w(AmllTtmlCache.TAG, "Unable to store AMLL TTML", error)
                    } catch (error: IllegalArgumentException) {
                        Log.w(AmllTtmlCache.TAG, "AMLL TTML exceeds the cache entry limit", error)
                    }
                }
                writeSide
            }
            else -> throw FileNotFoundException("Unsupported AMLL TTML cache mode")
        }
    }

    override fun getType(uri: Uri) = "application/ttml+xml"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private const val PREFERENCES_NAME = "amll_ttml_cache"
        private const val LIMIT_KEY = "limit_mb"
        private const val DEFAULT_LIMIT_MB = 1000
        private val KEY_PATTERN = Regex("[0-9a-f]{64}")
        private var totalBytes: Long? = null
        @Volatile private var generation = 0

        private fun preferences(context: Context) =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        private fun directory(context: Context) = File(context.cacheDir, "amll-ttml")

        @JvmStatic fun readLimitMb(context: Context) = preferences(context).getInt(LIMIT_KEY, DEFAULT_LIMIT_MB)

        @JvmStatic @Synchronized @Throws(IOException::class)
        fun setLimitMb(context: Context, limitMb: Int) {
            if (!preferences(context).edit().putInt(LIMIT_KEY, limitMb).commit()) {
                throw IOException("Unable to save AMLL TTML cache limit")
            }
            totalBytes = null
            trim(context)
        }

        @JvmStatic @Synchronized @Throws(IOException::class)
        fun clear(context: Context) {
            // Writes opened before a manual clear may still be streaming over the pipe.
            generation++
            totalBytes = null
            files(context).forEach { Files.deleteIfExists(it.toPath()) }
            totalBytes = 0L
        }

        @Synchronized private fun store(context: Context, file: File, bytes: ByteArray, revision: Int) {
            if (revision != generation) return
            val dir = directory(context)
            Files.createDirectories(dir.toPath())
            val previousBytes = file.length()
            val currentBytes = totalBytes ?: files(context).filter { it.extension == "ttml" }.sumOf(File::length)
            val pending = File(dir, ".pending").toPath()
            try {
                Files.write(pending, bytes)
                Files.move(pending, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(pending)
            }
            totalBytes = currentBytes - previousBytes + bytes.size
            trim(context)
        }

        private fun files(context: Context): Array<File> {
            val dir = directory(context)
            return dir.listFiles() ?: if (dir.exists()) throw IOException("Unable to list AMLL TTML cache")
                else emptyArray()
        }

        private fun trim(context: Context) {
            val limit = readLimitMb(context) * 1_000_000L
            if (totalBytes?.let { it < limit } == true) return
            val entries = files(context).filter { it.extension == "ttml" }
                .map { Triple(it, it.length(), it.lastModified()) }
            var size = entries.sumOf { it.second }
            totalBytes = size
            if (size < limit) return
            // Leave space for new tracks so each write near the limit needn't rescan the directory.
            val target = limit * 9 / 10
            for ((file, length) in entries.sortedBy { it.third }) {
                Files.deleteIfExists(file.toPath())
                size -= length
                totalBytes = size
                if (size <= target) break
            }
        }
    }
}
