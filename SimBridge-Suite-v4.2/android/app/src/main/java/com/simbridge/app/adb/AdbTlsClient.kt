package com.simbridge.app.adb

import android.content.Context
import android.os.Build
import org.conscrypt.Conscrypt
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.*

/**
 * Fix #4 & #6:
 * - Zero reflection: always uses bundled Conscrypt.exportKeyingMaterial().
 * - Strict loopback-only (127.0.0.1) enforcement.
 * - TOFU certificate pinning: records adbd's server certificate SHA-256 during SPAKE2-authenticated pairing
 *   and enforces an exact match on subsequent TLS shell connections.
 */
class AdbTlsClient(private val context: Context) {

    companion object {
        private const val A_CNXN = 0x4E584E43
        private const val A_STLS = 0x534C5453
        private const val A_OPEN = 0x4E45504F
        private const val A_OKAY = 0x59414B4F
        private const val A_CLSE = 0x45534C43
        private const val A_WRTE = 0x45545257

        private const val ADB_VERSION = 0x01000001
        private const val ADB_MAXDATA = 1024 * 1024
        private const val STLS_VERSION = 0x01000000

        private const val PAIR_VERSION: Byte = 1
        private const val PAIR_TYPE_SPAKE2: Byte = 0
        private const val PAIR_TYPE_PEER_INFO: Byte = 1
        private const val PEER_INFO_SIZE = 8192

        private val conscryptProvider by lazy { Conscrypt.newProvider() }
    }

    private fun createTls13Context(
        isPairingMode: Boolean,
        onPeerCertObserved: ((X509Certificate) -> Unit)? = null
    ): SSLContext {
        val (privKey, cert) = AdbKeyStore.getOrCreateIdentity(context)
        val pinnedHex = AdbKeyStore.getPinnedServerCertHex(context)

        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(k: String?, i: Array<out java.security.Principal>?) = arrayOf("adb")
            override fun chooseClientAlias(k: Array<out String>?, i: Array<out java.security.Principal>?, s: Socket?) = "adb"
            override fun getServerAliases(k: String?, i: Array<out java.security.Principal>?) = null
            override fun chooseServerAlias(k: String?, i: Array<out java.security.Principal>?, s: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)
            override fun getPrivateKey(alias: String?): PrivateKey = privKey
        }

