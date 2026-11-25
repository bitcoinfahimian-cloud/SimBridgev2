import Foundation
import CoreBluetooth
import UIKit

struct SmsMessageItem: Identifiable, Hashable {
    let id = UUID()
    let from: String
    let senderName: String
    let body: String
    let date: Date
    let isOutgoing: Bool
}

struct CallEventPayload {
    let state: String // DIALING, RINGING, ACTIVE, IDLE
    let number: String
    let callerName: String
    let audioActive: Bool
    let audioHost: String
    let audioPort: UInt16
}

final class BleBridgeClient: NSObject, ObservableObject {
    static let defaultServiceUUID = CBUUID(string: "8f2b0001-5b8f-4c2a-9d3e-2f6a1b0c9d01")
    static let writeCharUUID = CBUUID(string: "8f2b0002-5b8f-4c2a-9d3e-2f6a1b0c9d01")
    static let notifyCharUUID = CBUUID(string: "8f2b0003-5b8f-4c2a-9d3e-2f6a1b0c9d01")

    @Published var isPaired: Bool = false
    @Published var isConnected: Bool = false
    @Published var bluetoothPoweredOn: Bool = false
    @Published var androidModel: String = "Xiaomi Mi 10T Pro"
    @Published var callAudioReady: Bool = false
    @Published var companionRegistered: Bool = false
    @Published var companionBound: Bool = false
    @Published var companionEventsCount: Int = 0
    @Published var lastAudioHost: String = ""
    @Published var lastAudioPort: UInt16 = 8119
    @Published var messages: [SmsMessageItem] = []
    @Published var lastProbeText: String = ""

    var onCallEvent: ((CallEventPayload) -> Void)?
    var onAudioEndpointUpdated: ((String, UInt16) -> Void)?

    private let bleQueue = DispatchQueue(label: "com.simbridge.ble")
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var writeChar: CBCharacteristic?
    private var notifyChar: CBCharacteristic?

    // Serialized write queue (prevents chunk interleaving or CoreBluetooth write overflow)
    private var pendingWriteChunks: [Data] = []
    private var writeInFlight = false

    private let framing = BleFraming()
    private var sharedKey: Data?
    private var serviceUUID: CBUUID = defaultServiceUUID
    private var pendingClientNonce: String?
    private var isAuthenticated = false
    private var txSeq: Int64 = 1
    private var lastRxSeq: Int64 = 0
    private var heartbeatTimer: DispatchSourceTimer?

