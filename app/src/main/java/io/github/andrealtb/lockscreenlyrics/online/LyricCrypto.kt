// SPDX-License-Identifier: AGPL-3.0-only
// QRC algorithm: SPlayer-Next, originally ported from LDDC/core/decryptor/tripledes.py.
package io.github.andrealtb.lockscreenlyrics.online

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object LyricCrypto {
    internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    internal fun hash(algorithm: String, value: String) = MessageDigest.getInstance(algorithm).digest(value.toByteArray())

    /** Match SPlayer's eapi AES-ECB request envelope, including the path-bound digest. */
    internal fun netease(path: String, json: String): String {
        val digest = hex(hash("MD5", "nobody${path}use${json}md5forencrypt"))
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
        return hex(cipher.doFinal("$path-36cd479b6b5-$json-36cd479b6b5-$digest".toByteArray())).uppercase()
    }

    @JvmStatic fun qqSign(payload: String): String {
        val bytes = hash("SHA-1", payload)
        val hash = hex(bytes)
        val prefix = intArrayOf(23,14,6,36,16,7,19).map { hash[it] }.joinToString("")
        val suffix = intArrayOf(16,1,32,12,19,27,8,5).map { hash[it] }.joinToString("")
        val mask = intArrayOf(89,39,179,150,218,82,58,252,177,52,186,123,120,64,242,133,143,161,121,179)
        val middle = Base64.getEncoder().encodeToString(ByteArray(20) { (bytes[it].toInt() xor mask[it]).toByte() })
            .replace(Regex("[/+=]"), "")
        return "zzc$prefix$middle$suffix".lowercase(java.util.Locale.ROOT)
    }

    /** Bound inflated and HTTP payloads before creating lyric strings in SystemUI. */
    internal fun readLimited(input: InputStream): ByteArray = input.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(out.size() + count <= 2_000_000) { "Lyric response exceeds limit" }
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    }

    private fun inflate(data: ByteArray, raw: Boolean = false): String {
        val inflater = Inflater(raw)
        return try { String(readLimited(InflaterInputStream(ByteArrayInputStream(data), inflater)), Charsets.UTF_8) }
        finally { inflater.end() }
    }

    @JvmStatic fun krc(base64: String): String {
        val bytes = Base64.getDecoder().decode(base64)
        require(bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "krc1")
        val key = intArrayOf(0x40,0x47,0x61,0x77,0x5e,0x32,0x74,0x47,0x51,0x36,0x31,0x2d,0xce,0xd2,0x6e,0x69)
        return inflate(ByteArray(bytes.size - 4) { (bytes[it + 4].toInt() xor key[it % 16]).toByte() })
    }

    @JvmStatic fun qrc(hex: String): String {
        require(hex.length % 16 == 0 && hex.isNotBlank())
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val plain = QrcCipher.decrypt(bytes)
        runCatching { inflate(plain) }.getOrNull()?.let { return it }
        runCatching { inflate(plain, true) }.getOrNull()?.let { return it }
        runCatching { String(readLimited(GZIPInputStream(ByteArrayInputStream(plain))), Charsets.UTF_8) }.getOrNull()?.let { return it }
        val text = String(plain, Charsets.UTF_8).trimEnd('\u0000')
        require(text.contains('[') || text.contains('<')) { "Invalid QRC data" }
        return text
    }
}

