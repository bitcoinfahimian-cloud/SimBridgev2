import SwiftUI
import UIKit
import AVFoundation

enum IosLang: String, CaseIterable {
    case en = "en"
    case fa = "fa"
    var displayName: String { self == .en ? "English" : "فارسی" }
    var isRtl: Bool { self == .fa }
}

enum AppTab: Hashable {
    case calls, contacts, keypad, messages, settings
}

// MARK: - Localization helper

/// Tiny bilingual helper: `t("Calls", "تماس‌ها")`.
struct Loc {
    let isFa: Bool

    func callAsFunction(_ en: String, _ fa: String) -> String { isFa ? fa : en }

    var locale: Locale { Locale(identifier: isFa ? "fa_IR" : "en_US") }

    func time(_ date: Date) -> String {
        date.formatted(.dateTime.hour().minute().locale(locale))
    }

    /// iOS Recents / Messages style: time today, "Yesterday", weekday this week, otherwise a short date.
    func shortDate(_ date: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(date) { return time(date) }
        if cal.isDateInYesterday(date) { return self("Yesterday", "دیروز") }
        if let days = cal.dateComponents([.day], from: date, to: Date()).day, days < 7 {
            return date.formatted(.dateTime.weekday(.wide).locale(locale))
        }
        return date.formatted(.dateTime.day().month(.abbreviated).locale(locale))
    }
}

