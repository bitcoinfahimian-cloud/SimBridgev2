package com.simbridge.app.adb

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Generates and persists:
 * - RSA-2048 client keypair & self-signed X.509 v3 certificate (for TLS 1.3)
 * - Android ADB 524-byte public key wire format (for PeerInfo)
 * - Pinned SHA-256 fingerprint of local adbd's server certificate (TOFU pinning, Fix #6)
 */
object AdbKeyStore {

    private const val KEY_FILE = "adb_key.pk8"
    private const val PUB_FILE = "adb_pub.der"
    private const val CERT_FILE = "adb_cert.der"
    private const val SERVER_FP_FILE = "adb_server_sha256.hex"

    @Synchronized
    fun getOrCreateIdentity(context: Context): Pair<PrivateKey, X509Certificate> {
        val dir = context.noBackupFilesDir
        val keyFile = File(dir, KEY_FILE)
        val pubFile = File(dir, PUB_FILE)
        val certFile = File(dir, CERT_FILE)

        if (keyFile.exists() && certFile.exists()) {
            val loaded = runCatching {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                priv to cert
            }.getOrNull()
            if (loaded != null) return loaded
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp: KeyPair = kpg.generateKeyPair()
        val certDer = buildSelfSignedX509Der(kp)
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

        keyFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)
        certFile.writeBytes(certDer)
        return kp.private to cert
    }

    fun certSha256Hex(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun savePinnedServerCert(context: Context, cert: X509Certificate) {
        val f = File(context.noBackupFilesDir, SERVER_FP_FILE)
        f.writeText(certSha256Hex(cert), Charsets.UTF_8)
    }

    fun getPinnedServerCertHex(context: Context): String? {
        val f = File(context.noBackupFilesDir, SERVER_FP_FILE)
        return if (f.exists()) f.readText(Charsets.UTF_8).trim().takeIf { it.length == 64 } else null
    }

    fun getAdbPublicKeyString(context: Context, userLabel: String = "SimBridge"): String {
        val (_, cert) = getOrCreateIdentity(context)
        val pub = cert.publicKey as RSAPublicKey
        val adbStruct = encodeAndroidAdbRsaPublicKey(pub)
        val b64 = Base64.encodeToString(adbStruct, Base64.NO_WRAP)
        return "$b64 $userLabel\u0000"
    }

    private fun encodeAndroidAdbRsaPublicKey(pub: RSAPublicKey): ByteArray {
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n = pub.modulus
        val r = BigInteger.ONE.shiftLeft(2048)
        val rr = r.multiply(r).mod(n)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).mod(r32).toLong().toInt()

        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(64)
        buf.putInt(n0inv)

        var tmpN = n
        for (i in 0 until 64) {
            buf.putInt(tmpN.and(BigInteger.valueOf(0xFFFFFFFFL)).toInt())
            tmpN = tmpN.shiftRight(32)
        }
        var tmpRR = rr
        for (i in 0 until 64) {
            buf.putInt(tmpRR.and(BigInteger.valueOf(0xFFFFFFFFL)).toInt())
            tmpRR = tmpRR.shiftRight(32)
        }
        buf.putInt(pub.publicExponent.toInt())
        return buf.array()
    }

    private fun buildSelfSignedX509Der(kp: KeyPair): ByteArray {
        val version = derExplicit(0, derInteger(BigInteger.valueOf(2)))
        val serial = derInteger(BigInteger.valueOf(System.currentTimeMillis().coerceAtLeast(1L)))
        val sigAlg = derSequence(
            derOid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)),
            byteArrayOf(0x05, 0x00)
        )
        val cnAttr = derSequence(
            derOid(byteArrayOf(0x55, 0x04, 0x03)),
            derUtf8String("SimBridge")
        )
        val name = derSequence(derSet(cnAttr))

        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val notBefore = Date(System.currentTimeMillis() - 86_400_000L)
        val notAfter = Date(System.currentTimeMillis() + 10L * 365L * 86_400_000L)
        val validity = derSequence(
            derUtcTime(fmt.format(notBefore)),
            derUtcTime(fmt.format(notAfter))
        )
        val spki = kp.public.encoded

        val tbsCert = derSequence(version, serial, sigAlg, name, validity, name, spki)
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(kp.private)
            update(tbsCert)
        }.sign()

        return derSequence(tbsCert, sigAlg, derBitString(sig))
    }

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(derLength(content.size))
        out.write(content)
        return out.toByteArray()
    }

    private fun derSequence(vararg items: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        items.forEach { body.write(it) }
        return derTag(0x30, body.toByteArray())
    }

    private fun derSet(vararg items: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        items.forEach { body.write(it) }
        return derTag(0x31, body.toByteArray())
    }

    private fun derExplicit(tagNum: Int, content: ByteArray): ByteArray = derTag(0xA0 or tagNum, content)
    private fun derInteger(v: BigInteger): ByteArray = derTag(0x02, v.toByteArray())
    private fun derOid(encodedBody: ByteArray): ByteArray = derTag(0x06, encodedBody)
    private fun derUtf8String(s: String): ByteArray = derTag(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun derUtcTime(s: String): ByteArray = derTag(0x17, s.toByteArray(Charsets.US_ASCII))
    private fun derBitString(bytes: ByteArray): ByteArray {
        val content = ByteArray(bytes.size + 1)
        content[0] = 0
        System.arraycopy(bytes, 0, content, 1, bytes.size)
        return derTag(0x03, content)
    }
}
