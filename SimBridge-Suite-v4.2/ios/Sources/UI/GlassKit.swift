import SwiftUI
import UIKit

// MARK: - Design tokens (light, iOS-native)

/// SimBridge light design system. Built on iOS semantic colors so it follows
/// the system look (grouped backgrounds, SF typography, system tints).
/// Deployment target is iOS 17, so the iOS 26-only `glassEffect` API is not used;
/// the "Liquid Glass" surfaces are approximated with system materials.
enum DS {
    // Surfaces
    static let groupedBackground = Color(uiColor: .systemGroupedBackground)
    static let cardBackground = Color(uiColor: .secondarySystemGroupedBackground)
    static let plainBackground = Color(uiColor: .systemBackground)
    static let fill = Color(uiColor: .systemGray5)
    static let fillPressed = Color(uiColor: .systemGray3)
    static let incomingBubble = Color(uiColor: .systemGray5)
    static let separator = Color(uiColor: .separator)

    // Text
    static let label = Color(uiColor: .label)
    static let secondaryLabel = Color(uiColor: .secondaryLabel)
    static let tertiaryLabel = Color(uiColor: .tertiaryLabel)

    // Tints
    static let accent = Color(uiColor: .systemBlue)
    static let green = Color(uiColor: .systemGreen)
    static let red = Color(uiColor: .systemRed)
    static let orange = Color(uiColor: .systemOrange)
    static let indigo = Color(uiColor: .systemIndigo)
    static let teal = Color(uiColor: .systemTeal)
    static let pink = Color(uiColor: .systemPink)
    static let gray = Color(uiColor: .systemGray)

    // Metrics
    static let cardRadius: CGFloat = 22
    static let tabBarHeight: CGFloat = 64
    /// Bottom space scroll content reserves so it can scroll clear of the floating tab bar.
    static let tabBarClearance: CGFloat = 84
    static let keySize: CGFloat = 76
    static let callControlSize: CGFloat = 74
}

// MARK: - Legacy names (kept so older call sites still compile)

/// Former neon palette, now mapped onto the light system tints.
enum Neon {
    static let bg = DS.groupedBackground
    static let cyan = DS.accent
    static let violet = DS.indigo
    static let magenta = DS.pink
    static let mint = DS.green
    static let amber = DS.orange

