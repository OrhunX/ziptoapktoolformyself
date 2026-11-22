import SwiftUI

@main
struct UltimateMusicWidgetApp: App {
    @State private var hub = PlaybackHub.shared
    @State private var importCode: ImportRequest?
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView(importCode: $importCode)
                .environment(hub)
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    if SpotifyRemote.shared.handle(url: url) { return }
                    if url.scheme == "umw",
                       let code = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                        .queryItems?.first(where: { $0.name == "c" })?.value {
                        importCode = ImportRequest(code: code)
                    }
                }
        }
        .onChange(of: phase) { _, newPhase in
            if newPhase == .active { hub.appBecameActive() }
        }
    }
}

struct ImportRequest: Identifiable {
    let id = UUID()
    let code: String
}

enum AppTab: Hashable {
    case player, library, playlists, settings
}

struct RootView: View {
    @Environment(PlaybackHub.self) private var hub
    @Binding var importCode: ImportRequest?
    @State private var tab: AppTab = .player

    var body: some View {
        ZStack(alignment: .top) {
            AmbientBackground()
            TabView(selection: $tab) {
                PlayerView()
                    .tabItem { Label("Now Playing", systemImage: "waveform") }
                    .tag(AppTab.player)
                LibraryView()
                    .tabItem { Label("Library", systemImage: "music.note.list") }
                    .tag(AppTab.library)
                PlaylistsView(openPlayer: { tab = .player })
                    .tabItem { Label("Playlists", systemImage: "square.stack.fill") }
                    .tag(AppTab.playlists)
                SettingsView()
                    .tabItem { Label("Settings", systemImage: "slider.horizontal.3") }
                    .tag(AppTab.settings)
            }
            .tint(Palette.lavender)
            ToastView()
        }
        .sheet(item: $importCode) { request in
            ImportCodeSheet(initialCode: request.code)
        }
    }
}

/// Dark background tinted by the cover; with Glass, the blurred cover itself.
struct AmbientBackground: View {
    @Environment(PlaybackHub.self) private var hub

    var body: some View {
        ZStack {
            Palette.night
            if hub.settings.glass, let art = hub.artwork {
                Image(uiImage: art).resizable().scaledToFill().blur(radius: 60).opacity(0.55)
                Color.black.opacity(0.25)
            } else {
                RadialGradient(colors: [Color(hex: hub.accentHex).opacity(0.45), .clear], center: .topLeading, startRadius: 0, endRadius: 520)
                RadialGradient(colors: [Palette.violet.opacity(0.25), .clear], center: .bottomTrailing, startRadius: 0, endRadius: 480)
            }
        }
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.8), value: hub.accentHex)
    }
}

struct ToastView: View {
    @Environment(PlaybackHub.self) private var hub
    @State private var visible: String?

    var body: some View {
        Group {
            if let text = visible {
                Text(text)
                    .font(Palette.body(14))
                    .foregroundStyle(Palette.paper)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.ultraThinMaterial, in: Capsule())
                    .padding(.top, 8)
                    .padding(.horizontal, 20)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .onChange(of: hub.toast) { _, text in
            guard let text else { return }
            withAnimation { visible = text }
            hub.toast = nil
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.6) {
                if visible == text { withAnimation { visible = nil } }
            }
        }
    }
}

/// Rounded translucent card, like the Android app's cards.
struct Card<Content: View>: View {
    @Environment(PlaybackHub.self) private var hub
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .fill(Color.white.opacity(hub.settings.glass ? 0.17 : 0.08))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .stroke(Color.white.opacity(hub.settings.glass ? 0.3 : 0.12), lineWidth: 1)
            )
    }
}

struct SourceChip: View {
    let source: SourceKind

    var body: some View {
        Text(source.label.uppercased())
            .font(.system(size: 10, weight: .bold))
            .foregroundStyle(source.color)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(Capsule().fill(source.color.opacity(0.15)))
    }
}

struct CoverArt: View {
    var image: UIImage?
    var accent: Color
    var symbol: String = "music.note"
    var corner: CGFloat = 24

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                LinearGradient(colors: [accent, Palette.violet.opacity(0.7)], startPoint: .topLeading, endPoint: .bottomTrailing)
                Image(systemName: symbol).font(.system(size: 44, weight: .semibold)).foregroundStyle(.white.opacity(0.85))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
    }
}
