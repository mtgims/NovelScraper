package com.novelscraper.app.sync

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** What was typed is not a sync code (a typo, most likely). */
class SyncCodeException(message: String) : IllegalArgumentException(message)

/**
 * The sync code: sixteen random bytes that pair a library's devices. Whoever
 * holds it can sync with them; nobody else can, because everything is derived
 * from it on the device ([PairKeys]) and the code itself never goes over the
 * network.
 *
 * Written as 30 characters of Crockford's base32 in groups of five: 26 for the
 * bytes and 4 of checksum, so a mistyped code is refused rather than quietly
 * pairing with nothing. Reading forgives case, spaces, dashes, and the letters
 * most often mistaken for digits (O for 0, I and L for 1).
 */
class SyncCode private constructor(internal val secret: ByteArray) {

    /** For people: `ABCDE-FGHIJ-KLMNO-PQRST-UVWXY-Z1234`. */
    override fun toString(): String = (encode(secret) + check(secret)).chunked(5).joinToString("-")

    override fun equals(other: Any?) = other is SyncCode && other.secret.contentEquals(secret)
    override fun hashCode() = secret.contentHashCode()

    companion object {
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val BYTES = 16
        private const val BODY = 26
        private const val CHECK = 4

        fun generate(): SyncCode = SyncCode(ByteArray(BYTES).also(SecureRandom()::nextBytes))

        /** Throws [SyncCodeException] for anything that is not a code. */
        fun parse(text: String): SyncCode {
            val clean = text.uppercase().filter { it.isLetterOrDigit() }
                .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
            if (clean.length != BODY + CHECK) throw SyncCodeException("A sync code has 30 letters and digits.")
            if (clean.any { it !in ALPHABET }) throw SyncCodeException("That isn't a sync code.")
            val secret = decode(clean.take(BODY)) ?: throw SyncCodeException("That isn't a sync code.")
            if (check(secret) != clean.drop(BODY)) throw SyncCodeException("That code has a typo in it.")
            return SyncCode(secret)
        }

        private fun encode(bytes: ByteArray): String {
            val out = StringBuilder()
            var buffer = 0
            var bits = 0
            for (b in bytes) {
                buffer = (buffer shl 8) or (b.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                    bits -= 5
                }
            }
            if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
            return out.toString()
        }

        private fun decode(text: String): ByteArray? {
            val out = ArrayList<Byte>()
            var buffer = 0
            var bits = 0
            for (c in text) {
                buffer = (buffer shl 5) or ALPHABET.indexOf(c)
                bits += 5
                if (bits >= 8) {
                    out.add(((buffer shr (bits - 8)) and 0xff).toByte())
                    bits -= 8
                }
            }
            // The final character's spare bits must be zero, as encode leaves them.
            if (out.size != BYTES || (buffer and ((1 shl bits) - 1)) != 0) return null
            return out.toByteArray()
        }

        private fun check(secret: ByteArray): String {
            val h = MessageDigest.getInstance("SHA-256").digest(secret)
            return encode(h).take(CHECK)
        }
    }
}

/**
 * What a sync code becomes on the device, by HKDF-SHA256 (RFC 5869), each from
 * its own label so that knowing one says nothing of the others:
 *
 * - [tagKey]: makes the tag a device broadcasts on the Wi-Fi, which changes
 *   every few minutes, so the broadcasts can't be used to follow a device.
 * - [proofKey]: each side of a connection proves with it that it holds the code.
 * - [key]: encrypts every file the devices exchange.
 */
internal class PairKeys(code: SyncCode) {
    val tagKey: ByteArray
    val proofKey: ByteArray
    val key: SecretKey

    init {
        val prk = hmac("novelscraper sync code v1".toByteArray(), code.secret)
        tagKey = expand(prk, "lan tag", 32)
        proofKey = expand(prk, "lan proof", 32)
        key = SecretKeySpec(expand(prk, "key", 32), "AES")
    }

    /** The broadcast tag for a time window. */
    fun tag(window: Long): String = hmac(tagKey, "tag $window".toByteArray()).copyOf(12).hex()

    /** Proof that whoever made it holds the code: for [nonce], from [device]. */
    fun proof(role: String, nonce: String, device: String): String =
        hmac(proofKey, "$role|$nonce|$device".toByteArray()).hex()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        /** HKDF-Expand for up to 32 bytes: one block is enough. */
        private fun expand(prk: ByteArray, info: String, length: Int): ByteArray =
            hmac(prk, info.toByteArray() + byteArrayOf(1)).copyOf(length)
    }
}
