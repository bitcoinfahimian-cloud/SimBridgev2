package com.simbridge.app.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fix #2: Exact BoringSSL SPAKE2 over Ed25519 (SPAKE2_role_alice) implementation.
 * - Uses official BoringSSL kM and kN compressed Ed25519 generator points.
 * - Feeds the full 64-byte SHA-512(password) hash into the final transcript SHA-512
 *   (matching BoringSSL crypto/curve25519/spake25519.cc: ctx->password_hash[64]).
 */
class Spake25519(
    private val myName: ByteArray = "adb pair client".toByteArray(Charsets.UTF_8),
    private val theirName: ByteArray = "adb pair server".toByteArray(Charsets.UTF_8)
) {
    companion object {
        private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        private val L: BigInteger = BigInteger.ONE.shiftLeft(252)
            .add(BigInteger("27742317777372353535851937790883648493"))
        private val D: BigInteger = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        private val I_SQRT: BigInteger = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        private val EXP_P5_8: BigInteger = P.subtract(BigInteger.valueOf(5)).shiftRight(3)

        // Official BoringSSL kM and kN points (verified order-L Ed25519 points)
        private val M_BYTES = hexToBytes("d048032c6ea0b6d697ddc2e86bda85a33adac920f1bf18e1b0c6d166a5cecdaf")
        private val N_BYTES = hexToBytes("d3bfb518f44f3430f29d0c92af503865a1ed3281dc69b35dd868ba85f886c4ab")

        private val M_PT: EdPoint by lazy { decompress(M_BYTES) }
        private val N_PT: EdPoint by lazy { decompress(N_BYTES) }
        private val B_PT: EdPoint by lazy {
            val by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
            val bx = recoverX(by, 0)
            EdPoint(bx, by, BigInteger.ONE, bx.multiply(by).mod(P))
        }

        private data class EdPoint(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

        private fun recoverX(y: BigInteger, sign: Int): BigInteger {
            val y2 = y.multiply(y).mod(P)
            val u = y2.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
            val v3 = v.multiply(v).mod(P).multiply(v).mod(P)
            val v7 = v3.multiply(v3).mod(P).multiply(v).mod(P)
            var x = u.multiply(v3).mod(P)
                .multiply(u.multiply(v7).mod(P).modPow(EXP_P5_8, P)).mod(P)
            if (v.multiply(x).mod(P).multiply(x).mod(P) != u) {
                x = x.multiply(I_SQRT).mod(P)
                require(v.multiply(x).mod(P).multiply(x).mod(P) == u) { "Invalid Ed25519 point" }
            }
            if ((if (x.testBit(0)) 1 else 0) != sign) {
                x = P.subtract(x).mod(P)
            }
            return x
        }

        private fun decompress(bytes: ByteArray): EdPoint {
            require(bytes.size == 32) { "Ed25519 point must be 32 bytes" }
            val sign = (bytes[31].toInt() ushr 7) and 1
            val copy = bytes.copyOf()
            copy[31] = (copy[31].toInt() and 0x7F).toByte()
            val y = leBytesToBigInt(copy)
            require(y < P) { "Ed25519 y >= P" }
            val x = recoverX(y, sign)
            return EdPoint(x, y, BigInteger.ONE, x.multiply(y).mod(P))
        }

        private fun compress(pt: EdPoint): ByteArray {
            val zi = pt.z.modInverse(P)
            val x = pt.x.multiply(zi).mod(P)
            val y = pt.y.multiply(zi).mod(P)
            val out = bigIntToLeBytes(y, 32)
            if (x.testBit(0)) {
                out[31] = (out[31].toInt() or 0x80).toByte()
            }
            return out
        }

        private fun add(p1: EdPoint, p2: EdPoint): EdPoint {
            val two = BigInteger.valueOf(2)
            val a = p1.y.subtract(p1.x).multiply(p2.y.subtract(p2.x)).mod(P)
            val b = p1.y.add(p1.x).multiply(p2.y.add(p2.x)).mod(P)
            val c = two.multiply(p1.t).mod(P).multiply(p2.t).mod(P).multiply(D).mod(P)
            val d = two.multiply(p1.z).mod(P).multiply(p2.z).mod(P)
            val e = b.subtract(a).mod(P)
            val f = d.subtract(c).mod(P)
            val g = d.add(c).mod(P)
            val h = b.add(a).mod(P)
            return EdPoint(
                e.multiply(f).mod(P),
                g.multiply(h).mod(P),
                f.multiply(g).mod(P),
                e.multiply(h).mod(P)
            )
        }

        private fun neg(pt: EdPoint): EdPoint =
            EdPoint(P.subtract(pt.x).mod(P), pt.y, pt.z, P.subtract(pt.t).mod(P))

        private fun scalarMult(pt: EdPoint, scalar: BigInteger): EdPoint {
            var res = EdPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
            var addend = pt
            var k = scalar
            while (k.signum() > 0) {
                if (k.testBit(0)) res = add(res, addend)
                addend = add(addend, addend)
                k = k.shiftRight(1)
            }
            return res
        }

        private fun leBytesToBigInt(le: ByteArray): BigInteger {
            val be = ByteArray(le.size + 1)
            for (i in le.indices) be[le.size - i] = le[i]
            return BigInteger(be)
        }

        private fun bigIntToLeBytes(v: BigInteger, len: Int): ByteArray {
            val out = ByteArray(len)
            val be = v.toByteArray()
            var idx = 0
            for (i in be.size - 1 downTo 0) {
                if (idx >= len) break
                out[idx++] = be[i]
            }
            return out
        }

        private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        fun deriveAdbAes128Key(spakeKey: ByteArray): ByteArray {
            val info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)
            val macExtract = Mac.getInstance("HmacSHA256")
            macExtract.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = macExtract.doFinal(spakeKey)

            val macExpand = Mac.getInstance("HmacSHA256")
            macExpand.init(SecretKeySpec(prk, "HmacSHA256"))
            macExpand.update(info)
            macExpand.update(1.toByte())
            return macExpand.doFinal().copyOf(16)
        }
    }

    private lateinit var xScalar: BigInteger
    private lateinit var wScalar: BigInteger
    private lateinit var passwordHash64: ByteArray
    private lateinit var myMsg: ByteArray

    fun generateClientMessage(password: ByteArray): ByteArray {
        // BoringSSL computes 64-byte SHA-512(password), keeps all 64 bytes for transcript,
        // and reduces mod L to obtain the scalar w.
        passwordHash64 = MessageDigest.getInstance("SHA-512").digest(password)
        wScalar = leBytesToBigInt(passwordHash64).mod(L)

        val xBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        xBytes[0] = (xBytes[0].toInt() and 248).toByte()
        xBytes[31] = ((xBytes[31].toInt() and 63) or 64).toByte()
        xScalar = leBytesToBigInt(xBytes)

        val xB = scalarMult(B_PT, xScalar)
        val wM = scalarMult(M_PT, wScalar)
        myMsg = compress(add(xB, wM))
        return myMsg
    }

    fun processServerMessage(serverMsg: ByteArray): ByteArray {
        val theirPt = decompress(serverMsg)
        val wN = scalarMult(N_PT, wScalar)
        val kPt = scalarMult(add(theirPt, neg(wN)), xScalar)
        val kBytes = compress(kPt)

        val md = MessageDigest.getInstance("SHA-512")
        updateLenPrefixed(md, myName)
        updateLenPrefixed(md, theirName)
        updateLenPrefixed(md, myMsg)
        updateLenPrefixed(md, serverMsg)
        updateLenPrefixed(md, kBytes)
        // Fix #2: Full 64-byte passwordHash (not 32-byte reduced scalar)
        updateLenPrefixed(md, passwordHash64)
        return md.digest()
    }

    private fun updateLenPrefixed(md: MessageDigest, data: ByteArray) {
        val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(data.size.toLong()).array()
        md.update(lenBuf)
        md.update(data)
    }
}
