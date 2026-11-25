import SwiftUI

@main
struct SimBridgeiOSApp: App {
    @StateObject private var bleClient: BleBridgeClient
    @StateObject private var audioClient: UdpCallAudioClient
    @StateObject private var callKitManager: CallKitManager

    init() {
        let ble = BleBridgeClient()
        let audio = UdpCallAudioClient()
        let ck = CallKitManager(audioClient: audio)
        ck.bleClient = ble
        ble.onCallEvent = { [weak ck] event in
            ck?.handleAndroidCallEvent(event)
        }
        ble.onAudioEndpointUpdated = { [weak audio] host, port in
            audio?.updateEndpointIfChanged(host: host, port: port)
        }
        _bleClient = StateObject(wrappedValue: ble)
        _audioClient = StateObject(wrappedValue: audio)
        _callKitManager = StateObject(wrappedValue: ck)
    }

    var body: some Scene {
        WindowGroup {
            ContentView(ble: bleClient, callKit: callKitManager, audio: audioClient)
        }
    }
}
