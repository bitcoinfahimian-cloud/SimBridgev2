package com.simbridge.app.bridge

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 64-packet sliding anti-replay window compatible with out-of-order UDP delivery and Jitter Buffers.
 * - Accepts packets within [highestSeq - 63, highestSeq + MAX_FORWARD_JUMP] that have not been seen yet.
 * - Rejects duplicates, replayed packets, packets older than the playout floor, and implausible jumps.
 */
class UdpReplayWindow(private val maxForwardJump: Long = 200L) {
    private var initialized = false
    private var highestSeq: Long = 0L
    private var seenMask: Long = 0L // bit 0 = highestSeq, bit k = highestSeq - k

    @Synchronized
    fun reset() {
        initialized = false
        highestSeq = 0L
        seenMask = 0L
    }

    @Synchronized
    fun accept(seq: Long, playoutFloor: Long): Boolean {
        if (seq <= 0L) return false
        if (playoutFloor > 0L && seq < playoutFloor) return false

        if (!initialized) {
            initialized = true
            highestSeq = seq
            seenMask = 1L
            return true
        }

        if (seq > highestSeq) {
            val shift = seq - highestSeq
            if (shift > maxForwardJump) return false
            seenMask = if (shift >= 64L) 1L else ((seenMask shl shift.toInt()) or 1L)
            highestSeq = seq
            return true
        }

        val delta = highestSeq - seq
        if (delta >= 64L) return false // Too old / outside anti-replay window
        val bit = 1L shl delta.toInt()
        if ((seenMask and bit) != 0L) return false // Duplicate / replay
        seenMask = seenMask or bit
        return true
    }
}

/**
 * Encrypted UDP Call Audio Server (port 8119) for Android <-> iOS live call voice:
 * - Mutual challenge-response auth with AES-256-GCM (supports seamless mid-call IP/port recovery).
 * - 64-frame sliding anti-replay window + Jitter Buffer for out-of-order uplink frames.
 * - Separate sequence counters for audio frames vs keepalives so keepalives never create gaps in audio seq.
 */