    static var gradient: LinearGradient {
        LinearGradient(colors: [DS.accent, DS.indigo], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

/// Former animated aurora. Now a calm system grouped background with a faint accent wash.
struct AuroraBackground: View {
    var body: some View {
        ZStack(alignment: .top) {
            DS.groupedBackground
            LinearGradient(colors: [DS.accent.opacity(0.08), .clear], startPoint: .top, endPoint: .center)
        }
        .ignoresSafeArea()
    }
}

// MARK: - Glass surfaces

/// Translucent "Liquid Glass" surface: ultra-thin material, light rim and soft shadow.
private struct GlassSurfaceModifier<S: InsettableShape>: ViewModifier {
    let shape: S
    func body(content: Content) -> some View {
        content
            .background {
                ZStack {
                    shape.fill(.ultraThinMaterial)
                    shape.fill(Color.white.opacity(0.28))
                }
            }
            .overlay(
                shape.strokeBorder(
                    LinearGradient(colors: [Color.white.opacity(0.95), Color.white.opacity(0.35)],
                                   startPoint: .top, endPoint: .bottom),
                    lineWidth: 1
                )
            )
            .overlay(shape.strokeBorder(Color.black.opacity(0.06), lineWidth: 0.5))
            .clipShape(shape)
            .shadow(color: Color.black.opacity(0.12), radius: 20, x: 0, y: 8)
    }
}

/// Solid grouped card (white on the grouped background), like an inset list section.
private struct CardSurfaceModifier: ViewModifier {
    let radius: CGFloat
    func body(content: Content) -> some View {
        content
            .background(DS.cardBackground, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

extension View {
    func glassCard(_ radius: CGFloat = DS.cardRadius) -> some View {
        modifier(GlassSurfaceModifier(shape: RoundedRectangle(cornerRadius: radius, style: .continuous)))
    }
    func glassCapsule() -> some View {
        modifier(GlassSurfaceModifier(shape: Capsule()))
    }
    func glassCircle() -> some View {
        modifier(GlassSurfaceModifier(shape: Circle()))
    }
    func cardStyle(_ radius: CGFloat = DS.cardRadius) -> some View {
        modifier(CardSurfaceModifier(radius: radius))
    }
    /// Reserves room at the bottom of scrolling content for the floating tab bar.
    func tabBarSafeArea() -> some View {
        safeAreaInset(edge: .bottom, spacing: 0) {
            Color.clear.frame(height: DS.tabBarClearance)
        }
    }
}

// MARK: - Buttons & toggles

/// Capsule button tinted with the accent color (filled or tinted-light variant).
struct TintedButtonStyle: ButtonStyle {
    var enabled: Bool = true
    var tint: Color = DS.accent
    var prominent: Bool = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(prominent ? Color.white : tint)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(prominent ? tint : tint.opacity(0.12), in: Capsule())
            .opacity(enabled ? (configuration.isPressed ? 0.75 : 1) : 0.4)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

/// Legacy name for the primary button style.
typealias NeonButtonStyle = TintedButtonStyle

/// Standard iOS switch with the system green tint.
struct SystemSwitchToggleStyle: ToggleStyle {
    var tint: Color = DS.green
    func makeBody(configuration: Configuration) -> some View {
        Toggle(configuration)
            .toggleStyle(.switch)
            .tint(tint)
    }
}

/// Legacy name for the toggle style.
typealias NeonToggleStyle = SystemSwitchToggleStyle

/// Round key (keypad digits, in-call controls). The label sets its own frame.
struct RoundKeyButtonStyle: ButtonStyle {
    var fill: Color = DS.fill
    var pressedFill: Color = DS.fillPressed

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(Circle().fill(configuration.isPressed ? pressedFill : fill))
            .contentShape(Circle())
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

// MARK: - Small components

/// Colored dot + label in a tinted capsule ("Connected", "Searching…").
struct StatusPill: View {
    let text: String
    let color: Color

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(text).font(.footnote.weight(.semibold))
        }
        .foregroundStyle(color)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(color.opacity(0.12), in: Capsule())
    }
}

/// iOS Settings-style rounded square icon.
struct SettingsIcon: View {
    let systemName: String
    let color: Color

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(Color.white)
            .frame(width: 29, height: 29)
            .background(color, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
    }
}

/// Icon + title used as the label of grouped settings rows.
struct SettingsLabel: View {
    let icon: String
    let color: Color
    let title: String
    var titleColor: Color = DS.label

    var body: some View {
        HStack(spacing: 12) {
            SettingsIcon(systemName: icon, color: color)
            Text(title).foregroundStyle(titleColor)
        }
    }
}

/// Monogram avatar with a stable color derived from the name.
struct AvatarView: View {
    let name: String
    var size: CGFloat = 40

    var body: some View {
        let initials = Self.initials(for: name)
        let base = Self.color(for: name)
        ZStack {
            Circle()
                .fill(LinearGradient(colors: [base.opacity(0.7), base], startPoint: .top, endPoint: .bottom))
            if initials.isEmpty {
                Image(systemName: "person.fill")
                    .font(.system(size: size * 0.46, weight: .medium))
                    .foregroundStyle(Color.white)
            } else {
                Text(initials)
                    .font(.system(size: size * 0.38, weight: .semibold, design: .rounded))
                    .foregroundStyle(Color.white)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    static func initials(for name: String) -> String {
        let words = name.split(separator: " ").filter { word in
            word.first.map { $0.isLetter } ?? false
        }
        return words.prefix(2).compactMap { $0.first }.map { String($0).uppercased() }.joined()
    }

    static func color(for name: String) -> Color {
        let palette: [Color] = [DS.accent, DS.indigo, .purple, DS.pink, DS.orange, DS.teal, DS.green, .cyan]
        let sum = name.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        return palette[abs(sum) % palette.count]
    }
}

// MARK: - Floating Liquid Glass tab bar

struct GlassTabItem<ID: Hashable>: Identifiable {
    let id: ID
    let title: String
    let systemImage: String
}

struct GlassTabBar<ID: Hashable>: View {
    @Binding var selection: ID
    let items: [GlassTabItem<ID>]
    @Namespace private var selectionNamespace

    var body: some View {
        HStack(spacing: 2) {
            ForEach(items) { item in
                let selected = item.id == selection
                Button {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { selection = item.id }
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: item.systemImage)
                            .font(.system(size: 19, weight: .semibold))
                            .frame(height: 24)
                        Text(item.title)
                            .font(.system(size: 10, weight: .semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .foregroundStyle(selected ? DS.accent : DS.label.opacity(0.75))
                    .frame(maxWidth: .infinity)
                    .frame(height: DS.tabBarHeight - 12)
                    .background {
                        if selected {
                            Capsule()
                                .fill(DS.accent.opacity(0.12))
                                .matchedGeometryEffect(id: "tab.selection", in: selectionNamespace)
                        }
                    }
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(item.title)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(6)
        .frame(height: DS.tabBarHeight)
        .glassCapsule()
    }
}

/// Lets pushed screens (e.g. a chat with its own composer) hide the floating tab bar.
private struct TabBarHiddenKey: EnvironmentKey {
    static var defaultValue: Binding<Bool> { .constant(false) }
}

extension EnvironmentValues {
    var tabBarHidden: Binding<Bool> {
        get { self[TabBarHiddenKey.self] }
        set { self[TabBarHiddenKey.self] = newValue }
    }
}
