package com.novelscraper.app.sync

import com.novelscraper.app.data.SyncChange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The folder is encrypted and no passphrase was given on this device. */
class SyncPassphraseNeededException : Exception("This sync folder is locked with a passphrase.")

/** The passphrase given here does not open the folder. */
class SyncWrongPassphraseException : Exception("The passphrase does not match this sync folder.")

/** A passphrase was given here, but the folder was set up without one. */
class SyncNotEncryptedException : Exception("This sync folder is not encrypted.")

/** The folder was set up by a newer version of the app. */
class SyncNewerFormatException : Exception("This sync folder needs a newer version of the app.")

/** A batch file that cannot be read: damaged, foreign, or not ours to trust. */
internal class BadBatchException(message: String) : Exception(message)

/** A batch file, read: who wrote it, its number, the nonce its writer gave it,
 *  its records. */
internal class Batch(val device: String, val seq: Long, val nonce: String, val changes: List<SyncChange>)

private const val FORMAT = 1

/** [w]: a nonce the writer recorded before uploading (FolderSync's clone check). */
@Serializable
private data class Header(val v: Int, val device: String, val seq: Long, val w: String = "")

/**
 * novelscraper.json, the one file no device owns. The first device to reach an
 * empty folder writes it; everyone reads it before anything else. With [enc], a
 * passphrase is needed to read or write the folder.
 */
@Serializable
internal data class Manifest(val v: Int = FORMAT, val enc: Encryption? = null) {
    /** PBKDF2-HMAC-SHA256 parameters (or [CODE]: the key comes from a sync
     *  code, and [iter] and [salt] are unused), and [check]: a known text encrypted with
     *  the key, so a wrong passphrase is caught before any record is touched. */
    @Serializable
    data class Encryption(val kdf: String = KDF, val iter: Int, val salt: String, val check: String)

    companion object {
        const val KDF = "pbkdf2-sha256"
        /** The folder's key is derived from a sync code ([PairKeys]). */
        const val CODE = "sync-code"
    }
}

/**
 * Batch files: a header line and one record per line, gzipped, and with a key,
 * sealed with AES-256-GCM (a random nonce in front of each file).
 *
 * Everything here is JDK crypto, available on Android from API 26 and on every
 * desktop JVM, so no dependency is added for it.
 */
internal class SyncCodec(private val key: SecretKey?) {

    fun encode(device: String, seq: Long, nonce: String, changes: List<SyncChange>): ByteArray {
        val raw = ByteArrayOutputStream()
        GZIPOutputStream(raw).bufferedWriter().use { w ->
            w.write(json.encodeToString(Header.serializer(), Header(FORMAT, device, seq, nonce)))
            w.write("\n")
            for (c in changes) {
                w.write(json.encodeToString(SyncChange.serializer(), c))
                w.write("\n")
            }
        }
        return if (key == null) raw.toByteArray() else seal(key, raw.toByteArray())
    }