/** QQ's altered S-boxes, byte order and key permutation are part of the wire format. */
internal object QrcCipher {
    private val sbox = arrayOf(
        intArrayOf(14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7,0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8,4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0,15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13),
        intArrayOf(15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10,3,13,4,7,15,2,8,15,12,0,1,10,6,9,11,5,0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15,13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9),
        intArrayOf(10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8,13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1,13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7,1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12),
        intArrayOf(7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15,13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9,10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4,3,15,0,6,10,10,13,8,9,4,5,11,12,7,2,14),
        intArrayOf(2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9,14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6,4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14,11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3),
        intArrayOf(12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11,10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8,9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6,4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13),
        intArrayOf(4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1,13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6,1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2,6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12),
        intArrayOf(13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7,1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2,7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8,2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11)
    )
    private val compression = intArrayOf(13,16,10,23,0,4,2,27,14,5,20,9,22,18,11,3,25,7,15,6,26,19,12,1,40,51,30,36,46,54,29,39,50,44,32,47,43,48,38,55,33,52,45,41,49,35,28,31)
    private val permC = intArrayOf(56,48,40,32,24,16,8,0,57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35)
    private val permD = intArrayOf(62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,60,52,44,36,28,20,12,4,27,19,11,3)
    private val permutation = intArrayOf(15,6,19,20,28,11,27,16,0,14,22,25,4,17,30,9,1,7,23,13,31,26,2,8,18,12,29,5,21,10,3,24)
    private fun bit(a: ByteArray, b: Int): Int = (a[b / 32 * 4 + 3 - b % 32 / 8].toInt() ushr (7 - b % 8)) and 1
    private fun schedule(key: ByteArray, decrypt: Boolean): LongArray {
        var c = 0
        var d = 0
        for (i in 0..27) { c = c or (bit(key, permC[i]) shl (31 - i)); d = d or (bit(key, permD[i]) shl (31 - i)) }
        val result = LongArray(16)
        for (i in 0..15) {
            val shift = if (i in listOf(0,1,8,15)) 1 else 2
            c = ((c shl shift) or (c ushr (28 - shift))) and -16
            d = ((d shl shift) or (d ushr (28 - shift))) and -16
            var subkey = 0L
            for (j in 0..47) {
                val value = if (j < 24) (c ushr (31 - compression[j])) and 1
                    else (d ushr (31 - (compression[j] - 27))) and 1
                subkey = (subkey shl 1) or value.toLong()
            }
            result[if (decrypt) 15 - i else i] = subkey
        }
        return result
    }
    private fun f(state: Int, key: Long): Int {
        var expanded = 0L
        for (group in 0..7) for (j in 0..5) {
            val bit = (group * 4 + j + 31) % 32
            expanded = (expanded shl 1) or ((state ushr (31 - bit)) and 1).toLong()
        }
        expanded = expanded xor key
        var substituted = 0
        for (i in 0..7) {
            val a = ((expanded ushr (42 - i * 6)) and 63).toInt()
            val index = (a and 32) or ((a and 31) ushr 1) or ((a and 1) shl 4)
            substituted = (substituted shl 4) or sbox[i][index]
        }
        var out = 0
        for (p in permutation) out = (out shl 1) or ((substituted ushr (31 - p)) and 1)
        return out
    }
    private fun crypt(data: ByteArray, key: LongArray): ByteArray {
        var left = 0
        var right = 0
        for (group in 0..3) for (j in 0..7) {
            left = (left shl 1) or bit(data, 57 + group * 2 - j * 8)
            right = (right shl 1) or bit(data, 56 + group * 2 - j * 8)
        }
        for (i in 0..14) { val old = right; right = f(right, key[i]) xor left; left = old }
        left = f(right, key[15]) xor left
        val out = ByteArray(8)
        for (i in 0..7) {
            var value = 0
            for (j in 0..3) {
                val b = 7 - i + j * 8
                value = (value shl 1) or ((right ushr (31 - b)) and 1)
                value = (value shl 1) or ((left ushr (31 - b)) and 1)
            }
            out[if (i < 4) 3 - i else 11 - i] = value.toByte()
        }
        return out
    }
    fun decrypt(data: ByteArray): ByteArray {
        val key = "!@#)(*\u0024%123ZXC!@!@#)(NHL".toByteArray()
        val keys = listOf(schedule(key.copyOfRange(16, 24), true), schedule(key.copyOfRange(8, 16), false), schedule(key.copyOfRange(0, 8), true))
        val output = ByteArray(data.size)
        for (offset in data.indices step 8) {
            var block = data.copyOfRange(offset, offset + 8)
            for (part in keys) block = crypt(block, part)
            block.copyInto(output, offset)
        }
        return output
    }
}