class CallAudioStreamServer(
    private val context: Context,
    private val port: Int = 8119
) {
    companion object {
        private const val PKT_HELLO: Byte = 0x01
        private const val PKT_CHALLENGE: Byte = 0x02
        private const val PKT_AUTH: Byte = 0x03
        private const val PKT_AUTH_OK: Byte = 0x04
        private const val PKT_KEEPALIVE: Byte = 0x05
        private const val PKT_AUDIO: Byte = 0x10

        const val FRAME_BYTES = 640 // 20ms @ 16kHz 16-bit signed LE mono
        private const val JITTER_TARGET_FRAMES = 2 // 40ms initial buffer
        private const val JITTER_MAX_FRAMES = 10   // 200ms upper bound before dropping oldest
        private const val PEER_TIMEOUT_MS = 5000L
        private const val HANDSHAKE_HOLD_MS = 3000L
    }

    private var socket: DatagramSocket? = null
    private var scope: CoroutineScope? = null
    private val running = AtomicBoolean(false)
    private val random = SecureRandom()

    @Volatile private var peerAddress: InetAddress? = null
    @Volatile private var peerPort: Int = -1
    @Volatile private var peerAuthenticated: Boolean = false
    @Volatile private var lastPeerSeenMs: Long = 0L

    @Volatile private var pendingServerNonce: ByteArray? = null
    @Volatile private var pendingClientNonce: ByteArray? = null
    @Volatile private var pendingPeerAddr: InetAddress? = null
    @Volatile private var pendingPeerPort: Int = -1
    @Volatile private var pendingSinceMs: Long = 0L
    @Volatile private var txKey: ByteArray = ByteArray(32)
    @Volatile private var rxKey: ByteArray = ByteArray(32)

    // Separate sequence spaces so keepalives never disturb audio jitter-buffer sequence numbers
    private val audioTxSeq = AtomicLong(1L)
    private val keepaliveTxSeq = AtomicLong(1L)
    private val txAudioCount = AtomicLong(0L)
    private val rxAudioCount = AtomicLong(0L)
    private val rxReplayDroppedCount = AtomicLong(0L)

    // Anti-replay + Jitter Buffer for incoming uplink audio & keepalives
    private val uplinkReplayWindow = UdpReplayWindow()
    @Volatile private var lastKeepaliveRxSeq: Long = 0L
    private val uplinkJitterBuffer = ConcurrentSkipListMap<Long, ByteArray>()
    @Volatile private var nextExpectedUplinkSeq: Long = -1L

    fun start(pipes: Array<ParcelFileDescriptor>) {
        stop()
        if (pipes.size < 2) return
        running.set(true)
        resetSessionState()
        txAudioCount.set(0L)
        rxAudioCount.set(0L)
        rxReplayDroppedCount.set(0L)

        val downReadFd = pipes[0].fileDescriptor
        val upWriteFd = pipes[1].fileDescriptor
        val master = BleCrypto.getOrCreateSharedKey(context)
        // Per-direction subkeys (v4.2): Android sends with s2c, receives with c2s
        val (c2s, s2c) = BleCrypto.deriveUdpKeys(master)
        txKey = s2c
        rxKey = c2s

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val ds = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                trafficClass = 0xB8 // DSCP EF for interactive voice
                bind(InetSocketAddress("0.0.0.0", port))
            }
        }.getOrNull() ?: return
        socket = ds

        // 1) UDP Receive Loop
        scope?.launch {
            val rxBuf = ByteArray(1500)
            while (isActive && running.get() && !ds.isClosed) {
                val pkt = DatagramPacket(rxBuf, rxBuf.size)
                val ok = runCatching { ds.receive(pkt); true }.getOrDefault(false)
                if (!ok || pkt.length < 2) continue
                handleIncomingDatagram(ds, pkt.address, pkt.port, rxBuf, pkt.length)
            }
        }

        // 2) Downlink Pump: Android VOICE_DOWNLINK pipe -> AES-256-GCM UDP -> iPhone speaker
        scope?.launch {
            val pipeIn = FileInputStream(downReadFd)
            val pcmBuf = ByteArray(FRAME_BYTES)
            val plainFrame = ByteArray(8 + FRAME_BYTES)
            val startMs = System.currentTimeMillis()
            while (isActive && running.get() && !ds.isClosed) {
                val n = readFullFrame(pipeIn, pcmBuf)
                if (n <= 0) break
                if (!peerAuthenticated) continue
                val addr = peerAddress ?: continue
                val pPort = peerPort
                if (pPort <= 0) continue

                val seq = audioTxSeq.getAndIncrement().toInt()
                val ts = (System.currentTimeMillis() - startMs).toInt()
                val bb = ByteBuffer.wrap(plainFrame).order(ByteOrder.BIG_ENDIAN)
                bb.putInt(seq)
                bb.putInt(ts)
                System.arraycopy(pcmBuf, 0, plainFrame, 8, n)

                val enc = BleCrypto.encrypt(txKey, plainFrame, 0, 8 + n)
                val udpPayload = ByteArray(1 + enc.size)
                udpPayload[0] = PKT_AUDIO
                System.arraycopy(enc, 0, udpPayload, 1, enc.size)

                runCatching {
                    ds.send(DatagramPacket(udpPayload, udpPayload.size, addr, pPort))
                    val c = txAudioCount.incrementAndGet()
                    if (c % 50L == 0L) {
                        BridgeEventBus.notifyAudioStats(rxAudioCount.get(), c)
                    }
                }
            }
        }

        // 3) Uplink Playout Pacer (20ms cadence): Jitter Buffer -> Android Shell Uplink Pipe
        scope?.launch {
            val pipeOut = FileOutputStream(upWriteFd)
            val silence = ByteArray(FRAME_BYTES)
            val tickNs = 20_000_000L
            var nextTick = System.nanoTime()
            while (isActive && running.get()) {
                if (!peerAuthenticated) {
                    delay(20)
                    nextTick = System.nanoTime()
                    continue
                }
                if (nextExpectedUplinkSeq < 0L) {
                    if (uplinkJitterBuffer.size >= JITTER_TARGET_FRAMES) {
                        nextExpectedUplinkSeq = uplinkJitterBuffer.firstKey()
                    } else {
                        delay(10)
                        nextTick = System.nanoTime()
                        continue
                    }
                }

                // Trim excessive backlog to bound latency <= 200ms
                while (uplinkJitterBuffer.size > JITTER_MAX_FRAMES) {
                    val oldest = uplinkJitterBuffer.firstKey()
                    uplinkJitterBuffer.remove(oldest)
                    nextExpectedUplinkSeq = uplinkJitterBuffer.firstKey()
                }

                // Drop any stragglers that somehow fell below nextExpectedUplinkSeq
                while (true) {
                    val first = uplinkJitterBuffer.firstEntry() ?: break
                    if (first.key < nextExpectedUplinkSeq) {
                        uplinkJitterBuffer.remove(first.key)
                    } else {
                        break
                    }
                }

                val frame = uplinkJitterBuffer.remove(nextExpectedUplinkSeq)
                if (frame != null) {
                    nextExpectedUplinkSeq++
                    if (runCatching { pipeOut.write(frame) }.isFailure) break
                } else {
                    val firstAvail = uplinkJitterBuffer.firstEntry()
                    if (firstAvail != null &&
                        firstAvail.key > nextExpectedUplinkSeq &&
                        uplinkJitterBuffer.size >= JITTER_TARGET_FRAMES
                    ) {
                        // Missing packet was lost; advance playout cursor to next contiguous buffered frame
                        nextExpectedUplinkSeq = firstAvail.key + 1
                        val nextPcm = uplinkJitterBuffer.remove(firstAvail.key) ?: silence
                        if (runCatching { pipeOut.write(nextPcm) }.isFailure) break
                    } else {
                        if (runCatching { pipeOut.write(silence) }.isFailure) break
                    }
                }
                // Absolute-time pacing: no cumulative drift from write/GC time
                nextTick += tickNs
                val waitNs = nextTick - System.nanoTime()
                if (waitNs > 0L) delay((waitNs + 999_999L) / 1_000_000L)
                else if (waitNs < -5 * tickNs) nextTick = System.nanoTime()
            }
        }

        // 4) Keepalive sender & peer liveness watchdog (1s)
        scope?.launch {
            while (isActive && running.get() && !ds.isClosed) {
                delay(1000)
                if (peerAuthenticated) {
                    val now = System.currentTimeMillis()
                    if (lastPeerSeenMs > 0L && (now - lastPeerSeenMs) > PEER_TIMEOUT_MS) {
                        // Peer went silent (e.g. Wi-Fi dropped); wait for fresh HELLO/AUTH re-handshake
                        peerAuthenticated = false
                        uplinkJitterBuffer.clear()
                        nextExpectedUplinkSeq = -1L
                        continue
                    }
                    val addr = peerAddress
                    val pPort = peerPort
                    if (addr != null && pPort > 0) {
                        val seqBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                            .putInt(keepaliveTxSeq.getAndIncrement().toInt()).array()
                        val enc = BleCrypto.encrypt(txKey, seqBytes)
                        val pkt = ByteArray(1 + enc.size).apply {
                            this[0] = PKT_KEEPALIVE
                            System.arraycopy(enc, 0, this, 1, enc.size)
                        }
                        runCatching { ds.send(DatagramPacket(pkt, pkt.size, addr, pPort)) }
                    }
                }
            }
        }
    }

    private fun resetSessionState() {
        peerAuthenticated = false
        peerAddress = null
        peerPort = -1
        lastPeerSeenMs = 0L
        audioTxSeq.set(1L)
        keepaliveTxSeq.set(1L)
        lastKeepaliveRxSeq = 0L
        uplinkReplayWindow.reset()
        uplinkJitterBuffer.clear()
        nextExpectedUplinkSeq = -1L
    }

    private fun handleIncomingDatagram(
        ds: DatagramSocket,
        addr: InetAddress,
        srcPort: Int,
        buf: ByteArray,
        len: Int
    ) {
        when (buf[0]) {
            PKT_HELLO -> {
                if (len != 17) return
                val nowMs = System.currentTimeMillis()
                val pa = pendingPeerAddr
                if (pa != null && pendingServerNonce != null &&
                    (pa != addr || pendingPeerPort != srcPort) &&
                    (nowMs - pendingSinceMs) < HANDSHAKE_HOLD_MS
                ) return // don't let a stray HELLO clobber an in-flight handshake
                val clientNonce = buf.copyOfRange(1, 17)
                val serverNonce = ByteArray(16).also { random.nextBytes(it) }
                pendingClientNonce = clientNonce
                pendingServerNonce = serverNonce
                pendingPeerAddr = addr
                pendingPeerPort = srcPort
                pendingSinceMs = nowMs

                val reply = ByteArray(33)
                reply[0] = PKT_CHALLENGE
                System.arraycopy(serverNonce, 0, reply, 1, 16)
                System.arraycopy(clientNonce, 0, reply, 17, 16)
                runCatching { ds.send(DatagramPacket(reply, reply.size, addr, srcPort)) }
            }

            PKT_AUTH -> {
                val sNonce = pendingServerNonce ?: return
                val cNonce = pendingClientNonce ?: return
                if (addr != pendingPeerAddr || srcPort != pendingPeerPort) return
                val enc = buf.copyOfRange(1, len)
                val plain = BleCrypto.decrypt(rxKey, enc) ?: return
                if (plain.size != 32) return

                val expected = ByteArray(32).apply {
                    System.arraycopy(sNonce, 0, this, 0, 16)
                    System.arraycopy(cNonce, 0, this, 16, 16)
                }
                if (!MessageDigest.isEqual(plain, expected)) return

                // Mutual authentication succeeded — reset replay windows & seq for fresh/recovered session
                peerAddress = addr
                peerPort = srcPort
                peerAuthenticated = true
                lastPeerSeenMs = System.currentTimeMillis()
                pendingServerNonce = null
                pendingClientNonce = null
                audioTxSeq.set(1L)
                keepaliveTxSeq.set(1L)
                lastKeepaliveRxSeq = 0L
                uplinkReplayWindow.reset()
                uplinkJitterBuffer.clear()
                nextExpectedUplinkSeq = -1L

                val ackPlain = ByteArray(32).apply {
                    System.arraycopy(cNonce, 0, this, 0, 16)
                    System.arraycopy(sNonce, 0, this, 16, 16)
                }
                val ackEnc = BleCrypto.encrypt(txKey, ackPlain)
                val ackPkt = ByteArray(1 + ackEnc.size).apply {
                    this[0] = PKT_AUTH_OK
                    System.arraycopy(ackEnc, 0, this, 1, ackEnc.size)
                }
                runCatching { ds.send(DatagramPacket(ackPkt, ackPkt.size, addr, srcPort)) }
            }

            PKT_KEEPALIVE -> {
                if (!peerAuthenticated || addr != peerAddress) return
                val enc = buf.copyOfRange(1, len)
                val plain = BleCrypto.decrypt(rxKey, enc) ?: return
                if (plain.size != 4) return
                val kaSeq = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (kaSeq <= lastKeepaliveRxSeq) {
                    rxReplayDroppedCount.incrementAndGet()
                    return
                }
                lastKeepaliveRxSeq = kaSeq
                peerPort = srcPort
                lastPeerSeenMs = System.currentTimeMillis()
            }

            PKT_AUDIO -> {
                if (!peerAuthenticated || addr != peerAddress) return
                val enc = buf.copyOfRange(1, len)
                val plain = BleCrypto.decrypt(rxKey, enc) ?: return
                // Validate exact 8-byte header + 640-byte 20ms PCM frame
                if (plain.size != 8 + FRAME_BYTES) return

                val bb = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN)
                val seq = bb.int.toLong() and 0xFFFFFFFFL
                bb.int // timestampMs

                // Enforce 64-frame sliding replay window & reject packets behind active playout cursor
                val floor = if (nextExpectedUplinkSeq > 0L) nextExpectedUplinkSeq else 0L
                if (!uplinkReplayWindow.accept(seq, floor)) {
                    rxReplayDroppedCount.incrementAndGet()
                    return
                }

                val pcm = plain.copyOfRange(8, plain.size)
                peerPort = srcPort
                lastPeerSeenMs = System.currentTimeMillis()
                uplinkJitterBuffer[seq] = pcm
                rxAudioCount.incrementAndGet()
            }
        }
    }

    fun stop() {
        running.set(false)
        peerAuthenticated = false
        runCatching { socket?.close() }
        socket = null
        scope?.cancel()
        scope = null
        uplinkReplayWindow.reset()
        uplinkJitterBuffer.clear()
        nextExpectedUplinkSeq = -1L
    }

    private fun readFullFrame(input: FileInputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val n = runCatching { input.read(buf, offset, buf.size - offset) }.getOrDefault(-1)
            if (n <= 0) return if (offset > 0) offset else -1
            offset += n
        }
        return offset
    }
}