    /** Throws [BadBatchException] for anything that is not a batch this folder
     *  could have written, under [expected]'s name. */
    fun decode(expected: BatchName, bytes: ByteArray): Batch {
        val sealed = bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        // The manifest decides: an encrypted folder takes no plain files, which
        // anyone able to drop a file there could otherwise use to edit a library.
        val plain = when {
            key != null && sealed -> try { open(key, bytes) } catch (e: AEADBadTagException) {
                throw BadBatchException("not sealed with this folder's key")
            }
            key == null && !sealed -> bytes
            else -> throw BadBatchException(if (sealed) "encrypted, folder is not" else "plain, folder is encrypted")
        }
        // Bounded: a few kilobytes of gzip can unpack to gigabytes, and a shared
        // folder without a key takes files from anyone who can write to it.
        val unpacked = try {
            val gz = GZIPInputStream(ByteArrayInputStream(plain))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = gz.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_UNPACKED) {
                    throw BadBatchException("unpacks to over ${MAX_UNPACKED / (1024 * 1024)} MiB")
                }
            }
            out.toByteArray()
        } catch (e: java.io.IOException) {
            throw BadBatchException("not gzip: ${e.message}")
        }
        val lines = unpacked.decodeToString().lines()
        val header = lines.firstOrNull()
            ?.let { runCatching { json.decodeFromString(Header.serializer(), it) }.getOrNull() }
            ?: throw BadBatchException("no header")
        if (header.v > FORMAT) throw BadBatchException("format ${header.v}, newer than this app")
        if (header.device != expected.device || header.seq != expected.seq)
            throw BadBatchException("header says ${header.device}.${header.seq}")
        val changes = try {
            lines.drop(1).filter { it.isNotBlank() }.map { json.decodeFromString(SyncChange.serializer(), it) }
        } catch (e: Exception) {
            throw BadBatchException("bad record: ${e.message}")
        }
        return Batch(header.device, header.seq, header.w, changes)
    }

    companion object {
        /** A batch is a few hundred records; this is far more than one can need. */
        private const val MAX_UNPACKED = 64 * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val MAGIC = "NSE1".toByteArray()
        private const val NONCE = 12
        private const val TAG_BITS = 128
        private const val ITERATIONS = 600_000
        private val CHECK = "novelscraper".toByteArray()
        private val random = SecureRandom()

        fun manifestJson(m: Manifest): ByteArray = json.encodeToString(Manifest.serializer(), m).toByteArray()

        /** Null if the bytes are not a manifest at all. */
        fun parseManifest(bytes: ByteArray): Manifest? =
            runCatching { json.decodeFromString(Manifest.serializer(), bytes.decodeToString()) }.getOrNull()

        /** A new folder's manifest, locked with [passphrase] if one is given,
         *  and the key that goes with it. */
        fun newManifest(passphrase: CharArray?, codeKey: SecretKey? = null): Pair<Manifest, SecretKey?> {
            if (codeKey != null) {
                val enc = Manifest.Encryption(kdf = Manifest.CODE, iter = 0, salt = "", check = b64(seal(codeKey, CHECK)))
                return Manifest(enc = enc) to codeKey
            }
            if (passphrase == null || passphrase.isEmpty()) return Manifest() to null
            val salt = ByteArray(16).also(random::nextBytes)
            val key = derive(passphrase, salt, ITERATIONS)
            val enc = Manifest.Encryption(
                iter = ITERATIONS, salt = b64(salt), check = b64(seal(key, CHECK)),
            )
            return Manifest(enc = enc) to key
        }

        /**
         * The key for an existing folder's manifest, null for a plain folder.
         * Throws one of the Sync*Exceptions when this device can't use the folder
         * as it stands; [keys] spares re-deriving on every sync.
         */
        fun keyFor(m: Manifest, passphrase: CharArray?, keys: KeyCache, codeKey: SecretKey? = null): SecretKey? {
            if (m.v > FORMAT) throw SyncNewerFormatException()
            val enc = m.enc
            if (codeKey != null || enc?.kdf == Manifest.CODE) {
                // A sync code's folder: the code is the only way in, and the
                // code is how the folder was found, so a mismatch means damage.
                if (codeKey == null || enc?.kdf != Manifest.CODE) throw SyncWrongPassphraseException()
                val ok = runCatching { open(codeKey, unb64(enc.check)!!).contentEquals(CHECK) }.getOrDefault(false)
                if (!ok) throw SyncWrongPassphraseException()
                return codeKey
            }
            val given = passphrase != null && passphrase.isNotEmpty()
            if (enc == null) {
                if (given) throw SyncNotEncryptedException()
                return null
            }
            if (!given) throw SyncPassphraseNeededException()
            if (enc.kdf != Manifest.KDF || enc.iter < 1) throw SyncNewerFormatException()
            val salt = unb64(enc.salt) ?: throw SyncNewerFormatException()
            val key = keys.get(passphrase!!, salt, enc.iter)
            val ok = runCatching { open(key, unb64(enc.check)!!).contentEquals(CHECK) }.getOrDefault(false)
            if (!ok) throw SyncWrongPassphraseException()
            return key
        }

        internal fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
            val spec = PBEKeySpec(passphrase, salt, iterations, 256)
            try {
                val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                return SecretKeySpec(bytes, "AES")
            } finally {
                spec.clearPassword()
            }
        }

        private fun seal(key: SecretKey, plain: ByteArray): ByteArray {
            val nonce = ByteArray(NONCE).also(random::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            return MAGIC + nonce + c.doFinal(plain)
        }

        private fun open(key: SecretKey, sealed: ByteArray): ByteArray {
            if (sealed.size < MAGIC.size + NONCE) throw AEADBadTagException("too short")
            val nonce = sealed.copyOfRange(MAGIC.size, MAGIC.size + NONCE)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            return c.doFinal(sealed, MAGIC.size + NONCE, sealed.size - MAGIC.size - NONCE)
        }

        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String) = runCatching { Base64.getDecoder().decode(s) }.getOrNull()
    }
}

/**
 * The last key derived, by passphrase and salt. PBKDF2 at 600k rounds takes a
 * moment on a phone, and a sync runs every minute.
 */
internal class KeyCache {
    private var tag: ByteArray? = null
    private var key: SecretKey? = null

    @Synchronized
    fun get(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val t = MessageDigest.getInstance("SHA-256").run {
            update(String(passphrase).toByteArray()); update(salt); update(iterations.toString().toByteArray())
            digest()
        }
        key?.let { if (tag.contentEquals(t)) return it }
        return SyncCodec.derive(passphrase, salt, iterations).also { key = it; tag = t }
    }
}
