package com.simbridge.app.bridge

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provides authenticated encryption (AES-256-GCM) for BLE control messages
 * and LAN Call Audio frames.
 * Wire format: [12-byte random IV][ciphertext + 16-byte GCM authentication tag].
 */
object BleCrypto {
    private const val PREFS_NAME = "simbridge_prefs"
    private const val KEY_PREF = "ble_shared_key_b64"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    fun getOrCreateSharedKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_PREF, null)
        if (!existing.isNullOrBlank()) {
            val decoded = runCatching { Base64.decode(existing, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
            if (decoded != null && decoded.size == 32) return decoded
        }
        return rotateSharedKey(context)
    }

    fun rotateSharedKey(context: Context): ByteArray {
        val key = ByteArray(32).also { random.nextBytes(it) }
        val encoded = Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREF, encoded)
            .apply()
        return key
    }

    fun getSharedKeyBase64Url(context: Context): String {
        val key = getOrCreateSharedKey(context)
        return Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun randomNonceBase64(bytes: Int = 16): String {
        val buf = ByteArray(bytes).also { random.nextBytes(it) }
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray, offset: Int = 0, length: Int = plaintext.size): ByteArray {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext, offset, length)
        return ByteArray(IV_LEN + ct.size).apply {
            System.arraycopy(iv, 0, this, 0, IV_LEN)
            System.arraycopy(ct, 0, this, IV_LEN, ct.size)
        }
    }

    fun decrypt(key: ByteArray, packet: ByteArray): ByteArray? {
        if (packet.size <= IV_LEN + 16) return null
        return runCatching {
            val iv = packet.copyOfRange(0, IV_LEN)
            val ct = packet.copyOfRange(IV_LEN, packet.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ct)
        }.getOrNull()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869), single output block (<= 32 bytes). */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmacSha256(salt, ikm)
        return hmacSha256(prk, info + byteArrayOf(0x01))
    }

    /** Per-direction UDP audio subkeys: (client->server, server->client). */
    fun deriveUdpKeys(master: ByteArray): Pair<ByteArray, ByteArray> {
        val salt = "simbridge-udp-v4.2".toByteArray(Charsets.UTF_8)
        val c2s = hkdfSha256(master, salt, "c2s".toByteArray(Charsets.UTF_8))
        val s2c = hkdfSha256(master, salt, "s2c".toByteArray(Charsets.UTF_8))
        return c2s to s2c
    }
}