        // Fix #6: Pin adbd's certificate via TOFU instead of blindly trusting all certificates
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {
                throw CertificateException("Client auth not accepted")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("Empty server certificate chain")
                leaf.checkValidity()
                if (isPairingMode) {
                    // During pairing, SPAKE2 authenticates the peer via the 6-digit code; record cert for pinning
                    onPeerCertObserved?.invoke(leaf)
                } else {
                    if (pinnedHex != null) {
                        val actualHex = AdbKeyStore.certSha256Hex(leaf)
                        if (actualHex != pinnedHex) {
                            throw CertificateException("adbd server certificate mismatch (possible MITM or adbd key reset)")
                        }
                    } else {
                        // First connect if user previously paired externally: pin on first connect
                        onPeerCertObserved?.invoke(leaf)
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        // Use bundled Conscrypt on API < 33 so Conscrypt.exportKeyingMaterial works with zero reflection (Fix #4)
        // v4.2: always the bundled Conscrypt so exportKeyingMaterial is guaranteed on every API level
        val sslCtx = SSLContext.getInstance("TLSv1.3", conscryptProvider)
        sslCtx.init(arrayOf<KeyManager>(km), arrayOf<TrustManager>(tm), SecureRandom())
        return sslCtx
    }

    private fun exportKeyingMaterial(sock: SSLSocket, label: String, length: Int): ByteArray {
        val exported = Conscrypt.exportKeyingMaterial(sock, label, null, length)
        return exported ?: error("TLS keying material export returned null")
    }

    fun pair(host: String, pairingPort: Int, sixDigitCode: String) {
        require(host == "127.0.0.1") { "Wireless debugging pairing is restricted to localhost (127.0.0.1)" }

        var observedCert: X509Certificate? = null
        val sslCtx = createTls13Context(isPairingMode = true) { observedCert = it }
        val raw = Socket().apply {
            connect(InetSocketAddress(host, pairingPort), 8_000)
            soTimeout = 10_000
        }
        val ssl = (sslCtx.socketFactory.createSocket(raw, host, pairingPort, true) as SSLSocket).apply {
            enabledProtocols = arrayOf("TLSv1.3")
            soTimeout = 10_000
            startHandshake()
        }

        ssl.use { sock ->
            val exported = exportKeyingMaterial(sock, "adb-label\u0000", 64)

            val codeBytes = sixDigitCode.trim().toByteArray(Charsets.UTF_8)
            val password = ByteArray(codeBytes.size + exported.size).apply {
                System.arraycopy(codeBytes, 0, this, 0, codeBytes.size)
                System.arraycopy(exported, 0, this, codeBytes.size, exported.size)
            }

            val input = DataInputStream(sock.inputStream)
            val output = DataOutputStream(sock.outputStream)

            val spake = Spake25519()
            val mySpakeMsg = spake.generateClientMessage(password)
            writePairPacket(output, PAIR_TYPE_SPAKE2, mySpakeMsg)

            val (srvType, srvSpakeMsg) = readPairPacket(input)
            require(srvType == PAIR_TYPE_SPAKE2) { "Expected SPAKE2 packet, got $srvType" }
            val sharedSecret = spake.processServerMessage(srvSpakeMsg)
            val aesKey = Spake25519.deriveAdbAes128Key(sharedSecret)

            val peerInfoPlain = ByteArray(PEER_INFO_SIZE)
            peerInfoPlain[0] = 0 // ADB_RSA_PUB_KEY
            val pubKeyBytes = AdbKeyStore.getAdbPublicKeyString(context).toByteArray(Charsets.UTF_8)
            System.arraycopy(pubKeyBytes, 0, peerInfoPlain, 1, pubKeyBytes.size.coerceAtMost(PEER_INFO_SIZE - 1))

            val encryptedPeerInfo = aesGcmEncrypt(aesKey, 0L, peerInfoPlain)
            writePairPacket(output, PAIR_TYPE_PEER_INFO, encryptedPeerInfo)

            val (peerType, encPeerReply) = readPairPacket(input)
            require(peerType == PAIR_TYPE_PEER_INFO) { "Expected PeerInfo reply, got $peerType" }
            aesGcmDecrypt(aesKey, 0L, encPeerReply)

            // SPAKE2 + GCM authentication succeeded — pin adbd's server certificate now (Fix #6)
            observedCert?.let { AdbKeyStore.savePinnedServerCert(context, it) }
        }
    }

    fun runShellCommand(host: String, connectPort: Int, command: String): String {
        require(host == "127.0.0.1") { "ADB shell connection is restricted to localhost (127.0.0.1)" }

        val raw = Socket().apply {
            connect(InetSocketAddress(host, connectPort), 8_000)
            soTimeout = 10_000
        }
        var activeSock: Socket = raw
        try {
            var input = DataInputStream(activeSock.inputStream)
            var output = DataOutputStream(activeSock.outputStream)

            writeAdbPacket(output, A_CNXN, ADB_VERSION, ADB_MAXDATA, "host::\u0000".toByteArray(Charsets.UTF_8))

            val first = readAdbPacket(input)
            if (first.cmd == A_STLS) {
                writeAdbPacket(output, A_STLS, STLS_VERSION, 0, ByteArray(0))
                var firstSeenCert: X509Certificate? = null
                val sslCtx = createTls13Context(isPairingMode = false) { firstSeenCert = it }
                val ssl = (sslCtx.socketFactory.createSocket(raw, host, connectPort, true) as SSLSocket).apply {
                    enabledProtocols = arrayOf("TLSv1.3")
                    soTimeout = 10_000
                    startHandshake()
                }
                activeSock = ssl
                input = DataInputStream(ssl.inputStream)
                output = DataOutputStream(ssl.outputStream)

                val cnxnReply = readAdbPacket(input)
                require(cnxnReply.cmd == A_CNXN) { "ADB TLS handshake refused (cmd=${cnxnReply.cmd})" }
                firstSeenCert?.let { AdbKeyStore.savePinnedServerCert(context, it) }
            } else if (first.cmd != A_CNXN) {
                error("Unexpected ADB response cmd=0x${first.cmd.toString(16)}")
            }

            val localId = 1
            val openPayload = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
            writeAdbPacket(output, A_OPEN, localId, 0, openPayload)

            val result = StringBuilder()
            var remoteId = 0
            while (true) {
                val pkt = readAdbPacket(input)
                when (pkt.cmd) {
                    A_OKAY -> remoteId = pkt.arg0
                    A_WRTE -> {
                        remoteId = pkt.arg0
                        result.append(String(pkt.data, Charsets.UTF_8))
                        writeAdbPacket(output, A_OKAY, localId, remoteId, ByteArray(0))
                    }
                    A_CLSE -> {
                        if (remoteId != 0) {
                            runCatching { writeAdbPacket(output, A_CLSE, localId, remoteId, ByteArray(0)) }
                        }
                        break
                    }
                }
            }
            return result.toString()
        } finally {
            runCatching { activeSock.close() }
        }
    }

    private data class AdbPacket(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun writeAdbPacket(out: DataOutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        var checksum = 0
        for (b in data) checksum += (b.toInt() and 0xFF)
        val hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(data.size)
            .putInt(checksum)
            .putInt(cmd xor -1)
            .array()
        out.write(hdr)
        if (data.isNotEmpty()) out.write(data)
        out.flush()
    }

    private fun readAdbPacket(input: DataInputStream): AdbPacket {
        val hdr = ByteArray(24)
        input.readFully(hdr)
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        bb.int
        val magic = bb.int
        require(magic == (cmd xor -1)) { "Bad ADB packet magic" }
        require(len in 0..ADB_MAXDATA) { "Invalid ADB payload length $len" }
        val data = ByteArray(len)
        if (len > 0) input.readFully(data)
        return AdbPacket(cmd, arg0, arg1, data)
    }

    private fun writePairPacket(out: DataOutputStream, type: Byte, payload: ByteArray) {
        val hdr = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .put(PAIR_VERSION)
            .put(type)
            .putInt(payload.size)
            .array()
        out.write(hdr)
        out.write(payload)
        out.flush()
    }

    private fun readPairPacket(input: DataInputStream): Pair<Byte, ByteArray> {
        val hdr = ByteArray(6)
        input.readFully(hdr)
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN)
        val ver = bb.get()
        val type = bb.get()
        val size = bb.int
        require(ver == PAIR_VERSION && size in 1..65536) { "Bad pairing header v=$ver size=$size" }
        val payload = ByteArray(size)
        input.readFully(payload)
        return type to payload
    }

    private fun aesGcmEncrypt(key: ByteArray, seq: Long, plain: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(seq).putInt(0).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(plain)
    }

    private fun aesGcmDecrypt(key: ByteArray, seq: Long, ct: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(seq).putInt(0).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ct)
    }
}
