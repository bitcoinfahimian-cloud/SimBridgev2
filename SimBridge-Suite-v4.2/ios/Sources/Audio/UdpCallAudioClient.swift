import Foundation
import AVFoundation
import Network

/// 64-frame sliding anti-replay window (matches Android UdpReplayWindow)
private final class DownlinkReplayWindow {
    private let maxForwardJump: UInt32 = 200
    private var initialized = false
    private var highestSeq: UInt32 = 0
    private var seenMask: UInt64 = 0

    func reset() {
        initialized = false
        highestSeq = 0
        seenMask = 0
    }

    func accept(_ seq: UInt32) -> Bool {
        guard seq > 0 else { return false }
        if !initialized {
            initialized = true
            highestSeq = seq
            seenMask = 1
            return true
        }
        if seq > highestSeq {
            let shift = seq - highestSeq
            if shift > maxForwardJump { return false }
            seenMask = shift >= 64 ? 1 : ((seenMask << UInt64(shift)) | 1)
            highestSeq = seq
            return true
        }
        let delta = highestSeq - seq
        if delta >= 64 { return false }
        let bit = UInt64(1) << UInt64(delta)
        if (seenMask & bit) != 0 { return false }
        seenMask |= bit
        return true
    }
}

/**
 * Fix #1: Resilient bidirectional encrypted UDP Call Audio client for iPhone 15 Pro (iOS 17+).
 * - Automatic UDP reconnect on Wi-Fi drop, IP/interface switch (NWPathMonitor), non-viable path,
 *   better path, connection failure, or 3.5s peer silence timeout.
 * - Re-performs mutual challenge-response authentication (HELLO -> CHALLENGE -> AUTH -> AUTH_OK)
 *   and seamlessly resumes AVAudioEngine capture & playback without dropping the CallKit call.
 * - Uses iPhone 15 Pro hardware Voice Processing (AEC + AGC + Noise Suppression).
 * - Separate sequence counters for audio frames vs keepalives, plus 64-frame anti-replay on downlink.
 */
final class UdpCallAudioClient: ObservableObject {
    private static let pktHello: UInt8 = 0x01
    private static let pktChallenge: UInt8 = 0x02
    private static let pktAuth: UInt8 = 0x03
    private static let pktAuthOk: UInt8 = 0x04
    private static let pktKeepalive: UInt8 = 0x05
    private static let pktAudio: UInt8 = 0x10

    private static let wireSampleRate: Double = 16000
    private static let wireFrameBytes: Int = 640 // 20ms @ 16kHz 16-bit mono
    private static let peerSilenceTimeoutSec: TimeInterval = 3.5

    @Published var isStreaming: Bool = false
    @Published var isReconnecting: Bool = false
    @Published var reconnectCount: Int = 0
    @Published var rxPackets: UInt64 = 0
    @Published var txPackets: UInt64 = 0

    private let netQueue = DispatchQueue(label: "com.simbridge.udp.audio")
    private var connection: NWConnection?
    private var pathMonitor: NWPathMonitor?
    private var lastPathStatus: NWPath.Status = .unsatisfied
    private var watchdogTimer: DispatchSourceTimer?

    private var desiredActive = false
    private var targetHost: String = ""
    private var targetPort: UInt16 = 8119
    private var keyData: Data?
    private var txKeyData: Data?   // client -> server
    private var rxKeyData: Data?   // server -> client

    private var clientNonce: Data?
    private var isAuthenticated = false
    private var lastPeerActivity: Date = .distantPast
    private var lastHelloSent: Date = .distantPast
    private var lastKeepaliveSent: Date = .distantPast
    private var audioTxSeq: UInt32 = 1
    private var keepaliveTxSeq: UInt32 = 1
    private var lastKeepaliveRxSeq: UInt32 = 0
    private var startTimeMs: UInt64 = 0
    private var rxCounter: UInt64 = 0
    private var txCounter: UInt64 = 0
    private let downlinkReplay = DownlinkReplayWindow()

