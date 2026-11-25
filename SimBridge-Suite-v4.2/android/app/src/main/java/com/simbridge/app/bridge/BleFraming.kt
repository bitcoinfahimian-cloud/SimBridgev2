package com.simbridge.app.bridge

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * 4-byte BLE GATT framing protocol (byte-for-byte identical to iOS BleFraming.swift):
 * [0] msgId (uint8)
 * [1] chunkIndex (uint8, 0..total-1)
 * [2] totalChunks (uint8, 1..64)
 * [3] flags (0x00 = plaintext auth_challenge, 0x01 = AES-256-GCM encrypted payload)
 * [4..] payload chunk
 */
class BleFraming {
    companion object {
        const val FLAG_PLAINTEXT: Byte = 0x00
        const val FLAG_ENCRYPTED: Byte = 0x01
        private const val HEADER_SIZE = 4
        private const val MAX_CHUNKS = 64
    }

    private val nextMsgId = AtomicInteger(1)
    private var rxMsgId: Int = -1
    private var rxTotal: Int = 0
    private var rxFlags: Byte = 0
    private var rxChunks: Array<ByteArray?> = emptyArray()
    private var rxReceivedCount: Int = 0

    fun encode(payload: ByteArray, flags: Byte, mtu: Int): List<ByteArray> {
        val maxAttPayload = (mtu - 3).coerceIn(20, 244)
        val maxDataPerChunk = (maxAttPayload - HEADER_SIZE).coerceAtLeast(16)
        val total = if (payload.isEmpty()) 1 else (payload.size + maxDataPerChunk - 1) / maxDataPerChunk
        // Reject oversized payloads instead of silently truncating ciphertext/GCM tag
        if (total > MAX_CHUNKS) return emptyList()

        val msgId = (nextMsgId.getAndIncrement() and 0xFF).toByte()
        val result = ArrayList<ByteArray>(total)
        for (idx in 0 until total) {
            val start = idx * maxDataPerChunk
            val end = minOf(start + maxDataPerChunk, payload.size)
            val chunkLen = (end - start).coerceAtLeast(0)
            val frame = ByteArray(HEADER_SIZE + chunkLen)
            frame[0] = msgId
            frame[1] = idx.toByte()
            frame[2] = total.toByte()
            frame[3] = flags
            if (chunkLen > 0) {
                System.arraycopy(payload, start, frame, HEADER_SIZE, chunkLen)
            }
            result.add(frame)
        }
        return result
    }

    @Synchronized
    fun decodeChunk(frame: ByteArray): Pair<Byte, ByteArray>? {
        if (frame.size < HEADER_SIZE) return null
        val msgId = frame[0].toInt() and 0xFF
        val idx = frame[1].toInt() and 0xFF
        val total = frame[2].toInt() and 0xFF
        val flags = frame[3]

        if (total !in 1..MAX_CHUNKS || idx >= total) return null

        val data = frame.copyOfRange(HEADER_SIZE, frame.size)
        if (total == 1) {
            return flags to data
        }

        if (msgId != rxMsgId || total != rxTotal) {
            rxMsgId = msgId
            rxTotal = total
            rxFlags = flags
            rxChunks = arrayOfNulls(total)
            rxReceivedCount = 0
        }

        if (rxChunks[idx] == null) {
            rxChunks[idx] = data
            rxReceivedCount++
        }

        if (rxReceivedCount == rxTotal) {
            val out = ByteArrayOutputStream()
            for (c in rxChunks) {
                if (c != null) out.write(c)
            }
            val completedFlags = rxFlags
            resetRx()
            return completedFlags to out.toByteArray()
        }
        return null
    }

    @Synchronized
    fun resetRx() {
        rxMsgId = -1
        rxTotal = 0
        rxChunks = emptyArray()
        rxReceivedCount = 0
    }
}
