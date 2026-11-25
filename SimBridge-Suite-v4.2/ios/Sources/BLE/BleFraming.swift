import Foundation

final class BleFraming {
    static let flagPlaintext: UInt8 = 0x00
    static let flagEncrypted: UInt8 = 0x01
    private static let headerSize = 4
    private static let maxChunks = 64

    private var nextMsgId: UInt8 = 1
    private var rxMsgId: Int = -1
    private var rxTotal: Int = 0
    private var rxFlags: UInt8 = 0
    private var rxChunks: [Data?] = []
    private var rxCount: Int = 0
    private let lock = NSLock()

    func encode(payload: Data, flags: UInt8, maxWriteLen: Int) -> [Data] {
        let chunkLimit = max(16, min(maxWriteLen, 240) - Self.headerSize)
        let total = payload.isEmpty ? 1 : (payload.count + chunkLimit - 1) / chunkLimit
        // Reject oversized payloads instead of silently truncating ciphertext/GCM tag
        guard total <= Self.maxChunks else { return [] }

        lock.lock()
        let msgId = nextMsgId
        nextMsgId = nextMsgId &+ 1
        lock.unlock()

        var frames: [Data] = []
        frames.reserveCapacity(total)
        for idx in 0..<total {
            let start = idx * chunkLimit
            let end = min(start + chunkLimit, payload.count)
            var frame = Data(capacity: Self.headerSize + (end - start))
            frame.append(msgId)
            frame.append(UInt8(idx))
            frame.append(UInt8(total))
            frame.append(flags)
            if end > start {
                frame.append(payload[start..<end])
            }
            frames.append(frame)
        }
        return frames
    }

    func decodeChunk(_ frame: Data) -> (UInt8, Data)? {
        guard frame.count >= Self.headerSize else { return nil }
        let msgId = Int(frame[0])
        let idx = Int(frame[1])
        let total = Int(frame[2])
        let flags = frame[3]
        guard total >= 1 && total <= Self.maxChunks && idx < total else { return nil }

        let body = frame.subdata(in: Self.headerSize..<frame.count)
        if total == 1 { return (flags, body) }

        lock.lock()
        defer { lock.unlock() }

        if msgId != rxMsgId || total != rxTotal {
            rxMsgId = msgId
            rxTotal = total
            rxFlags = flags
            rxChunks = Array(repeating: nil, count: total)
            rxCount = 0
        }
        if rxChunks[idx] == nil {
            rxChunks[idx] = body
            rxCount += 1
        }
        if rxCount == rxTotal {
            var full = Data()
            for part in rxChunks {
                if let p = part { full.append(p) }
            }
            let doneFlags = rxFlags
            rxMsgId = -1
            rxTotal = 0
            rxChunks = []
            rxCount = 0
            return (doneFlags, full)
        }
        return nil
    }

    func reset() {
        lock.lock()
        defer { lock.unlock() }
        rxMsgId = -1
        rxTotal = 0
        rxChunks = []
        rxCount = 0
    }
}