    override init() {
        super.init()
        if let savedKey = KeychainStore.loadKey(), savedKey.count == 32 {
            sharedKey = savedKey
            isPaired = true
            if let uuidStr = KeychainStore.loadServiceUUID() {
                serviceUUID = CBUUID(string: uuidStr)
            }
        }
        central = CBCentralManager(
            delegate: self,
            queue: bleQueue,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "com.simbridge.ios.central"]
        )
    }

    func pairFromQrPayload(_ payload: String) -> Bool {
        guard let comps = URLComponents(string: payload),
              comps.scheme == "simbridge",
              let items = comps.queryItems,
              let kStr = items.first(where: { $0.name == "k" })?.value,
              let keyData = BridgeCrypto.base64UrlDecode(kStr),
              keyData.count == 32 else {
            return false
        }
        let svcStr = items.first(where: { $0.name == "svc" })?.value ?? Self.defaultServiceUUID.uuidString
        let portVal = UInt16(items.first(where: { $0.name == "ap" })?.value ?? "8119") ?? 8119

        KeychainStore.savePairing(keyData: keyData, serviceUUID: svcStr)
        bleQueue.async {
            self.sharedKey = keyData
            self.serviceUUID = CBUUID(string: svcStr)
            DispatchQueue.main.async {
                self.isPaired = true
                self.lastAudioPort = portVal
            }
            self.startScanOnQueue()
        }
        return true
    }

    func unpair() {
        KeychainStore.clear()
        bleQueue.async {
            self.sharedKey = nil
            self.isAuthenticated = false
            self.pendingWriteChunks.removeAll()
            self.writeInFlight = false
            self.stopHeartbeatOnQueue()
            if let p = self.peripheral { self.central.cancelPeripheralConnection(p) }
            self.peripheral = nil
            DispatchQueue.main.async {
                self.isPaired = false
                self.isConnected = false
                self.callAudioReady = false
                self.companionRegistered = false
                self.companionBound = false
                self.companionEventsCount = 0
            }
        }
    }

    private func startScanOnQueue() {
        guard sharedKey != nil, central.state == .poweredOn else { return }
        central.scanForPeripherals(
            withServices: [serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
    }

    // MARK: - Outgoing Control Commands (all serialized on bleQueue)
    func sendDial(number: String) {
        bleQueue.async { self.sendEncryptedCommandOnQueue(["type": "dial", "number": number]) }
    }

    func sendAnswer() {
        bleQueue.async { self.sendEncryptedCommandOnQueue(["type": "answer"]) }
    }

    func sendHangup() {
        bleQueue.async { self.sendEncryptedCommandOnQueue(["type": "hangup"]) }
    }

    func sendMute(_ muted: Bool) {
        bleQueue.async { self.sendEncryptedCommandOnQueue(["type": "mute", "muted": muted]) }
    }

    func sendDtmf(_ digit: String) {
        bleQueue.async { self.sendEncryptedCommandOnQueue(["type": "dtmf", "digit": digit]) }
    }

    func sendSms(to: String, body: String) {
        bleQueue.async { self.sendEncryptedCommandOnQueue(["type": "send_sms", "to": to, "body": body]) }
        DispatchQueue.main.async {
            self.messages.insert(
                SmsMessageItem(from: to, senderName: to, body: body, date: Date(), isOutgoing: true),
                at: 0
            )
        }
    }

    func sendPing() {
        bleQueue.async { self.sendEncryptedCommandOnQueue(["type": "ping"]) }
    }

    func requestAudioProbe(method: Int = 1) {
        bleQueue.async { self.sendEncryptedCommandOnQueue(["type": "probe_audio", "method": method]) }
    }

    private func sendEncryptedCommandOnQueue(_ dict: [String: Any], allowDuringHandshake: Bool = false) {
        guard allowDuringHandshake || isAuthenticated else { return }
        guard let key = sharedKey, let p = peripheral, writeChar != nil else { return }

        var body = dict
        body["seq"] = txSeq
        txSeq += 1

        guard let plain = try? JSONSerialization.data(withJSONObject: body),
              let encrypted = BridgeCrypto.encrypt(keyData: key, plaintext: plain) else { return }

        let maxLen = p.maximumWriteValueLength(for: .withResponse)
        let chunks = framing.encode(payload: encrypted, flags: BleFraming.flagEncrypted, maxWriteLen: min(maxLen, 240))
        guard !chunks.isEmpty else { return }

        pendingWriteChunks.append(contentsOf: chunks)
        drainWriteQueueOnQueue()
    }

    private func drainWriteQueueOnQueue() {
        guard !writeInFlight, let p = peripheral, let wChar = writeChar, !pendingWriteChunks.isEmpty else { return }
        let nextChunk = pendingWriteChunks.removeFirst()
        writeInFlight = true
        p.writeValue(nextChunk, for: wChar, type: .withResponse)
    }

    private func handleCompleteBleMessageOnQueue(flags: UInt8, payload: Data) {
        if flags == BleFraming.flagPlaintext {
            guard let obj = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
                  obj["type"] as? String == "auth_challenge",
                  let serverNonce = obj["nonce"] as? String else { return }

            let cNonce = BridgeCrypto.randomNonceBase64Url(16)
            pendingClientNonce = cNonce
            txSeq = 1
            lastRxSeq = 0
            let devName = DispatchQueue.main.sync { UIDevice.current.name }
            sendEncryptedCommandOnQueue([
                "type": "auth_response",
                "nonce": serverNonce,
                "client_nonce": cNonce,
                "device_name": devName
            ], allowDuringHandshake: true)
            return
        }

        guard flags == BleFraming.flagEncrypted,
              let key = sharedKey,
              let plain = BridgeCrypto.decrypt(keyData: key, packet: payload),
              let json = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              let type = json["type"] as? String else { return }

        let seq = (json["seq"] as? NSNumber)?.int64Value ?? -1
        if seq <= lastRxSeq { return }
        lastRxSeq = seq

        if !isAuthenticated {
            guard type == "auth_ok",
                  let echoed = json["client_nonce"] as? String,
                  echoed == pendingClientNonce else { return }
            isAuthenticated = true
            pendingClientNonce = nil
            let host = json["audio_host"] as? String ?? ""
            let port = UInt16((json["audio_port"] as? NSNumber)?.intValue ?? 8119)
            let audioReady = json["call_audio_ready"] as? Bool ?? false
            let compReg = json["companion_registered"] as? Bool ?? false
            let compBound = json["companion_bound"] as? Bool ?? false
            let compEvents = (json["companion_events_count"] as? NSNumber)?.intValue ?? 0
            let model = json["android_model"] as? String ?? "Android"

            startHeartbeatOnQueue()
            DispatchQueue.main.async {
                self.isConnected = true
                if !host.isEmpty {
                    self.lastAudioHost = host
                    self.onAudioEndpointUpdated?(host, port)
                }
                self.lastAudioPort = port
                self.callAudioReady = audioReady
                self.companionRegistered = compReg
                self.companionBound = compBound
                self.companionEventsCount = compEvents
                self.androidModel = model
            }
            return
        }

        switch type {
        case "call_state":
            let state = json["state"] as? String ?? "IDLE"
            let number = json["number"] as? String ?? ""
            let callerName = json["caller_name"] as? String ?? ""
            let audioActive = json["audio_active"] as? Bool ?? false
            let host = json["audio_host"] as? String ?? ""
            let port = UInt16((json["audio_port"] as? NSNumber)?.intValue ?? 8119)

            DispatchQueue.main.async {
                let effectiveHost = host.isEmpty ? self.lastAudioHost : host
                if !host.isEmpty {
                    self.lastAudioHost = host
                    self.onAudioEndpointUpdated?(host, port)
                }
                self.lastAudioPort = port
                self.onCallEvent?(
                    CallEventPayload(
                        state: state,
                        number: number,
                        callerName: callerName,
                        audioActive: audioActive,
                        audioHost: effectiveHost,
                        audioPort: port
                    )
                )
            }

        case "sms_in":
            let from = json["from"] as? String ?? ""
            let senderName = json["sender_name"] as? String ?? ""
            let body = json["body"] as? String ?? ""
            DispatchQueue.main.async {
                self.messages.insert(
                    SmsMessageItem(from: from, senderName: senderName.isEmpty ? from : senderName, body: body, date: Date(), isOutgoing: false),
                    at: 0
                )
            }

        case "pong":
            let host = json["audio_host"] as? String ?? ""
            let port = UInt16((json["audio_port"] as? NSNumber)?.intValue ?? 8119)
            let ready = json["call_audio_ready"] as? Bool ?? false
            let compReg = json["companion_registered"] as? Bool ?? false
            let compBound = json["companion_bound"] as? Bool ?? false
            let compEvents = (json["companion_events_count"] as? NSNumber)?.intValue ?? 0
            DispatchQueue.main.async {
                if !host.isEmpty {
                    self.lastAudioHost = host
                    self.onAudioEndpointUpdated?(host, port)
                }
                self.lastAudioPort = port
                self.callAudioReady = ready
                self.companionRegistered = compReg
                self.companionBound = compBound
                self.companionEventsCount = compEvents
            }

        case "audio_probe_result":
            let summary = String(data: plain, encoding: .utf8) ?? ""
            DispatchQueue.main.async {
                self.lastProbeText = summary
            }

        default:
            break
        }
    }

    private func startHeartbeatOnQueue() {
        stopHeartbeatOnQueue()
        let timer = DispatchSource.makeTimerSource(queue: bleQueue)
        timer.schedule(deadline: .now() + 15.0, repeating: 15.0)
        timer.setEventHandler { [weak self] in
            self?.sendEncryptedCommandOnQueue(["type": "ping"])
        }
        heartbeatTimer = timer
        timer.resume()
    }

    private func stopHeartbeatOnQueue() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
    }
}