    // Audio engine state (guarded by audioLock)
    private let audioLock = NSLock()
    private var engine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var wireFormat: AVAudioFormat!
    private var inputConverter: AVAudioConverter?
    private var micMonoFormat: AVAudioFormat?
    private var queuedBuffers = 0
    private var playerGeneration = 0
    private static let maxQueuedBuffers = 8 // 160 ms cap on downlink playout latency
    private var micAccumBuffer = Data()
    private var routeObserver: NSObjectProtocol?
    private var interruptObserver: NSObjectProtocol?

    func start(host: String, port: UInt16, sharedKey: Data) {
        guard !host.isEmpty, sharedKey.count == 32 else { return }
        netQueue.async {
            // Avoid tearing down an already healthy stream to the exact same endpoint
            if self.desiredActive && self.targetHost == host && self.targetPort == port && self.keyData == sharedKey {
                return
            }
            self.stopOnNetQueue(keepCounters: false)

            self.desiredActive = true
            self.targetHost = host
            self.targetPort = port
            self.keyData = sharedKey
            guard let derived = BridgeCrypto.deriveUdpKeys(master: sharedKey) else { return }
            self.txKeyData = derived.c2s
            self.rxKeyData = derived.s2c
            self.startTimeMs = UInt64(Date().timeIntervalSince1970 * 1000)

            // Float32 mono 16 kHz is what the mixer natively handles; Int16 is only the wire format
            self.wireFormat = AVAudioFormat(
                commonFormat: .pcmFormatFloat32,
                sampleRate: Self.wireSampleRate,
                channels: 1,
                interleaved: false
            )!

            self.setupAudioEngine()
            self.registerAudioNotifications()
            self.startPathMonitorOnNetQueue()
            self.startWatchdogOnNetQueue()
            self.openConnectionOnNetQueue(isReconnect: false)
        }
    }

    /// Updates endpoint seamlessly if Android's IP changes mid-call (announced via BLE call_state/pong)
    func updateEndpointIfChanged(host: String, port: UInt16) {
        guard !host.isEmpty else { return }
        netQueue.async {
            guard self.desiredActive else { return }
            if self.targetHost != host || self.targetPort != port {
                self.targetHost = host
                self.targetPort = port
                self.scheduleReconnectOnNetQueue(delaySec: 0.1)
            }
        }
    }

    func stop() {
        netQueue.async {
            self.stopOnNetQueue(keepCounters: false)
        }
    }

    private func stopOnNetQueue(keepCounters: Bool) {
        desiredActive = false
        isAuthenticated = false
        clientNonce = nil
        watchdogTimer?.cancel()
        watchdogTimer = nil
        pathMonitor?.cancel()
        pathMonitor = nil
        connection?.cancel()
        connection = nil
        downlinkReplay.reset()
        teardownAudioEngine()
        unregisterAudioNotifications()

        if !keepCounters {
            rxCounter = 0
            txCounter = 0
        }
        DispatchQueue.main.async {
            self.isStreaming = false
            self.isReconnecting = false
            if !keepCounters {
                self.rxPackets = 0
                self.txPackets = 0
                self.reconnectCount = 0
            }
        }
    }

