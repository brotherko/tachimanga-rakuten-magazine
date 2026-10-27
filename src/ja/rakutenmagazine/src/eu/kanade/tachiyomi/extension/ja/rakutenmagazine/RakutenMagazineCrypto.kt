package eu.kanade.tachiyomi.extension.ja.rakutenmagazine

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object RakutenMagazineCrypto {

    private val SALTED_PREFIX = "Salted__".toByteArray(Charsets.US_ASCII)

    /**
     * Checks whether [data] begins with the OpenSSL `Salted__` header.
     */
    fun isSaltedOpenSsl(data: ByteArray): Boolean {
        if (data.size < 16) return false
        for (i in SALTED_PREFIX.indices) {
            if (data[i] != SALTED_PREFIX[i]) return false
        }
        return true
    }

    /**
     * Decrypts an OpenSSL `Salted__` AES-256-CBC payload using MD5 `EVP_BytesToKey`
     * (matching CryptoJS.AES.decrypt used by Rakuten Magazine's Aquafadas WebReader).
     */
    fun decryptOpenSslAes256Cbc(data: ByteArray, passphrase: ByteArray): ByteArray {
        require(isSaltedOpenSsl(data)) { "Payload does not start with OpenSSL Salted__ header" }
        val salt = data.copyOfRange(8, 16)
        val ciphertext = data.copyOfRange(16, data.size)

        val (keyBytes, ivBytes) = evpBytesToKeyMd5(passphrase, salt, keyLen = 32, ivLen = 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ivBytes),
        )
        return cipher.doFinal(ciphertext)
    }

    /**
     * OpenSSL `EVP_BytesToKey` key derivation using MD5 (1 iteration).
     */
    fun evpBytesToKeyMd5(
        password: ByteArray,
        salt: ByteArray,
        keyLen: Int,
        ivLen: Int,
    ): Pair<ByteArray, ByteArray> {
        val totalLen = keyLen + ivLen
        val derived = ByteArray(totalLen)
        val md5 = MessageDigest.getInstance("MD5")
        var generated = 0
        var prev = ByteArray(0)

        while (generated < totalLen) {
            md5.reset()
            if (prev.isNotEmpty()) {
                md5.update(prev)
            }
            md5.update(password)
            md5.update(salt)
            prev = md5.digest()

            val toCopy = minOf(prev.size, totalLen - generated)
            System.arraycopy(prev, 0, derived, generated, toCopy)
            generated += toCopy
        }

        val key = derived.copyOfRange(0, keyLen)
        val iv = derived.copyOfRange(keyLen, totalLen)
        return key to iv
    }

    /**
     * Extracts the largest embedded DCTDecode JPEG stream (`\xFF\xD8...\xFF\xD9`)
     * from a single-page Aquafadas PDF (`AVED0_A0_L0_Pxx.pdf`).
     *
     * In Rakuten Magazine's `complete` mode, only the 192-byte PDF trailer at the very end
     * of the file is AES-encrypted; the embedded high-resolution page JPEG (`~2176x3072`)
     * is stored unencrypted inside `<</Subtype/Image ...>>stream\r\n...\nendstream`.
     */
    fun extractJpegFromAquafadasPdf(pdfBytes: ByteArray): ByteArray? {
        val streamMarker = "stream".toByteArray(Charsets.US_ASCII)
        val endStreamMarker = "endstream".toByteArray(Charsets.US_ASCII)

        var bestStart = -1
        var bestLen = 0

        var searchPos = 0
        while (searchPos < pdfBytes.size - 16) {
            val streamIdx = indexOfBytes(pdfBytes, streamMarker, searchPos)
            if (streamIdx < 0) break

            var dataStart = streamIdx + streamMarker.size
            if (dataStart < pdfBytes.size && pdfBytes[dataStart] == '\r'.code.toByte()) {
                dataStart++
            }
            if (dataStart < pdfBytes.size && pdfBytes[dataStart] == '\n'.code.toByte()) {
                dataStart++
            }

            // Check for JPEG SOI marker (FF D8 FF)
            if (dataStart + 3 < pdfBytes.size &&
                pdfBytes[dataStart] == 0xFF.toByte() &&
                pdfBytes[dataStart + 1] == 0xD8.toByte() &&
                pdfBytes[dataStart + 2] == 0xFF.toByte()
            ) {
                val endIdx = indexOfBytes(pdfBytes, endStreamMarker, dataStart)
                if (endIdx > dataStart) {
                    var dataEnd = endIdx
                    if (dataEnd > dataStart && pdfBytes[dataEnd - 1] == '\n'.code.toByte()) {
                        dataEnd--
                    }
                    if (dataEnd > dataStart && pdfBytes[dataEnd - 1] == '\r'.code.toByte()) {
                        dataEnd--
                    }
                    val len = dataEnd - dataStart
                    if (len > bestLen) {
                        bestStart = dataStart
                        bestLen = len
                    }
                    searchPos = endIdx + endStreamMarker.size
                    continue
                }
            }

            searchPos = dataStart
        }

        if (bestStart >= 0 && bestLen > 0) {
            return pdfBytes.copyOfRange(bestStart, bestStart + bestLen)
        }
        return null
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, fromIndex: Int): Int {
        if (needle.isEmpty() || haystack.size - fromIndex < needle.size) return -1
        val first = needle[0]
        val max = haystack.size - needle.size
        var i = fromIndex.coerceAtLeast(0)
        while (i <= max) {
            if (haystack[i] == first) {
                var j = 1
                while (j < needle.size && haystack[i + j] == needle[j]) {
                    j++
                }
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }
}