extension BleBridgeClient: CBCentralManagerDelegate, CBPeripheralDelegate {
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral],
           let restored = peripherals.first {
            self.peripheral = restored
            restored.delegate = self
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let on = central.state == .poweredOn
        DispatchQueue.main.async { self.bluetoothPoweredOn = on }
        if on { startScanOnQueue() }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        self.peripheral = peripheral
        peripheral.delegate = self
        central.stopScan()
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        framing.reset()
        isAuthenticated = false
        pendingWriteChunks.removeAll()
        writeInFlight = false
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        isAuthenticated = false
        framing.reset()
        pendingWriteChunks.removeAll()
        writeInFlight = false
        stopHeartbeatOnQueue()
        DispatchQueue.main.async {
            self.isConnected = false
        }
        if sharedKey != nil && central.state == .poweredOn {
            central.connect(peripheral, options: nil)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for svc in services where svc.uuid == serviceUUID {
            peripheral.discoverCharacteristics([Self.writeCharUUID, Self.notifyCharUUID], for: svc)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let chars = service.characteristics else { return }
        for ch in chars {
            if ch.uuid == Self.writeCharUUID {
                writeChar = ch
            } else if ch.uuid == Self.notifyCharUUID {
                notifyChar = ch
                peripheral.setNotifyValue(true, for: ch)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        writeInFlight = false
        drainWriteQueueOnQueue()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == Self.notifyCharUUID, let data = characteristic.value else { return }
        if let (flags, fullPayload) = framing.decodeChunk(data) {
            handleCompleteBleMessageOnQueue(flags: flags, payload: fullPayload)
        }
    }
}