    // MARK: - Network Path Monitoring & Automatic UDP Reconnection
    private func startPathMonitorOnNetQueue() {
        pathMonitor?.cancel()
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self, self.desiredActive else { return }
            let wasSatisfied = (self.lastPathStatus == .satisfied)
            self.lastPathStatus = path.status

            if path.status == .satisfied {
                // Network returned or switched interface/IP -> rebuild UDP socket & re-authenticate
                if !wasSatisfied || !self.isAuthenticated {
                    self.scheduleReconnectOnNetQueue(delaySec: 0.2)
                }
            } else {
                // Network dropped -> mark unauthenticated and wait for recovery
                self.isAuthenticated = false
                DispatchQueue.main.async {
                    self.isStreaming = false
                    self.isReconnecting = true
                }
            }
        }
        pathMonitor = monitor
        monitor.start(queue: netQueue)
    }

    private func startWatchdogOnNetQueue() {
        watchdogTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: netQueue)
        timer.schedule(deadline: .now() + 0.5, repeating: 0.5)
        timer.setEventHandler { [weak self] in
            self?.tickWatchdogOnNetQueue()
        }
        watchdogTimer = timer
        timer.resume()
    }

    private func tickWatchdogOnNetQueue() {
        guard desiredActive else { return }
        let now = Date()

        if !isAuthenticated {
            // Retry HELLO every 0.5s while connection is ready so dropped UDP packets never stall auth
            if connection?.state == .ready && now.timeIntervalSince(lastHelloSent) >= 0.5 {
                sendHelloOnNetQueue()
            }
            return
        }

        // Send client->server keepalive every 1.0s
        if now.timeIntervalSince(lastKeepaliveSent) >= 1.0 {
            sendKeepaliveOnNetQueue()
        }

        // Detect silent network drop (e.g. Wi-Fi freeze without immediate OS path notification)
        if now.timeIntervalSince(lastPeerActivity) > Self.peerSilenceTimeoutSec {
            isAuthenticated = false
            DispatchQueue.main.async {
                self.isStreaming = false
                self.isReconnecting = true
            }
            scheduleReconnectOnNetQueue(delaySec: 0.3)
        }
    }

    private func openConnectionOnNetQueue(isReconnect: Bool) {
        guard desiredActive, !targetHost.isEmpty, let nwPort = NWEndpoint.Port(rawValue: targetPort) else { return }

        connection?.stateUpdateHandler = nil
        connection?.viabilityUpdateHandler = nil
        connection?.betterPathUpdateHandler = nil
        connection?.cancel()
        connection = nil

        isAuthenticated = false
        clientNonce = nil
        audioTxSeq = 1
        keepaliveTxSeq = 1
        lastKeepaliveRxSeq = 0
        downlinkReplay.reset()

        if isReconnect {
            DispatchQueue.main.async {
                self.isStreaming = false
                self.isReconnecting = true
                self.reconnectCount += 1
            }
        }

        let params = NWParameters.udp
        params.serviceClass = .interactiveVoice
        let conn = NWConnection(host: NWEndpoint.Host(targetHost), port: nwPort, using: params)
        connection = conn

        conn.stateUpdateHandler = { [weak self, weak conn] state in
            guard let self, let conn, self.connection === conn, self.desiredActive else { return }
            switch state {
            case .ready:
                self.sendHelloOnNetQueue()
                self.receiveLoopOnNetQueue(conn: conn)
            case .waiting:
                self.isAuthenticated = false
                DispatchQueue.main.async {
                    self.isStreaming = false
                    self.isReconnecting = true
                }
            case .failed:
                self.isAuthenticated = false
                self.scheduleReconnectOnNetQueue(delaySec: 0.8)
            default:
                break
            }
        }

        conn.viabilityUpdateHandler = { [weak self, weak conn] viable in
            guard let self, let conn, self.connection === conn, self.desiredActive else { return }
            if !viable {
                self.isAuthenticated = false
                DispatchQueue.main.async {
                    self.isStreaming = false
                    self.isReconnecting = true
                }
                self.scheduleReconnectOnNetQueue(delaySec: 0.6)
            }
        }

        conn.betterPathUpdateHandler = { [weak self, weak conn] betterAvailable in
            guard let self, let conn, self.connection === conn, self.desiredActive else { return }
            if betterAvailable {
                self.scheduleReconnectOnNetQueue(delaySec: 0.2)
            }
        }

        conn.start(queue: netQueue)
    }

    private func scheduleReconnectOnNetQueue(delaySec: TimeInterval) {
        guard desiredActive else { return }
        netQueue.asyncAfter(deadline: .now() + delaySec) { [weak self] in
            guard let self, self.desiredActive else { return }
            self.openConnectionOnNetQueue(isReconnect: true)
        }
    }

    // MARK: - UDP Mutual Handshake & Packet Handling
    private func sendHelloOnNetQueue() {
        let nonce = BridgeCrypto.randomBytes(16)
        clientNonce = nonce
        lastHelloSent = Date()
        var pkt = Data([Self.pktHello])
        pkt.append(nonce)
        connection?.send(content: pkt, completion: .idempotent)
    }

    private func sendKeepaliveOnNetQueue() {
        guard isAuthenticated, let key = txKeyData else { return }
        lastKeepaliveSent = Date()
        var seqBE = keepaliveTxSeq.bigEndian
        keepaliveTxSeq &+= 1
        let plain = withUnsafeBytes(of: &seqBE) { Data($0) }
        guard let enc = BridgeCrypto.encrypt(keyData: key, plaintext: plain) else { return }
        var pkt = Data([Self.pktKeepalive])
        pkt.append(enc)
        connection?.send(content: pkt, completion: .idempotent)
    }

    private func receiveLoopOnNetQueue(conn: NWConnection) {
        conn.receiveMessage { [weak self, weak conn] data, _, _, error in
            guard let self, let conn, self.connection === conn, self.desiredActive else { return }
            if let data, !data.isEmpty {
                self.handleDatagramOnNetQueue(data)
            }
            if let error {
                _ = error
                self.isAuthenticated = false
                self.scheduleReconnectOnNetQueue(delaySec: 0.6)
                return
            }
            self.receiveLoopOnNetQueue(conn: conn)
        }
    }

    private func handleDatagramOnNetQueue(_ data: Data) {
        guard let txKey = txKeyData, let rxKey = rxKeyData, let type = data.first else { return }

        switch type {
        case Self.pktChallenge:
            guard data.count == 33, let cNonce = clientNonce else { return }
            let serverNonce = data.subdata(in: 1..<17)
            let echoedClient = data.subdata(in: 17..<33)
            guard echoedClient == cNonce else { return }

            var plain = Data()
            plain.append(serverNonce)
            plain.append(cNonce)
            guard let enc = BridgeCrypto.encrypt(keyData: txKey, plaintext: plain) else { return }
            var authPkt = Data([Self.pktAuth])
            authPkt.append(enc)
            connection?.send(content: authPkt, completion: .idempotent)

        case Self.pktAuthOk:
            guard data.count > 29, let cNonce = clientNonce else { return }
            let enc = data.subdata(in: 1..<data.count)
            guard let plain = BridgeCrypto.decrypt(keyData: rxKey, packet: enc),
                  plain.count == 32,
                  plain.subdata(in: 0..<16) == cNonce else { return }

            isAuthenticated = true
            lastPeerActivity = Date()
            lastKeepaliveSent = .distantPast
            downlinkReplay.reset()
            ensureAudioEngineRunning()

            DispatchQueue.main.async {
                self.isStreaming = true
                self.isReconnecting = false
            }

        case Self.pktKeepalive:
            guard isAuthenticated, data.count > 29 else { return }
            let enc = data.subdata(in: 1..<data.count)
            guard let plain = BridgeCrypto.decrypt(keyData: rxKey, packet: enc),
                  plain.count == 4 else { return }
            let kaSeq = plain.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
            guard kaSeq > lastKeepaliveRxSeq else { return }
            lastKeepaliveRxSeq = kaSeq
            lastPeerActivity = Date()

        case Self.pktAudio:
            guard isAuthenticated, data.count >= 1 + 12 + 8 + Self.wireFrameBytes + 16 else { return }
            let enc = data.subdata(in: 1..<data.count)
            guard let plain = BridgeCrypto.decrypt(keyData: rxKey, packet: enc),
                  plain.count == 8 + Self.wireFrameBytes else { return }

            let seq = plain.prefix(4).withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
            guard downlinkReplay.accept(seq) else { return }

            lastPeerActivity = Date()
            let pcm = plain.subdata(in: 8..<plain.count)
            scheduleDownlinkPlayback(pcm)

            rxCounter += 1
            if rxCounter % 50 == 0 {
                let snap = rxCounter
                DispatchQueue.main.async { self.rxPackets = snap }
            }

        default:
            break
        }
    }

    // MARK: - AVAudioEngine with iPhone Hardware Voice Processing (AEC + AGC)
    private func setupAudioEngine() {
        audioLock.lock()
        defer { audioLock.unlock() }

        teardownAudioEngineLocked()

        let eng = AVAudioEngine()
        let player = AVAudioPlayerNode()

        try? eng.inputNode.setVoiceProcessingEnabled(true)
        try? eng.outputNode.setVoiceProcessingEnabled(true)

        eng.attach(player)
        eng.connect(player, to: eng.mainMixerNode, format: wireFormat)

        let inputFormat = eng.inputNode.outputFormat(forBus: 0)
        if inputFormat.sampleRate > 0 && inputFormat.channelCount > 0 {
            // Voice processing may deliver multi-channel input; we always take channel 0 as mono
            let monoFmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: inputFormat.sampleRate, channels: 1, interleaved: false)
            micMonoFormat = monoFmt
            if let monoFmt { inputConverter = AVAudioConverter(from: monoFmt, to: wireFormat) }
            eng.inputNode.installTap(onBus: 0, bufferSize: 960, format: inputFormat) { [weak self] buffer, _ in
                self?.processMicBuffer(buffer)
            }
        }

        // Assign BEFORE eng.start() so activateAfterAudioSession() can always start it when CallKit activates audio
        self.engine = eng
        self.playerNode = player

        do {
            try eng.start()
            player.play()
        } catch {
            // Expected when CallKit has not activated AVAudioSession yet; started in activateAfterAudioSession()
        }
    }

    private func teardownAudioEngine() {
        audioLock.lock()
        defer { audioLock.unlock() }
        teardownAudioEngineLocked()
    }

    private func teardownAudioEngineLocked() {
        engine?.inputNode.removeTap(onBus: 0)
        playerNode?.stop()
        engine?.stop()
        engine = nil
        playerNode = nil
        inputConverter = nil
        micMonoFormat = nil
        micAccumBuffer.removeAll()
        queuedBuffers = 0
        playerGeneration += 1
    }

    func activateAfterAudioSession() {
        netQueue.async {
            guard self.desiredActive else { return }
            self.ensureAudioEngineRunning()
        }
    }

    private func ensureAudioEngineRunning() {
        audioLock.lock()
        let needsRebuild = (engine == nil) || (inputConverter == nil)
        audioLock.unlock()

        if needsRebuild {
            setupAudioEngine()
            return
        }

        audioLock.lock()
        defer { audioLock.unlock() }
        if engine?.isRunning != true {
            try? engine?.start()
        }
        if playerNode?.isPlaying != true {
            playerNode?.play()
        }
    }

    private func registerAudioNotifications() {
        unregisterAudioNotifications()
        let nc = NotificationCenter.default
        routeObserver = nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { [weak self] _ in
            self?.netQueue.async {
                guard let self, self.desiredActive else { return }
                self.setupAudioEngine()
            }
        }
        interruptObserver = nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { [weak self] note in
            guard let info = note.userInfo,
                  let rawType = info[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: rawType) else { return }
            if type == .ended {
                self?.netQueue.async {
                    guard let self, self.desiredActive else { return }
                    self.ensureAudioEngineRunning()
                }
            }
        }
    }

    private func unregisterAudioNotifications() {
        if let r = routeObserver { NotificationCenter.default.removeObserver(r); routeObserver = nil }
        if let i = interruptObserver { NotificationCenter.default.removeObserver(i); interruptObserver = nil }
    }

    // Runs on real-time audio thread: channel 0 -> mono Float32 -> 16 kHz Float32 -> Int16 LE, frames handed to netQueue
    private func processMicBuffer(_ inputBuffer: AVAudioPCMBuffer) {
        audioLock.lock()
        guard let converter = inputConverter, let fmt = wireFormat, let monoFmt = micMonoFormat,
              let srcCh = inputBuffer.floatChannelData?[0], inputBuffer.frameLength > 0,
              let monoBuf = AVAudioPCMBuffer(pcmFormat: monoFmt, frameCapacity: inputBuffer.frameLength),
              let monoDst = monoBuf.floatChannelData?[0] else {
            audioLock.unlock()
            return
        }
        memcpy(monoDst, srcCh, Int(inputBuffer.frameLength) * MemoryLayout<Float>.size)
        monoBuf.frameLength = inputBuffer.frameLength

        let ratio = Self.wireSampleRate / monoFmt.sampleRate
        let outCap = AVAudioFrameCount(Double(monoBuf.frameLength) * ratio) + 32
        guard let outBuf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: outCap) else {
            audioLock.unlock()
            return
        }

        var consumed = false
        var convError: NSError?
        converter.convert(to: outBuf, error: &convError) { _, outStatus in
            if consumed {
                outStatus.pointee = .noDataNow
                return nil
            }
            consumed = true
            outStatus.pointee = .haveData
            return monoBuf
        }

        guard convError == nil, outBuf.frameLength > 0, let f32 = outBuf.floatChannelData?[0] else {
            audioLock.unlock()
            return
        }

        let n = Int(outBuf.frameLength)
        var chunk = Data(count: n * 2)
        chunk.withUnsafeMutableBytes { raw in
            let dst = raw.bindMemory(to: Int16.self)
            for k in 0..<n {
                let v = max(-1.0, min(1.0, f32[k])) * 32767.0
                dst[k] = Int16(v.rounded()).littleEndian
            }
        }
        micAccumBuffer.append(chunk)

        var readyFrames: [Data] = []
        while micAccumBuffer.count >= Self.wireFrameBytes {
            readyFrames.append(Data(micAccumBuffer.prefix(Self.wireFrameBytes)))
            micAccumBuffer.removeFirst(Self.wireFrameBytes)
        }
        audioLock.unlock()

        if !readyFrames.isEmpty {
            netQueue.async { [weak self] in
                guard let self else { return }
                for frame in readyFrames {
                    self.sendAudioFrameOnNetQueue(frame)
                }
            }
        }
    }

    private func sendAudioFrameOnNetQueue(_ pcm: Data) {
        guard isAuthenticated, let key = txKeyData else { return }
        var plain = Data(capacity: 8 + pcm.count)
        var seqBE = audioTxSeq.bigEndian
        audioTxSeq &+= 1
        let nowMs = UInt64(Date().timeIntervalSince1970 * 1000)
        var tsBE = UInt32(truncatingIfNeeded: nowMs &- startTimeMs).bigEndian

        withUnsafeBytes(of: &seqBE) { plain.append(contentsOf: $0) }
        withUnsafeBytes(of: &tsBE) { plain.append(contentsOf: $0) }
        plain.append(pcm)

        guard let enc = BridgeCrypto.encrypt(keyData: key, plaintext: plain) else { return }
        var pkt = Data([Self.pktAudio])
        pkt.append(enc)
        connection?.send(content: pkt, completion: .idempotent)

        txCounter += 1
        if txCounter % 50 == 0 {
            let snap = txCounter
            DispatchQueue.main.async { self.txPackets = snap }
        }
    }

    private func scheduleDownlinkPlayback(_ pcm: Data) {
        audioLock.lock()
        guard let player = playerNode, let format = wireFormat else {
            audioLock.unlock()
            return
        }
        // Bound latency: if the player is backed up (Wi-Fi burst), drop the newest frame instead of growing the queue
        if queuedBuffers >= Self.maxQueuedBuffers {
            audioLock.unlock()
            return
        }
        let frames = pcm.count / 2
        guard frames > 0, let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)),
              let dst = buf.floatChannelData?[0] else {
            audioLock.unlock()
            return
        }
        buf.frameLength = AVAudioFrameCount(frames)
        pcm.withUnsafeBytes { raw in
            let src = raw.bindMemory(to: Int16.self)
            for k in 0..<frames {
                dst[k] = Float(Int16(littleEndian: src[k])) / 32768.0
            }
        }
        queuedBuffers += 1
        let gen = playerGeneration
        player.scheduleBuffer(buf) { [weak self] in
            guard let self else { return }
            self.audioLock.lock()
            if self.playerGeneration == gen && self.queuedBuffers > 0 { self.queuedBuffers -= 1 }
            self.audioLock.unlock()
        }
        audioLock.unlock()
    }
}
