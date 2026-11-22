import AppIntents
import SwiftUI
import UIKit
import WidgetKit

/// In the widget extension the buttons only declare the intent; iOS performs audio-playback
/// intents inside the app, where the real router lives.
enum IntentRouter {
    static func run(_ command: HubCommand) async {}
}

struct SnapshotEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
    let art: UIImage?
}

struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry {
        SnapshotEntry(date: Date(), snapshot: .empty, art: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        completion(current())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        // The app reloads the widget whenever the song or state changes; no polling needed.
        completion(Timeline(entries: [current()], policy: .never))
    }

    private func current() -> SnapshotEntry {
        let snap = WidgetSnapshot.load()
        var art: UIImage?
        if snap.hasArt, let url = WidgetSnapshot.artURL, let data = try? Data(contentsOf: url) {
            art = UIImage(data: data)
        }
        return SnapshotEntry(date: Date(), snapshot: snap, art: art)
    }
}

struct UltimateWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SnapshotEntry

    private var snap: WidgetSnapshot { entry.snapshot }
    private var accent: Color { Color(hex: snap.accentHex) }
    private var style: VisualizerStyle { VisualizerStyle(rawValue: snap.visualizerStyle) ?? .bars }

    var body: some View {
        switch family {
        case .accessoryCircular: circular
        case .accessoryRectangular: rectangular
        case .accessoryInline: inline
        case .systemSmall: small
        case .systemLarge, .systemExtraLarge: large
        default: medium
        }
    }

    // MARK: Lock Screen

    private var circular: some View {
        ZStack {
            AccessoryWidgetBackground()
            Button(intent: PlayPauseIntent()) {
                Image(systemName: snap.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 20, weight: .bold))
            }
            .buttonStyle(.plain)
        }
        .containerBackground(for: .widget) { Color.clear }
    }

    private var rectangular: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 1) {
                Text(snap.title).font(.headline).lineLimit(1)
                Text(snap.artist).font(.caption).lineLimit(1).opacity(0.8)
                BarsShape(bars: Array(snap.bars.prefix(12)), style: .bars, color: .white, spacing: 2)
                    .frame(height: 12)
            }
            Button(intent: PlayPauseIntent()) {
                Image(systemName: snap.isPlaying ? "pause.fill" : "play.fill").font(.title3)
            }
            .buttonStyle(.plain)
        }
        .containerBackground(for: .widget) { Color.clear }
    }

    private var inline: some View {
        Text("\(snap.isPlaying ? "▶︎" : "❚❚") \(snap.title) · \(snap.artist)")
            .containerBackground(for: .widget) { Color.clear }
    }

    // MARK: Home Screen

    private var artView: some View {
        ZStack {
            if let art = entry.art {
                Image(uiImage: art).resizable().scaledToFill()
            } else {
                LinearGradient(colors: [accent, Palette.violet.opacity(0.6)], startPoint: .topLeading, endPoint: .bottomTrailing)
                Image(systemName: "music.note").font(.system(size: 26, weight: .semibold)).foregroundStyle(.white.opacity(0.85))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var background: some View {
        ZStack {
            Palette.night
            if snap.glass, let art = entry.art {
                Image(uiImage: art).resizable().scaledToFill().blur(radius: 30).opacity(0.55)
                Color.white.opacity(0.06)
            } else {
                RadialGradient(colors: [accent.opacity(0.55), .clear], center: .topLeading, startRadius: 0, endRadius: 260)
            }
        }
    }

    private var sourceChip: some View {
        Text(snap.sourceLabel.uppercased())
            .font(.system(size: 9, weight: .bold))
            .foregroundStyle(Color(hex: snap.sourceColorHex))
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Capsule().fill(Color(hex: snap.sourceColorHex).opacity(0.16)))
    }

    private func controls(size: CGFloat) -> some View {
        HStack(spacing: size * 0.9) {
            Button(intent: PreviousTrackIntent()) {
                Image(systemName: "backward.fill").font(.system(size: size * 0.7))
            }
            Button(intent: PlayPauseIntent()) {
                ZStack {
                    Circle().fill(Palette.paper).frame(width: size * 1.7, height: size * 1.7)
                    Image(systemName: snap.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: size * 0.75, weight: .bold))
                        .foregroundStyle(Palette.night)
                }
            }
            Button(intent: NextTrackIntent()) {
                Image(systemName: "forward.fill").font(.system(size: size * 0.7))
            }
        }
        .buttonStyle(.plain)
        .foregroundStyle(Palette.paper)
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                artView.frame(width: 52, height: 52)
                Spacer()
                Button(intent: PlayPauseIntent()) {
                    ZStack {
                        Circle().fill(Palette.paper).frame(width: 36, height: 36)
                        Image(systemName: snap.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.night)
                    }
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
            Text(snap.title).font(Palette.heading(14)).foregroundStyle(Palette.paper).lineLimit(1)
            Text(snap.artist).font(Palette.body(11)).foregroundStyle(Palette.mist).lineLimit(1)
            BarsShape(bars: Array(snap.bars.prefix(12)), style: style, color: accent.opacity(0.9), spacing: 2)
                .frame(height: 14)
        }
        .containerBackground(for: .widget) { background }
    }

    private var medium: some View {
        HStack(spacing: 12) {
            artView.frame(width: 110, height: 110)
            VStack(alignment: .leading, spacing: 4) {
                sourceChip
                Text(snap.title).font(Palette.heading(16)).foregroundStyle(Palette.paper).lineLimit(1)
                Text(snap.artist).font(Palette.body(12)).foregroundStyle(Palette.mist).lineLimit(1)
                BarsShape(bars: snap.bars, style: style, color: accent, spacing: 2).frame(height: 18)
                Spacer(minLength: 0)
                controls(size: 18)
            }
        }
        .containerBackground(for: .widget) { background }
    }

    private var large: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                sourceChip
                Spacer()
                if let name = snap.playlistName {
                    Text(name).font(Palette.body(11)).foregroundStyle(Palette.mist).lineLimit(1)
                }
            }
            artView.frame(maxWidth: .infinity).frame(height: 150)
            Text(snap.title).font(Palette.display(20)).foregroundStyle(Palette.paper).lineLimit(1)
            Text(snap.artist).font(Palette.body(13)).foregroundStyle(Palette.mist).lineLimit(1)
            BarsShape(bars: snap.bars, style: style, color: accent, spacing: 3).frame(height: 36)
            HStack { Spacer(); controls(size: 22); Spacer() }
        }
        .containerBackground(for: .widget) { background }
    }
}

struct UltimateMusicWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetSnapshot.kind, provider: SnapshotProvider()) { entry in
            UltimateWidgetView(entry: entry)
        }
        .configurationDisplayName("Ultimate Music Widget")
        .description("Now playing with controls for your hub playlists.")
        .supportedFamilies([
            .systemSmall, .systemMedium, .systemLarge,
            .accessoryCircular, .accessoryRectangular, .accessoryInline,
        ])
    }
}

@main
struct UltimateWidgetBundle: WidgetBundle {
    var body: some Widget {
        UltimateMusicWidget()
    }
}
