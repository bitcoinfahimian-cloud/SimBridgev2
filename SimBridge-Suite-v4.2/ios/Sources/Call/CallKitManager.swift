import Foundation
import CallKit
import AVFoundation

struct RecentCallItem: Identifiable {
    let id = UUID()
    let number: String
    let name: String
    let isOutgoing: Bool
    let date: Date
}

enum CallDirection {
    case incoming
    case outgoing
}

/**
 * Fix #4: Strictly separates incoming and outgoing CallKit call lifecycles:
 * - Outgoing calls use reportOutgoingCall(startedConnectingAt:) on DIALING and reportOutgoingCall(connectedAt:) once on ACTIVE.
 * - Incoming calls use reportNewIncomingCall on RINGING, NEVER call reportOutgoingCall on ACTIVE,
 *   and report .unanswered if ended before answering or .remoteEnded after answering.
 * - Audio session defaults to Earpiece (not Speakerphone) to avoid privacy leaks and acoustic echo.
 */
final class CallKitManager: NSObject, ObservableObject {
    @Published var activeCallNumber: String?
    @Published var activeCallName: String?
    @Published var isInCall: Bool = false
    @Published var recentCalls: [RecentCallItem] = []

    private let provider: CXProvider
    private let callController = CXCallController()

    private var activeCallUUID: UUID?
    private var activeDirection: CallDirection?
    private var hasReportedConnecting: Bool = false
    private var hasReportedConnected: Bool = false
    private var hasAnsweredIncoming: Bool = false

    private var pendingAudioHost: String = ""
    private var pendingAudioPort: UInt16 = 8119

    weak var bleClient: BleBridgeClient?
    let audioClient: UdpCallAudioClient

    init(audioClient: UdpCallAudioClient) {
        self.audioClient = audioClient
        let config = CXProviderConfiguration()
        config.localizedName = "SimBridge"
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
        config.supportedHandleTypes = [.phoneNumber]
        self.provider = CXProvider(configuration: config)
        super.init()
        self.provider.setDelegate(self, queue: .main)
    }

    func handleAndroidCallEvent(_ event: CallEventPayload) {
        if !event.audioHost.isEmpty {
            pendingAudioHost = event.audioHost
            pendingAudioPort = event.audioPort
            audioClient.updateEndpointIfChanged(host: event.audioHost, port: event.audioPort)
        }

        let displayName = event.callerName.isEmpty
            ? (event.number.isEmpty ? "Unknown" : event.number)
            : event.callerName

        switch event.state {
        case "RINGING":
            // Only report a new incoming call when no call is currently active
            guard activeCallUUID == nil else { return }
            let uuid = UUID()
            activeCallUUID = uuid
            activeDirection = .incoming
            hasAnsweredIncoming = false
            hasReportedConnected = false
            hasReportedConnecting = false

            let update = CXCallUpdate()
            update.remoteHandle = CXHandle(type: .phoneNumber, value: event.number.isEmpty ? "Unknown" : event.number)
            update.localizedCallerName = displayName
            update.hasVideo = false

            provider.reportNewIncomingCall(with: uuid, update: update) { [weak self] err in
                guard let self else { return }
                if err != nil {
                    self.resetCallState()
                } else {
                    self.activeCallNumber = event.number
                    self.activeCallName = displayName
                    self.isInCall = true
                    self.recentCalls.insert(
                        RecentCallItem(number: event.number, name: displayName, isOutgoing: false, date: Date()),
                        at: 0
                    )
                }
            }

        case "DIALING":
            if let uuid = activeCallUUID, activeDirection == .outgoing {
                if !hasReportedConnecting {
                    hasReportedConnecting = true
                    provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
                }
                if !event.callerName.isEmpty {
                    let update = CXCallUpdate()
                    update.localizedCallerName = event.callerName
                    provider.reportCall(with: uuid, updated: update)
                    activeCallName = event.callerName
                }
            } else if activeCallUUID == nil {
                // Outgoing call was dialed directly on the Android handset; mirror into CallKit as outgoing
                let uuid = UUID()
                activeCallUUID = uuid
                activeDirection = .outgoing
                hasReportedConnecting = true
                hasReportedConnected = false
                let handle = CXHandle(type: .phoneNumber, value: event.number.isEmpty ? "Unknown" : event.number)
                let startAction = CXStartCallAction(call: uuid, handle: handle)
                startAction.contactIdentifier = displayName
                callController.request(CXTransaction(action: startAction)) { [weak self] err in
                    if err == nil {
                        self?.provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
                    }
                }
            }

        case "ACTIVE":
            guard let uuid = activeCallUUID else { return }
            switch activeDirection {
            case .outgoing:
                if !hasReportedConnecting {
                    hasReportedConnecting = true
                    provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
                }
                if !hasReportedConnected {
                    hasReportedConnected = true
                    provider.reportOutgoingCall(with: uuid, connectedAt: Date())
                }
                if !event.callerName.isEmpty {
                    let update = CXCallUpdate()
                    update.localizedCallerName = event.callerName
                    provider.reportCall(with: uuid, updated: update)
                    activeCallName = event.callerName
                }
            case .incoming:
                // Incoming call answered (either from iPhone or Android); never call reportOutgoingCall here
                hasAnsweredIncoming = true
                if !event.callerName.isEmpty {
                    let update = CXCallUpdate()
                    update.localizedCallerName = event.callerName
                    provider.reportCall(with: uuid, updated: update)
                    activeCallName = event.callerName
                }
            case .none:
                break
            }

            isInCall = true
            if event.audioActive {
                startAudioStreamIfPossible()
            }

        case "IDLE":
            audioClient.stop()
            if let uuid = activeCallUUID {
                let reason: CXCallEndedReason = (activeDirection == .incoming && !hasAnsweredIncoming)
                    ? .unanswered
                    : .remoteEnded
                provider.reportCall(with: uuid, endedAt: Date(), reason: reason)
            }
            resetCallState()

        default:
            break
        }
    }

