package com.morealm.app.domain.sync

import com.morealm.app.core.log.AppLog
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based AES-GCM encryption for backup zip payloads.
 *
 * Layout of an encrypted blob (binary):
 * ```
 *   magic   = "MoREncBk" (8 bytes ASCII)
 *   version = 0x01       (1 byte)
 *   salt    = 16 bytes   (random per backup)
 *   iv      = 12 bytes   (random per backup, GCM-recommended length)
 *   ciphertext + 16-byte GCM auth tag (the rest)
 * ```
 *
 * Why this rather than Legado's `BackupAES` (Base64-of-AES-CBC over a
 * derived key):
 *  - GCM provides authentication for free — a tampered backup fails
 *    decryption explicitly instead of producing garbage that the JSON
 *    parser then mis-interprets.
 *  - PBKDF2-HmacSHA256 with 60_000 iterations defends against brute
 *    force on a stolen .zip from a cloud drive.
 *  - Random salt + iv means re-uploading the same backup with the same
 *    password produces a different ciphertext, denying a passive
 *    observer the ability to detect "no change".
 *
 * Backwards compat: when the user disables encryption (empty password),
 * we fall back to plain JSON-in-zip via [BackupManager]; restore detects
 * the [MAGIC] prefix and only invokes decryption when present, so old
 * unencrypted zips still restore as before.
 */
object BackupCrypto {

    /** ASCII magic bytes — kept short to avoid bloating tiny backups. */
    private val MAGIC = "MoREncBk".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 0x01
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 60_000
    private const val AES_KEY_BITS = 256

    /** Header length: magic + version + salt + iv. */
    private val HEADER_LEN = MAGIC.size + 1 + SALT_LEN + IV_LEN

    private val random by lazy { SecureRandom() }

    /**
     * Encrypt [plaintext] with [password]. The returned blob includes the
     * magic header, salt, iv, ciphertext, and GCM auth tag — pass the
     * whole thing into [decrypt] to round-trip.
     *
     * @throws IllegalArgumentException if password is blank.
     */
    fun encrypt(plaintext: ByteArray, password: String): ByteArray {
        require(password.isNotEmpty()) { "Backup password cannot be empty" }
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)

        return MAGIC + byteArrayOf(VERSION) + salt + iv + ciphertext
    }

    /**
     * Decrypt a blob previously returned by [encrypt]. Returns null on
     * any failure (wrong password, tampered ciphertext, malformed
     * header, …) so the restore code can fall through to a clean
     * "wrong password" error message instead of crashing.
     */
    fun decrypt(blob: ByteArray, password: String): ByteArray? {
        if (!isEncrypted(blob)) {
            AppLog.warn("BackupCrypto", "Blob has no MoREncBk magic — not encrypted")
            return null
        }
        if (blob.size < HEADER_LEN + 1) {
            AppLog.warn("BackupCrypto", "Blob too short (${blob.size} < $HEADER_LEN)")
            return null
        }
        val version = blob[MAGIC.size]
        if (version != VERSION) {
            AppLog.warn("BackupCrypto", "Unsupported backup version 0x${version.toString(16)}")
            return null
        }
        val salt = blob.copyOfRange(MAGIC.size + 1, MAGIC.size + 1 + SALT_LEN)
        val iv = blob.copyOfRange(MAGIC.size + 1 + SALT_LEN, HEADER_LEN)
        val ciphertext = blob.copyOfRange(HEADER_LEN, blob.size)

        return try {
            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            // GCM auth failure surfaces as AEADBadTagException — the
            // password is wrong, the file is tampered, or both.
            AppLog.warn("BackupCrypto", "Decryption failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Cheap header check used by the restore path to branch encrypted vs plain. */
    fun isEncrypted(blob: ByteArray): Boolean {
        if (blob.size < MAGIC.size) return false
        for (i in MAGIC.indices) {
            if (blob[i] != MAGIC[i]) return false
        }
        return true
    }

    /** Key derivation: PBKDF2-HmacSHA256, 60k iterations, 256-bit key. */
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    // Unused for the binary blob path, but exposed so future flows
    // (export single setting value, etc.) can reuse the same primitives.
    @Suppress("unused")
    fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    @Suppress("unused")
    fun fromBase64(s: String): ByteArray = Base64.getDecoder().decode(s)
}