enum CallClock {
    static func format(from start: Date, to now: Date) -> String {
        let s = max(0, Int(now.timeIntervalSince(start)))
        if s >= 3600 {
            return String(format: "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        }
        return String(format: "%02d:%02d", s / 60, s % 60)
    }
}

// MARK: - People derived from the bridge's real data

/// Someone the bridge has seen in recent calls or SMS.
/// The model layer exposes no address book, so contacts are derived from that history.
struct BridgeContact: Identifiable, Hashable {
    let number: String
    let name: String
    let lastDate: Date
    var id: String { number }
    var hasName: Bool { name != number }
}

/// One SMS conversation, keyed by the remote number.
struct SmsThread: Identifiable {
    let number: String
    let name: String
    let lastMessage: SmsMessageItem
    var id: String { number }
}

private struct ContactSection: Identifiable {
    let key: String
    let items: [BridgeContact]
    var id: String { key }
}

enum BridgeDirectory {
    static func displayName(for number: String, calls: [RecentCallItem], messages: [SmsMessageItem]) -> String {
        if let name = messages.first(where: {
            $0.from == number && !$0.isOutgoing && !$0.senderName.isEmpty && $0.senderName != number
        })?.senderName {
            return name
        }
        if let name = calls.first(where: {
            $0.number == number && !$0.name.isEmpty && $0.name != number && $0.name != "Unknown"
        })?.name {
            return name
        }
        return number
    }

    static func contacts(calls: [RecentCallItem], messages: [SmsMessageItem]) -> [BridgeContact] {
        var latest: [String: Date] = [:]
        for call in calls where !call.number.isEmpty {
            latest[call.number] = max(latest[call.number] ?? .distantPast, call.date)
        }
        for msg in messages where !msg.from.isEmpty {
            latest[msg.from] = max(latest[msg.from] ?? .distantPast, msg.date)
        }
        return latest.map { entry in
            BridgeContact(number: entry.key,
                          name: displayName(for: entry.key, calls: calls, messages: messages),
                          lastDate: entry.value)
        }
    }

    static func threads(messages: [SmsMessageItem], calls: [RecentCallItem]) -> [SmsThread] {
        var newest: [String: SmsMessageItem] = [:]
        for msg in messages where !msg.from.isEmpty {
            if let current = newest[msg.from], current.date >= msg.date { continue }
            newest[msg.from] = msg
        }
        return newest.values
            .sorted { $0.date > $1.date }
            .map { msg in
                SmsThread(number: msg.from,
                          name: displayName(for: msg.from, calls: calls, messages: messages),
                          lastMessage: msg)
            }
    }
}

/// Connection state shown in the Settings card and the toolbar badge.
private struct BridgeStatus {
    let title: String
    let short: String
    let color: Color

    init(ble: BleBridgeClient, t: Loc) {
        if ble.isConnected {
            title = t("Connected", "متصل")
            short = t("Connected", "متصل")
            color = DS.green
        } else if !ble.bluetoothPoweredOn {
            title = t("Bluetooth Off", "بلوتوث خاموش")
            short = t("Offline", "قطع")
            color = DS.red
        } else if ble.isPaired {
            title = t("Searching…", "در حال جستجو…")
            short = t("Offline", "قطع")
            color = DS.orange
        } else {
            title = t("Not Paired", "جفت نشده")
            short = t("Not Paired", "جفت نشده")
            color = DS.gray
        }
    }
}

/// UI-only state of the in-call screen (kept in ContentView so it survives minimizing).
private struct CallUIState {
    var connectedAt: Date?
    var isMuted = false
    var isSpeaker = false
}

// MARK: - Root

struct ContentView: View {
    @ObservedObject var ble: BleBridgeClient
    @ObservedObject var callKit: CallKitManager
    @ObservedObject var audio: UdpCallAudioClient

    @AppStorage("ios_lang") private var langRaw: String = "en"
    @State private var selectedTab: AppTab = .calls
    @State private var showScanner = false
    @State private var dialNumber = ""
    @State private var messagePath: [String] = []
    @State private var hideTabBar = false
    @State private var didPickInitialTab = false
    @State private var callMinimized = false
    @State private var callUI = CallUIState()

    private var lang: IosLang { IosLang(rawValue: langRaw) ?? .en }
    private var t: Loc { Loc(isFa: lang == .fa) }

    private var tabItems: [GlassTabItem<AppTab>] {
        [
            GlassTabItem(id: .calls, title: t("Recents", "اخیر"), systemImage: "clock.fill"),
            GlassTabItem(id: .contacts, title: t("Contacts", "مخاطبین"), systemImage: "person.crop.circle.fill"),
            GlassTabItem(id: .keypad, title: t("Keypad", "صفحه‌کلید"), systemImage: "circle.grid.3x3.fill"),
            GlassTabItem(id: .messages, title: t("Messages", "پیام‌ها"), systemImage: "message.fill"),
            GlassTabItem(id: .settings, title: t("Settings", "تنظیمات"), systemImage: "gearshape.fill")
        ]
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            tabContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            VStack(spacing: 10) {
                if callKit.isInCall && callMinimized {
                    callPill
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                if !hideTabBar {
                    GlassTabBar(selection: $selectedTab, items: tabItems)
                        .padding(.horizontal, 18)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .padding(.bottom, hideTabBar ? 64 : 2)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .ignoresSafeArea(.keyboard, edges: .bottom)

            if callKit.isInCall && !callMinimized {
                ActiveCallView(ble: ble, callKit: callKit, audio: audio, state: $callUI, t: t) {
                    callMinimized = true
                }
                .transition(.move(edge: .bottom))
                .zIndex(2)
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.86), value: callKit.isInCall)
        .animation(.spring(response: 0.4, dampingFraction: 0.86), value: callMinimized)
        .animation(.easeInOut(duration: 0.2), value: hideTabBar)
        .environment(\.tabBarHidden, $hideTabBar)
        .tint(DS.accent)
        .preferredColorScheme(.light)
        .environment(\.layoutDirection, lang.isRtl ? .rightToLeft : .leftToRight)
        .onAppear {
            guard !didPickInitialTab else { return }
            didPickInitialTab = true
            if !ble.isPaired { selectedTab = .settings }
        }
        .onChange(of: callKit.isInCall) { _, inCall in
            if !inCall && callUI.isSpeaker {
                try? AVAudioSession.sharedInstance().overrideOutputAudioPort(.none)
            }
            callUI = CallUIState()
            callUI.connectedAt = (inCall && audio.isStreaming) ? Date() : nil
            callMinimized = false
        }
        .onChange(of: audio.isStreaming) { _, streaming in
            if streaming && callKit.isInCall && callUI.connectedAt == nil {
                callUI.connectedAt = Date()
            }
        }
        .sheet(isPresented: $showScanner) {
            NavigationStack {
                QrScannerView { code in
                    _ = ble.pairFromQrPayload(code)
                    showScanner = false
                }
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle(t("Scan Android QR Code", "اسکن کد QR اندروید"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(t("Close", "بستن")) { showScanner = false }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var tabContent: some View {
        switch selectedTab {
        case .calls:
            RecentsView(ble: ble, callKit: callKit, t: t, onMessage: openThread)
        case .contacts:
            ContactsView(ble: ble, callKit: callKit, t: t, onMessage: openThread)
        case .keypad:
            KeypadView(ble: ble, callKit: callKit, number: $dialNumber, t: t)
        case .messages:
            MessagesView(ble: ble, callKit: callKit, path: $messagePath, t: t)
        case .settings:
            SettingsView(ble: ble, audio: audio, langRaw: $langRaw, showScanner: $showScanner, t: t)
        }
    }

    private func openThread(_ number: String) {
        messagePath = [number]
        selectedTab = .messages
    }

    /// Green "return to call" pill shown while the call screen is minimized.
    private var callPill: some View {
        Button {
            callMinimized = false
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "phone.fill")
                Text(callKit.activeCallName ?? callKit.activeCallNumber ?? t("Call", "تماس"))
                    .lineLimit(1)
                if let start = callUI.connectedAt {
                    TimelineView(.periodic(from: start, by: 1)) { context in
                        Text(CallClock.format(from: start, to: context.date))
                            .monospacedDigit()
                    }
                }
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.white)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .background(DS.green, in: Capsule())
            .shadow(color: Color.black.opacity(0.15), radius: 10, x: 0, y: 4)
        }
        .buttonStyle(.plain)
        .accessibilityHint(t("Returns to the call screen", "بازگشت به صفحهٔ تماس"))
    }
}

// MARK: - Shared bits

private struct ConnectionBadge: View {
    @ObservedObject var ble: BleBridgeClient
    let t: Loc

    var body: some View {
        let status = BridgeStatus(ble: ble, t: t)
        HStack(spacing: 5) {
            Circle().fill(status.color).frame(width: 8, height: 8)
            Text(status.short)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(DS.secondaryLabel)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Small round tinted icon button used in list rows.
private struct RowIconButton: View {
    let systemName: String
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(DS.accent)
                .frame(width: 34, height: 34)
                .background(DS.accent.opacity(0.12), in: Circle())
        }
        .buttonStyle(.borderless)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }
}

/// Digit + letters label for keypad keys.
private struct DialKeyLabel: View {
    let digit: String
    let letters: String
    var size: CGFloat = DS.keySize
    var foreground: Color = DS.label

    var body: some View {
        VStack(spacing: -2) {
            Text(digit)
                .font(.system(size: size * 0.44, weight: .regular))
            if !letters.isEmpty {
                Text(letters)
                    .font(.system(size: size * 0.13, weight: .bold))
                    .tracking(1.5)
                    .opacity(0.7)
            }
        }
        .foregroundStyle(foreground)
        .frame(width: size, height: size)
    }
}

private let dialKeys: [(digit: String, letters: String)] = [
    ("1", ""), ("2", "ABC"), ("3", "DEF"),
    ("4", "GHI"), ("5", "JKL"), ("6", "MNO"),
    ("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ"),
    ("*", ""), ("0", "+"), ("#", "")
]

// MARK: - Recents

private struct RecentsView: View {
    @ObservedObject var ble: BleBridgeClient
    @ObservedObject var callKit: CallKitManager
    let t: Loc
    let onMessage: (String) -> Void

    var body: some View {
        NavigationStack {
            Group {
                if callKit.recentCalls.isEmpty {
                    ContentUnavailableView(
                        t("No Recents", "تماس اخیری نیست"),
                        systemImage: "clock",
                        description: Text(t("Calls you make or receive through your Android SIM appear here.",
                                            "تماس‌هایی که با سیم‌کارت اندروید می‌گیرید یا دریافت می‌کنید اینجا نمایش داده می‌شوند."))
                    )
                } else {
                    List {
                        ForEach(callKit.recentCalls) { item in
                            recentRow(item)
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .tabBarSafeArea()
            .navigationTitle(t("Recents", "اخیر"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    ConnectionBadge(ble: ble, t: t)
                }
            }
        }
    }

    private func recentRow(_ item: RecentCallItem) -> some View {
        let name = item.number.isEmpty
            ? item.name
            : BridgeDirectory.displayName(for: item.number, calls: callKit.recentCalls, messages: ble.messages)
        let canCall = ble.isConnected && !item.number.isEmpty

        return HStack(spacing: 12) {
            AvatarView(name: name, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(DS.label)
                    .lineLimit(1)
                HStack(spacing: 4) {
                    Image(systemName: item.isOutgoing ? "phone.arrow.up.right.fill" : "phone.arrow.down.left.fill")
                        .font(.caption2)
                    Text(item.isOutgoing ? t("Outgoing", "خروجی") : t("Incoming", "ورودی"))
                }
                .font(.subheadline)
                .foregroundStyle(DS.secondaryLabel)
            }
            Spacer(minLength: 8)
            Text(t.shortDate(item.date))
                .font(.subheadline)
                .foregroundStyle(DS.secondaryLabel)
            Image(systemName: "phone.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(canCall ? DS.accent : DS.tertiaryLabel)
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
        .onTapGesture {
            if canCall { callKit.startOutgoingCall(to: item.number) }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            if !item.number.isEmpty {
                Button {
                    onMessage(item.number)
                } label: {
                    Label(t("Message", "پیام"), systemImage: "message.fill")
                }
                .tint(DS.accent)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - Contacts

private struct ContactsView: View {
    @ObservedObject var ble: BleBridgeClient
    @ObservedObject var callKit: CallKitManager
    let t: Loc
    let onMessage: (String) -> Void

    @State private var query = ""

    private var contacts: [BridgeContact] {
        let all = BridgeDirectory.contacts(calls: callKit.recentCalls, messages: ble.messages)
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return all }
        return all.filter { $0.name.localizedCaseInsensitiveContains(q) || $0.number.contains(q) }
    }

    private var sections: [ContactSection] {
        let grouped = Dictionary(grouping: contacts) { Self.indexKey(for: $0) }
        return grouped.keys
            .sorted(by: Self.keyOrder)
            .map { ContactSection(key: $0, items: grouped[$0] ?? []) }
    }

    private static func indexKey(for contact: BridgeContact) -> String {
        guard contact.hasName, let first = contact.name.first, first.isLetter else { return "#" }
        return String(first).uppercased()
    }

    private static func keyOrder(_ a: String, _ b: String) -> Bool {
        if a == "#" { return false }
        if b == "#" { return true }
        return a.localizedCompare(b) == .orderedAscending
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    ForEach(sections) { section in
                        Section {
                            ForEach(section.items) { contact in
                                contactRow(contact)
                            }
                        } header: {
                            Text(section.key)
                        }
                    }
                }
                .listStyle(.plain)
                .overlay {
                    if contacts.isEmpty {
                        if query.isEmpty {
                            ContentUnavailableView(
                                t("No Contacts Yet", "هنوز مخاطبی نیست"),
                                systemImage: "person.crop.circle",
                                description: Text(t("People you call or text through your Android phone appear here.",
                                                    "افرادی که از طریق گوشی اندروید با آن‌ها تماس می‌گیرید یا پیامک می‌دهید اینجا نمایش داده می‌شوند."))
                            )
                        } else {
                            ContentUnavailableView.search(text: query)
                        }
                    }
                }
                .overlay(alignment: .trailing) {
                    if sections.count > 1 && query.isEmpty {
                        sectionIndex(sections) { section in
                            if let first = section.items.first {
                                withAnimation { proxy.scrollTo(first.id, anchor: .top) }
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: t("Search", "جستجو"))
            .tabBarSafeArea()
            .navigationTitle(t("Contacts", "مخاطبین"))
        }
    }

    private func contactRow(_ contact: BridgeContact) -> some View {
        HStack(spacing: 12) {
            AvatarView(name: contact.name, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(contact.name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(DS.label)
                    .lineLimit(1)
                if contact.hasName {
                    Text(contact.number)
                        .font(.subheadline)
                        .foregroundStyle(DS.secondaryLabel)
                }
            }
            Spacer(minLength: 8)
            RowIconButton(systemName: "message.fill") { onMessage(contact.number) }
            RowIconButton(systemName: "phone.fill", enabled: ble.isConnected) {
                callKit.startOutgoingCall(to: contact.number)
            }
        }
        .padding(.vertical, 2)
        .padding(.trailing, 14)
    }

    /// A–Z jump strip on the trailing edge, like the iOS Contacts index.
    private func sectionIndex(_ sections: [ContactSection], onSelect: @escaping (ContactSection) -> Void) -> some View {
        VStack(spacing: 1) {
            ForEach(sections) { section in
                Button {
                    onSelect(section)
                } label: {
                    Text(section.key)
                        .font(.system(size: 11, weight: .semibold))
                        .frame(width: 18, height: 15)
                }
                .buttonStyle(.plain)
                .foregroundStyle(DS.accent)
            }
        }
        .padding(.vertical, 6)
        .padding(.trailing, 2)
    }
}

// MARK: - Keypad

private struct KeypadView: View {
    @ObservedObject var ble: BleBridgeClient
    @ObservedObject var callKit: CallKitManager
    @Binding var number: String
    let t: Loc

    private var trimmed: String { number.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canCall: Bool { ble.isConnected && !trimmed.isEmpty }

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 12)

            Text(number.isEmpty ? " " : number)
                .font(.system(size: 36, weight: .regular))
                .monospacedDigit()
                .foregroundStyle(DS.label)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .frame(height: 48)
                .padding(.horizontal, 32)
                .environment(\.layoutDirection, .leftToRight)

            Text(ble.isConnected
                 ? t("Calls go through \(ble.androidModel)", "تماس از طریق \(ble.androidModel)")
                 : t("Not connected to your Android phone", "به گوشی اندروید متصل نیست"))
                .font(.footnote)
                .foregroundStyle(ble.isConnected ? DS.secondaryLabel : DS.orange)
                .lineLimit(1)
                .frame(height: 20)

            Spacer(minLength: 16)

            VStack(spacing: 14) {
                ForEach(0..<4, id: \.self) { row in
                    HStack(spacing: 24) {
                        ForEach(0..<3, id: \.self) { col in
                            let key = dialKeys[row * 3 + col]
                            Button {
                                if number.count < 32 { number.append(key.digit) }
                            } label: {
                                DialKeyLabel(digit: key.digit, letters: key.letters)
                            }
                            .buttonStyle(RoundKeyButtonStyle())
                            .accessibilityLabel(key.digit)
                        }
                    }
                }

                HStack(spacing: 24) {
                    Color.clear.frame(width: DS.keySize, height: DS.keySize)

                    Button {
                        callKit.startOutgoingCall(to: trimmed)
                    } label: {
                        Image(systemName: "phone.fill")
                            .font(.system(size: 30, weight: .semibold))
                            .foregroundStyle(Color.white)
                            .frame(width: DS.keySize, height: DS.keySize)
                    }
                    .buttonStyle(RoundKeyButtonStyle(fill: DS.green, pressedFill: DS.green.opacity(0.7)))
                    .disabled(!canCall)
                    .opacity(canCall ? 1 : 0.45)
                    .accessibilityLabel(t("Call", "تماس"))

                    Button {
                        if !number.isEmpty { number.removeLast() }
                    } label: {
                        Image(systemName: "delete.left.fill")
                            .font(.system(size: 24, weight: .regular))
                            .foregroundStyle(DS.gray)
                            .frame(width: DS.keySize, height: DS.keySize)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .simultaneousGesture(LongPressGesture(minimumDuration: 0.6).onEnded { _ in number = "" })
                    .opacity(number.isEmpty ? 0 : 1)
                    .disabled(number.isEmpty)
                    .accessibilityLabel(t("Delete", "حذف"))
                }
                .padding(.top, 4)
            }
            .environment(\.layoutDirection, .leftToRight)

            Spacer(minLength: 12)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(DS.plainBackground.ignoresSafeArea())
        .sensoryFeedback(.impact(weight: .light), trigger: number)
        .tabBarSafeArea()
    }
}

// MARK: - Messages

private struct MessagesView: View {
    @ObservedObject var ble: BleBridgeClient
    @ObservedObject var callKit: CallKitManager
    @Binding var path: [String]
    let t: Loc

    @State private var query = ""
    @State private var showCompose = false
    @State private var composeNumber = ""

    private var threads: [SmsThread] {
        let all = BridgeDirectory.threads(messages: ble.messages, calls: callKit.recentCalls)
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return all }
        return all.filter {
            $0.name.localizedCaseInsensitiveContains(q)
                || $0.number.contains(q)
                || $0.lastMessage.body.localizedCaseInsensitiveContains(q)
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                ForEach(threads) { thread in
                    NavigationLink(value: thread.number) {
                        threadRow(thread)
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if threads.isEmpty {
                    if query.isEmpty {
                        ContentUnavailableView(
                            t("No Messages", "پیامی نیست"),
                            systemImage: "message",
                            description: Text(t("SMS sent or received on your Android SIM appear here.",
                                                "پیامک‌های ارسالی و دریافتی سیم‌کارت اندروید اینجا نمایش داده می‌شوند."))
                        )
                    } else {
                        ContentUnavailableView.search(text: query)
                    }
                }
            }
            .searchable(text: $query, prompt: t("Search", "جستجو"))
            .tabBarSafeArea()
            .navigationTitle(t("Messages", "پیام‌ها"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        composeNumber = ""
                        showCompose = true
                    } label: {
                        Image(systemName: "square.and.pencil")
                    }
                    .accessibilityLabel(t("New Message", "پیام جدید"))
                }
            }
            .navigationDestination(for: String.self) { number in
                ChatView(ble: ble, callKit: callKit, number: number, t: t)
            }
            .alert(t("New Message", "پیام جدید"), isPresented: $showCompose) {
                TextField(t("Phone number", "شماره تلفن"), text: $composeNumber)
                    .keyboardType(.phonePad)
                Button(t("Cancel", "انصراف"), role: .cancel) {}
                Button(t("Next", "بعدی")) {
                    let n = composeNumber.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !n.isEmpty { path.append(n) }
                }
            }
        }
    }

    private func threadRow(_ thread: SmsThread) -> some View {
        HStack(alignment: .top, spacing: 12) {
            AvatarView(name: thread.name, size: 46)
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline) {
                    Text(thread.name)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(DS.label)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Text(t.shortDate(thread.lastMessage.date))
                        .font(.subheadline)
                        .foregroundStyle(DS.secondaryLabel)
                }
                Text((thread.lastMessage.isOutgoing ? t("You: ", "شما: ") : "") + thread.lastMessage.body)
                    .font(.subheadline)
                    .foregroundStyle(DS.secondaryLabel)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct ChatView: View {
    @ObservedObject var ble: BleBridgeClient
    @ObservedObject var callKit: CallKitManager
    let number: String
    let t: Loc

    @Environment(\.tabBarHidden) private var tabBarHidden
    @State private var draft = ""
    @FocusState private var composerFocused: Bool

    private var items: [SmsMessageItem] {
        ble.messages.filter { $0.from == number }.sorted { $0.date < $1.date }
    }

    private var name: String {
        BridgeDirectory.displayName(for: number, calls: callKit.recentCalls, messages: ble.messages)
    }

    private var canSend: Bool {
        ble.isConnected && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 4) {
                    chatHeader
                    ForEach(Array(items.enumerated()), id: \.element.id) { index, msg in
                        if showsTimestamp(at: index) {
                            Text("\(t.shortDate(msg.date)) · \(t.time(msg.date))")
                                .font(.caption2.weight(.medium))
                                .foregroundStyle(DS.secondaryLabel)
                                .padding(.top, 12)
                                .padding(.bottom, 4)
                        }
                        MessageBubble(message: msg)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: items.count) { _, _ in
                if let last = items.last {
                    withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
        }
        .background(DS.plainBackground)
        .safeAreaInset(edge: .bottom, spacing: 0) { composer }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    callKit.startOutgoingCall(to: number)
                } label: {
                    Image(systemName: "phone.fill")
                }
                .disabled(!ble.isConnected)
                .accessibilityLabel(t("Call", "تماس"))
            }
        }
        .onAppear { tabBarHidden.wrappedValue = true }
        .onDisappear { tabBarHidden.wrappedValue = false }
    }

    private var chatHeader: some View {
        VStack(spacing: 6) {
            AvatarView(name: name, size: 56)
            Text(name)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(DS.label)
            if name != number {
                Text(number)
                    .font(.caption)
                    .foregroundStyle(DS.secondaryLabel)
            }
            Text(t("Text Message · Android SIM", "پیامک · سیم‌کارت اندروید"))
                .font(.caption2)
                .foregroundStyle(DS.tertiaryLabel)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
    }

    private func showsTimestamp(at index: Int) -> Bool {
        guard index > 0 else { return true }
        return items[index].date.timeIntervalSince(items[index - 1].date) > 15 * 60
    }

    private var composer: some View {
        VStack(spacing: 6) {
            if !ble.isConnected {
                Text(t("Not connected — messages can't be sent right now.", "متصل نیست؛ فعلاً امکان ارسال پیامک نیست."))
                    .font(.caption)
                    .foregroundStyle(DS.orange)
            }
            HStack(alignment: .bottom, spacing: 8) {
                TextField(t("Text Message", "پیامک"), text: $draft, axis: .vertical)
                    .lineLimit(1...5)
                    .focused($composerFocused)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(DS.plainBackground, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .strokeBorder(DS.separator, lineWidth: 0.5)
                    )
                Button(action: send) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 32))
                        .foregroundStyle(canSend ? DS.accent : DS.gray.opacity(0.5))
                }
                .disabled(!canSend)
                .accessibilityLabel(t("Send", "ارسال"))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private func send() {
        let body = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard ble.isConnected, !body.isEmpty else { return }
        ble.sendSms(to: number, body: body)
        draft = ""
    }
}

private struct MessageBubble: View {
    let message: SmsMessageItem

    var body: some View {
        HStack {
            if message.isOutgoing { Spacer(minLength: 56) }
            Text(message.body)
                .font(.body)
                .foregroundStyle(message.isOutgoing ? Color.white : DS.label)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(message.isOutgoing ? DS.accent : DS.incomingBubble,
                            in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .textSelection(.enabled)
            if !message.isOutgoing { Spacer(minLength: 56) }
        }
    }
}

// MARK: - Active call

private struct ActiveCallView: View {
    @ObservedObject var ble: BleBridgeClient
    @ObservedObject var callKit: CallKitManager
    @ObservedObject var audio: UdpCallAudioClient
    @Binding var state: CallUIState
    let t: Loc
    let onMinimize: () -> Void

    @State private var showKeypad = false
    @State private var dtmf = ""

    private var number: String { callKit.activeCallNumber ?? "" }
    private var name: String {
        let n = callKit.activeCallName ?? ""
        if !n.isEmpty { return n }
        return number.isEmpty ? t("Unknown", "ناشناس") : number
    }

    var body: some View {
        ZStack {
            LinearGradient(colors: [DS.accent.opacity(0.14), DS.groupedBackground, DS.groupedBackground],
                           startPoint: .top, endPoint: .bottom)
                .background(DS.groupedBackground)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                HStack {
                    Button(action: onMinimize) {
                        Image(systemName: "chevron.down")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(DS.label)
                            .frame(width: 40, height: 40)
                    }
                    .buttonStyle(RoundKeyButtonStyle(fill: Color.white.opacity(0.8), pressedFill: DS.fill))
                    .accessibilityLabel(t("Minimize", "کوچک کردن"))
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)

                VStack(spacing: 8) {
                    AvatarView(name: name, size: showKeypad ? 72 : 112)
                    Text(name)
                        .font(.system(size: showKeypad ? 26 : 32, weight: .regular))
                        .foregroundStyle(DS.label)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    if !number.isEmpty && name != number {
                        Text(number)
                            .font(.title3)
                            .foregroundStyle(DS.secondaryLabel)
                    }
                    statusLine
                    Text(t("via \(ble.androidModel)", "از طریق \(ble.androidModel)"))
                        .font(.footnote)
                        .foregroundStyle(DS.tertiaryLabel)
                }
                .padding(.horizontal, 24)
                .padding(.top, 12)

                Spacer(minLength: 16)

                if showKeypad {
                    inCallKeypad
                } else {
                    controls
                }

                Spacer(minLength: 24)

                ZStack {
                    Button {
                        ble.sendHangup()
                    } label: {
                        Image(systemName: "phone.down.fill")
                            .font(.system(size: 30, weight: .semibold))
                            .foregroundStyle(Color.white)
                            .frame(width: DS.keySize, height: DS.keySize)
                    }
                    .buttonStyle(RoundKeyButtonStyle(fill: DS.red, pressedFill: DS.red.opacity(0.7)))
                    .accessibilityLabel(t("End Call", "پایان تماس"))

                    if showKeypad {
                        HStack {
                            Spacer()
                            Button(t("Hide", "پنهان")) { withAnimation { showKeypad = false } }
                                .font(.body.weight(.medium))
                                .foregroundStyle(DS.label)
                                .frame(width: DS.keySize)
                        }
                        .padding(.horizontal, 40)
                    }
                }
                .padding(.bottom, 28)
            }
        }
        .environment(\.layoutDirection, .leftToRight)
    }

    @ViewBuilder
    private var statusLine: some View {
        if showKeypad && !dtmf.isEmpty {
            Text(dtmf)
                .font(.title2.monospacedDigit())
                .foregroundStyle(DS.label)
                .lineLimit(1)
                .truncationMode(.head)
        } else if let start = state.connectedAt {
            TimelineView(.periodic(from: start, by: 1)) { context in
                Text(CallClock.format(from: start, to: context.date))
                    .font(.title3.monospacedDigit())
                    .foregroundStyle(DS.secondaryLabel)
            }
        } else {
            Text(audio.isReconnecting ? t("Reconnecting audio…", "اتصال دوبارهٔ صدا…") : t("Connecting…", "در حال اتصال…"))
                .font(.title3)
                .foregroundStyle(DS.secondaryLabel)
        }
    }

    private var controls: some View {
        HStack(spacing: 28) {
            control(symbol: state.isMuted ? "mic.slash.fill" : "mic.slash",
                    title: t("Mute", "بی‌صدا"),
                    active: state.isMuted) {
                state.isMuted.toggle()
                ble.sendMute(state.isMuted)
            }
            control(symbol: "circle.grid.3x3.fill",
                    title: t("Keypad", "صفحه‌کلید"),
                    active: false) {
                withAnimation { showKeypad = true }
            }
            control(symbol: "speaker.wave.2.fill",
                    title: t("Speaker", "بلندگو"),
                    active: state.isSpeaker) {
                state.isSpeaker.toggle()
                try? AVAudioSession.sharedInstance().overrideOutputAudioPort(state.isSpeaker ? .speaker : .none)
            }
        }
    }

    private func control(symbol: String, title: String, active: Bool, action: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Button(action: action) {
                Image(systemName: symbol)
                    .font(.system(size: 26, weight: .medium))
                    .foregroundStyle(active ? Color.white : DS.label)
                    .frame(width: DS.callControlSize, height: DS.callControlSize)
            }
            .buttonStyle(RoundKeyButtonStyle(fill: active ? DS.label : Color.white,
                                             pressedFill: active ? DS.label.opacity(0.7) : DS.fill))
            .shadow(color: Color.black.opacity(active ? 0 : 0.06), radius: 6, x: 0, y: 2)
            .accessibilityLabel(title)
            .accessibilityAddTraits(active ? .isSelected : [])
            Text(title)
                .font(.footnote.weight(.medium))
                .foregroundStyle(DS.label)
        }
    }

    private var inCallKeypad: some View {
        let size: CGFloat = 70
        return VStack(spacing: 12) {
            ForEach(0..<4, id: \.self) { row in
                HStack(spacing: 22) {
                    ForEach(0..<3, id: \.self) { col in
                        let key = dialKeys[row * 3 + col]
                        Button {
                            dtmf.append(key.digit)
                            ble.sendDtmf(key.digit)
                        } label: {
                            DialKeyLabel(digit: key.digit, letters: key.letters, size: size)
                        }
                        .buttonStyle(RoundKeyButtonStyle(fill: Color.white, pressedFill: DS.fill))
                        .accessibilityLabel(key.digit)
                    }
                }
            }
        }
        .sensoryFeedback(.impact(weight: .light), trigger: dtmf)
    }
}

// MARK: - Settings

private struct SettingsView: View {
    @ObservedObject var ble: BleBridgeClient
    @ObservedObject var audio: UdpCallAudioClient
    @Binding var langRaw: String
    @Binding var showScanner: Bool
    let t: Loc

    @State private var manualHost = ""
    @State private var confirmUnpair = false

    private var streamStatus: String {
        if audio.isStreaming { return t("Streaming", "در حال پخش") }
        if audio.isReconnecting { return t("Reconnecting", "اتصال دوباره") }
        return t("Idle", "غیرفعال")
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ConnectionCard(ble: ble, audio: audio, t: t)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }

                Section {
                    Button {
                        showScanner = true
                    } label: {
                        SettingsLabel(icon: "qrcode.viewfinder", color: DS.accent,
                                      title: ble.isPaired
                                          ? t("Pair Again (Scan QR)", "جفت‌سازی دوباره (اسکن QR)")
                                          : t("Pair with Android (Scan QR)", "جفت‌سازی با اندروید (اسکن QR)"))
                    }
                } footer: {
                    Text(t("Open SimBridge on your Android phone and scan the QR code it shows.",
                           "SimBridge را روی گوشی اندروید باز کنید و کد QR آن را اسکن کنید."))
                }

                Section(t("General", "عمومی")) {
                    Picker(selection: $langRaw) {
                        ForEach(IosLang.allCases, id: \.rawValue) { l in
                            Text(l.displayName).tag(l.rawValue)
                        }
                    } label: {
                        SettingsLabel(icon: "globe", color: DS.accent, title: t("Language", "زبان"))
                    }
                }

                Section {
                    LabeledContent {
                        Text("\(ble.lastAudioHost.isEmpty ? "–" : ble.lastAudioHost):\(ble.lastAudioPort)")
                            .font(.subheadline.monospaced())
                    } label: {
                        SettingsLabel(icon: "wifi", color: DS.accent, title: t("Endpoint", "نقطهٔ اتصال"))
                    }
                    HStack(spacing: 12) {
                        SettingsIcon(systemName: "pencil", color: DS.gray)
                        TextField(t("Manual host IP", "IP دستی میزبان"), text: $manualHost)
                            .keyboardType(.numbersAndPunctuation)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .environment(\.layoutDirection, .leftToRight)
                        Button(t("Set", "ثبت")) {
                            if !manualHost.isEmpty { ble.lastAudioHost = manualHost }
                        }
                        .buttonStyle(.borderless)
                        .disabled(manualHost.isEmpty)
                    }
                    LabeledContent {
                        Text(streamStatus)
                    } label: {
                        SettingsLabel(icon: "waveform", color: DS.green, title: t("Stream", "جریان صدا"))
                    }
                    LabeledContent {
                        Text("↓ \(audio.rxPackets)   ↑ \(audio.txPackets)")
                            .font(.subheadline.monospacedDigit())
                    } label: {
                        SettingsLabel(icon: "arrow.up.arrow.down", color: DS.teal, title: t("Packets", "بسته‌ها"))
                    }
                    LabeledContent {
                        Text("\(audio.reconnectCount)")
                            .monospacedDigit()
                    } label: {
                        SettingsLabel(icon: "arrow.clockwise", color: DS.orange, title: t("Reconnects", "اتصال‌های دوباره"))
                    }
                } header: {
                    Text(t("Call Audio", "صدای تماس"))
                } footer: {
                    Text(t("Encrypted voice over local Wi-Fi or Hotspot (UDP \(ble.lastAudioPort)).",
                           "صدای رمزنگاری‌شده روی Wi-Fi یا هات‌اسپات محلی (UDP \(ble.lastAudioPort))."))
                }

                Section(t("Diagnostics", "عیب‌یابی")) {
                    LabeledContent {
                        Text(ble.companionRegistered
                             ? (ble.companionBound ? t("Bound", "متصل") : t("Registered", "ثبت‌شده"))
                             : t("Not registered", "ثبت نشده"))
                    } label: {
                        SettingsLabel(icon: "app.connected.to.app.below.fill", color: .purple,
                                      title: t("Companion Service", "سرویس همراه"))
                    }
                    LabeledContent {
                        Text("\(ble.companionEventsCount)")
                            .monospacedDigit()
                    } label: {
                        SettingsLabel(icon: "list.bullet", color: DS.indigo, title: t("Call Events", "رویدادهای تماس"))
                    }
                    Button {
                        ble.sendPing()
                    } label: {
                        SettingsLabel(icon: "dot.radiowaves.left.and.right", color: DS.green,
                                      title: t("Send Ping", "ارسال پینگ"))
                    }
                    .disabled(!ble.isConnected)
                    Button {
                        ble.requestAudioProbe(method: 1)
                    } label: {
                        SettingsLabel(icon: "waveform.path.ecg", color: DS.orange,
                                      title: t("Run Android Audio Probe", "تست سخت‌افزاری صدای اندروید"))
                    }
                    .disabled(!ble.isConnected)
                    if !ble.lastProbeText.isEmpty {
                        Text(ble.lastProbeText)
                            .font(.caption.monospaced())
                            .foregroundStyle(DS.secondaryLabel)
                            .textSelection(.enabled)
                            .environment(\.layoutDirection, .leftToRight)
                    }
                }

                Section {
                    Button(role: .destructive) {
                        confirmUnpair = true
                    } label: {
                        SettingsLabel(icon: "trash.fill", color: DS.red,
                                      title: t("Unpair & Clear Key", "حذف جفت‌سازی و کلید"),
                                      titleColor: DS.red)
                    }
                    .disabled(!ble.isPaired)
                } footer: {
                    Text(t("Removes the pairing key from this iPhone's Keychain.",
                           "کلید جفت‌سازی از Keychain این آیفون پاک می‌شود."))
                }
            }
            .listStyle(.insetGrouped)
            .tabBarSafeArea()
            .navigationTitle(t("Settings", "تنظیمات"))
            .confirmationDialog(t("Unpair from Android?", "جفت‌سازی حذف شود؟"),
                                isPresented: $confirmUnpair,
                                titleVisibility: .visible) {
                Button(t("Unpair", "حذف جفت‌سازی"), role: .destructive) { ble.unpair() }
                Button(t("Cancel", "انصراف"), role: .cancel) {}
            }
        }
    }
}

/// Connection status card at the top of Settings.
private struct ConnectionCard: View {
    @ObservedObject var ble: BleBridgeClient
    @ObservedObject var audio: UdpCallAudioClient
    let t: Loc

    var body: some View {
        let status = BridgeStatus(ble: ble, t: t)
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
                Image(systemName: "smartphone")
                    .font(.system(size: 26, weight: .regular))
                    .foregroundStyle(DS.accent)
                    .frame(width: 56, height: 56)
                    .background(DS.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(ble.isPaired ? ble.androidModel : t("No Android phone paired", "هیچ گوشی اندرویدی جفت نشده"))
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(DS.label)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Text(t("Android SIM bridge · Bluetooth LE", "پل سیم‌کارت اندروید · بلوتوث کم‌مصرف"))
                        .font(.subheadline)
                        .foregroundStyle(DS.secondaryLabel)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                Spacer(minLength: 0)
            }

            HStack {
                StatusPill(text: status.title, color: status.color)
                Spacer()
                Label(t("AES-256-GCM", "AES-256-GCM"), systemImage: "lock.fill")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(DS.secondaryLabel)
            }

            Grid(horizontalSpacing: 10, verticalSpacing: 10) {
                GridRow {
                    tile(icon: "antenna.radiowaves.left.and.right", title: t("Bluetooth", "بلوتوث"),
                         value: ble.bluetoothPoweredOn ? t("On", "روشن") : t("Off", "خاموش"),
                         ok: ble.bluetoothPoweredOn)
                    tile(icon: "link", title: t("BLE Link", "اتصال BLE"),
                         value: ble.isConnected ? t("Connected", "متصل") : t("Disconnected", "قطع"),
                         ok: ble.isConnected)
                }
                GridRow {
                    tile(icon: "phone.fill", title: t("Call Audio", "صدای تماس"),
                         value: ble.callAudioReady ? t("Ready", "آماده") : t("Off", "غیرفعال"),
                         ok: ble.callAudioReady)
                    tile(icon: "waveform", title: t("Audio Stream", "جریان صدا"),
                         value: audio.isStreaming ? t("Streaming", "فعال")
                             : (audio.isReconnecting ? t("Reconnecting", "اتصال دوباره") : t("Idle", "غیرفعال")),
                         ok: audio.isStreaming)
                }
            }
        }
        .padding(18)
        .cardStyle()
    }

    private func tile(icon: String, title: String, value: String, ok: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(ok ? DS.green : DS.gray)
                .frame(height: 20)
            Text(title)
                .font(.caption)
                .foregroundStyle(DS.secondaryLabel)
            Text(value)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(DS.label)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(DS.groupedBackground, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
