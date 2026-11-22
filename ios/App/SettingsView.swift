import SwiftUI

struct SettingsView: View {
    @Environment(PlaybackHub.self) private var hub
    @State private var accent = Color(hex: 0x6B4FD8)

    var body: some View {
        @Bindable var settings = hub.settings
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Make the hub yours: connect your sources, pick a look, add the widget.")
                        .font(Palette.body(14)).foregroundStyle(Palette.mist)

                    Card {
                        row(
                            title: "Spotify",
                            body: "Approve once so playlist songs start exactly, from the beginning. Needs Spotify Premium. iPhone may open Spotify for a moment when it is not running.",
                            done: hub.spotify.isLinked,
                            action: hub.spotify.isLinked ? "Reconnect" : "Connect"
                        ) { hub.spotify.link() }
                        Divider().overlay(Color.white.opacity(0.1))
                        row(
                            title: "Music library",
                            body: "Play songs from the Music app (including Apple Music) in hub playlists.",
                            done: hub.library.appleAccess == .authorized,
                            action: "Allow"
                        ) { hub.library.requestAppleAccess() }
                        Divider().overlay(Color.white.opacity(0.1))
                        VStack(alignment: .leading, spacing: 4) {
                            Text("YouTube Music").font(Palette.heading(16)).foregroundStyle(Palette.paper)
                            Text("iPhone does not let other apps control YouTube Music, so its songs are skipped here (they still play on Android).")
                                .font(Palette.body(13)).foregroundStyle(Palette.mist)
                        }
                    }

                    Card {
                        Text("Look").font(Palette.heading(18)).foregroundStyle(Palette.paper)
                        Picker("Theme", selection: $settings.glass) {
                            Text("Night").tag(false)
                            Text("Glass").tag(true)
                        }
                        .pickerStyle(.segmented)
                        HStack {
                            Text("Accent color").font(Palette.body(15)).foregroundStyle(Palette.paper)
                            Spacer()
                            Button("From cover") { settings.customAccentHex = 0 }
                                .font(Palette.body(13))
                                .tint(settings.customAccentHex == 0 ? Palette.lavender : Palette.mist)
                            ColorPicker("", selection: $accent, supportsOpacity: false)
                                .labelsHidden()
                                .onChange(of: accent) { _, c in settings.customAccentHex = max(c.hexValue, 1) }
                        }
                        Text("Visualizer").font(Palette.body(15)).foregroundStyle(Palette.paper)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 10) {
                                ForEach(VisualizerStyle.allCases) { style in
                                    Button {
                                        settings.visualizerStyle = style
                                    } label: {
                                        VStack(spacing: 6) {
                                            BarsShape(bars: sample, style: style, color: Color(hex: hub.accentHex), spacing: 2)
                                                .frame(width: 64, height: 34)
                                            Text(style.label).font(Palette.body(12))
                                                .foregroundStyle(settings.visualizerStyle == style ? Palette.paper : Palette.mist)
                                        }
                                        .padding(10)
                                        .background(
                                            RoundedRectangle(cornerRadius: 14)
                                                .fill(Color.white.opacity(settings.visualizerStyle == style ? 0.16 : 0.05))
                                        )
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                        HStack {
                            Text("Sensitivity").font(Palette.body(15)).foregroundStyle(Palette.paper)
                            Slider(value: $settings.sensitivity, in: 0.5...2.0)
                                .tint(Color(hex: hub.accentHex))
                        }
                    }

                    Card {
                        Text("Widgets").font(Palette.heading(18)).foregroundStyle(Palette.paper)
                        Text("Home Screen: touch and hold an empty area → Edit → Add Widget → Ultimate Music Widget (small, medium or large).\nLock Screen: touch and hold the Lock Screen → Customize → Lock Screen → add the widget under the clock.")
                            .font(Palette.body(13)).foregroundStyle(Palette.mist)
                        Text("Songs from your imported files show their cover and controls on the Lock Screen as well.")
                            .font(Palette.body(13)).foregroundStyle(Palette.mist)
                    }

                    Card {
                        Text("About").font(Palette.heading(18)).foregroundStyle(Palette.paper)
                        Text("The Ultimate Music Widget · iOS 1.3\nSEN0413 · Project 17\nOrhun Utku Topal · Jaweria Qadir · Mustafa Alhussein\n\nFonts: Space Grotesk and DM Sans (SIL Open Font License). Spotify playback uses the Spotify iOS SDK.")
                            .font(Palette.body(13)).foregroundStyle(Palette.mist)
                    }
                }
                .padding(20)
            }
            .navigationTitle("Settings")
            .onAppear {
                if settings.customAccentHex != 0 { accent = Color(hex: settings.customAccentHex) }
            }
        }
    }

    private let sample: [Float] = [0.4, 0.7, 0.55, 0.9, 0.65, 0.8, 0.5, 0.35, 0.6, 0.45]

    private func row(title: String, body: String, done: Bool, action: String, perform: @escaping () -> Void) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(Palette.heading(16)).foregroundStyle(Palette.paper)
                Text(body).font(Palette.body(13)).foregroundStyle(Palette.mist)
            }
            Spacer()
            if done {
                Label("On", systemImage: "checkmark.circle.fill").font(Palette.body(13)).foregroundStyle(Palette.lavender)
            }
            Button(done && action == "Allow" ? "" : action, action: perform)
                .buttonStyle(.bordered).tint(Palette.lavender)
                .opacity(done && action == "Allow" ? 0 : 1)
        }
    }
}