    func startOutgoingCall(to number: String) {
        let cleaned = number.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty, activeCallUUID == nil else { return }

        let uuid = UUID()
        activeCallUUID = uuid
        activeDirection = .outgoing
        hasReportedConnecting = false
        hasReportedConnected = false
        activeCallNumber = cleaned
        activeCallName = cleaned

        let handle = CXHandle(type: .phoneNumber, value: cleaned)
        let action = CXStartCallAction(call: uuid, handle: handle)
        let tx = CXTransaction(action: action)

        callController.request(tx) { [weak self] err in
            guard let self else { return }
            if err != nil {
                self.resetCallState()
            } else {
                self.recentCalls.insert(
                    RecentCallItem(number: cleaned, name: cleaned, isOutgoing: true, date: Date()),
                    at: 0
                )
            }
        }
    }

    private func resetCallState() {
        activeCallUUID = nil
        activeDirection = nil
        hasReportedConnecting = false
        hasReportedConnected = false
        hasAnsweredIncoming = false
        activeCallNumber = nil
        activeCallName = nil
        isInCall = false
    }

    private func configureVoiceAudioSession() {
        let session = AVAudioSession.sharedInstance()
        // Default to earpiece (NOT .defaultToSpeaker) so voice stays private and acoustic echo is minimized
        try? session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .allowBluetoothA2DP])
        try? session.setPreferredSampleRate(16000)
        try? session.setPreferredIOBufferDuration(0.02)
    }

    private func startAudioStreamIfPossible() {
        guard let key = KeychainStore.loadKey() else { return }
        let host = pendingAudioHost.isEmpty ? (bleClient?.lastAudioHost ?? "") : pendingAudioHost
        let port = pendingAudioPort > 0 ? pendingAudioPort : (bleClient?.lastAudioPort ?? 8119)
        guard !host.isEmpty else { return }
        audioClient.start(host: host, port: port, sharedKey: key)
    }
}

extension CallKitManager: CXProviderDelegate {
    func providerDidReset(_ provider: CXProvider) {
        audioClient.stop()
        resetCallState()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        configureVoiceAudioSession()
        // Only send dial over BLE if this outgoing call was initiated from iOS (not mirrored from Android)
        if !hasReportedConnecting {
            bleClient?.sendDial(number: action.handle.value)
        }
        isInCall = true
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        configureVoiceAudioSession()
        hasAnsweredIncoming = true
        bleClient?.sendAnswer()
        // Audio stream starts cleanly when Android confirms ACTIVE + audio_active=true (or immediately if host known)
        startAudioStreamIfPossible()
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        bleClient?.sendHangup()
        audioClient.stop()
        resetCallState()
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        bleClient?.sendMute(action.isMuted)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        bleClient?.sendDtmf(action.digits)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        audioClient.activateAfterAudioSession()
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        audioClient.stop()
    }
}
