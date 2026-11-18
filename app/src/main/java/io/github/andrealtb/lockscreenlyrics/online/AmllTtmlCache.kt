package io.github.andrealtb.lockscreenlyrics.online

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.andrealtb.lockscreenlyrics.BuildConfig
import java.io.FileNotFoundException
import java.io.IOException

internal class AmllTtmlCache(private val resolver: ContentResolver) {
    fun read(platform: String, id: String): String? = try {
        resolver.openFileDescriptor(entry(platform, id), "r")?.let {
            String(LyricCrypto.readLimited(ParcelFileDescriptor.AutoCloseInputStream(it)), Charsets.UTF_8)
        }
    } catch (_: FileNotFoundException) {
        null
    } catch (error: IOException) {
        Log.w(TAG, "Unable to read AMLL TTML cache", error)
        null
    } catch (error: RuntimeException) {
        Log.w(TAG, "Unable to read AMLL TTML cache", error)
        null
    }

    fun write(platform: String, id: String, ttml: String) {
        try {
            resolver.openFileDescriptor(entry(platform, id), "w")?.let {
                ParcelFileDescriptor.AutoCloseOutputStream(it).use { stream ->
                    stream.write(ttml.toByteArray(Charsets.UTF_8))
                }
            }
        } catch (error: IOException) {
            Log.w(TAG, "Unable to write AMLL TTML cache", error)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to write AMLL TTML cache", error)
        }
    }

    private fun entry(platform: String, id: String): Uri = CONTENT_URI.buildUpon()
        .appendPath(LyricCrypto.hex(LyricCrypto.hash("SHA-256", "$platform:$id"))).build()

    companion object {
        const val TAG = "BridgeAmllCache"
        val CONTENT_URI: Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.amllcache")
    }
}
