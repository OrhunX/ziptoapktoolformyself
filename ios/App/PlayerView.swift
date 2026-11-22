import SwiftUI

struct PlayerView: View {
    @Environment(PlaybackHub.self) private var hub
    @State private var scrubbing: Double?

    private var accent: Color { Color(hex: hub.accentHex) }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                header
                CoverArt(image: hub.artwork, accent: accent, corner: 28)
                    .aspectRatio(1, contentMode: .fit)
                    .frame(maxWidth: 340)
                    .shadow(color: accent.opacity(0.45), radius: 30, y: 12)
                    .scaleEffect(1 + CGFloat(beat) * 0.025)
                    .animation(.easeOut(duration: 0.12), value: beat)
                titleBlock
                BarsShape(bars: hub.bars, style: hub.settings.visualizerStyle, color: accent)
                    .frame(height: 72)
                    .padding(.horizontal, 4)
                if !hub.barsAreLive && hub.isPlaying {
                    Text("Ambient visualizer · iPhone can't listen to other apps' audio")
                        .font(Palette.body(11))
                        .foregroundStyle(Palette.mist.opacity(0.7))
                }
                progress
                controls
                upNext
            }
            .padding(.horizontal, 22)
            .padding(.bottom, 30)
        }
        .scrollIndicators(.hidden)
    }

    /// Low-band energy, used for a subtle pulse of the cover.
    private var beat: Float {
        guard hub.isPlaying, hub.bars.count > 3 else { return 0 }
        return (hub.bars[0] + hub.bars[1] + hub.bars[2]) / 3
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("NOW PLAYING").font(.system(size: 11, weight: .bold)).foregroundStyle(Palette.mist)
                Text(hub.sessionName ?? "Pick a playlist").font(Palette.heading(15)).foregroundStyle(Palette.paper).lineLimit(1)
            }
            Spacer()
            if let s = hub.current?.source { SourceChip(source: s) }
        }
        .padding(.top, 8)
    }

    private var titleBlock: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 4) {
                Text(hub.current?.title ?? "Nothing playing")
                    .font(Palette.display(26)).foregroundStyle(Palette.paper).lineLimit(2)
                Text(hub.current?.artist ?? "Start a playlist from the Playlists tab")
                    .font(Palette.body(15)).foregroundStyle(Palette.mist).lineLimit(1)
            }
            Spacer()
            Button {
                hub.toggleFavorite()
            } label: {
                Image(systemName: hub.currentIsFavorite ? "heart.fill" : "heart")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(hub.currentIsFavorite ? Palette.appleMusic : Palette.paper)
                    .frame(width: 48, height: 48)
            }
            .disabled(hub.current == nil)
        }
    }

    private var progress: some View {
        let duration = max(Double(hub.durationMs), 1)
        let value = scrubbing ?? min(Double(hub.positionMs), duration)
        return VStack(spacing: 4) {
            Slider(value: Binding(get: { value }, set: { scrubbing = $0 }), in: 0...duration) { editing in
                if !editing, let s = scrubbing {
                    hub.seek(toMs: Int64(s))
                    scrubbing = nil
                }
            }
            .tint(accent)
            HStack {
                Text(Entry.format(ms: Int64(value)).ifEmpty("0:00"))
                Spacer()
                Text(Entry.format(ms: hub.durationMs).ifEmpty("--:--"))
            }
            .font(Palette.body(12)).foregroundStyle(Palette.mist).monospacedDigit()
        }
    }

    private var controls: some View {
        HStack {
            roundButton(hub.shuffle ? "shuffle.circle.fill" : "shuffle", active: hub.shuffle, size: 20) { hub.toggleShuffle() }
            Spacer()
            roundButton("backward.fill", size: 26) { hub.previous() }
            Spacer()
            Button {
                hub.togglePlay()
            } label: {
                ZStack {
                    Circle().fill(Palette.paper).frame(width: 78, height: 78)
                    Image(systemName: hub.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 30, weight: .bold)).foregroundStyle(Palette.night)
                }
            }
            Spacer()
            roundButton("forward.fill", size: 26) { hub.next() }
            Spacer()
            roundButton(hub.repeatMode.symbol, active: hub.repeatMode != .off, size: 20) { hub.cycleRepeat() }
        }
        .padding(.horizontal, 4)
    }

    private func roundButton(_ symbol: String, active: Bool = true, size: CGFloat, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size, weight: .semibold))
                .foregroundStyle(active ? Palette.paper : Palette.mist.opacity(0.5))
                .frame(width: 48, height: 48)
        }
    }

    @ViewBuilder
    private var upNext: some View {
        let items = hub.upNext
        if !items.isEmpty {
            Card {
                Text("Up next").font(Palette.heading(16)).foregroundStyle(Palette.paper)
                ForEach(Array(items.prefix(12))) { item in
                    Button {
                        hub.skipTo(position: item.position)
                    } label: {
                        HStack(spacing: 10) {
                            Circle().fill(item.entry.source.color).frame(width: 8, height: 8)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(item.entry.title).font(Palette.body(14)).foregroundStyle(Palette.paper).lineLimit(1)
                                Text(item.entry.artist).font(Palette.body(12)).foregroundStyle(Palette.mist).lineLimit(1)
                            }
                            Spacer()
                            Text(item.entry.durationText).font(Palette.body(12)).foregroundStyle(Palette.mist).monospacedDigit()
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

extension String {
    func ifEmpty(_ fallback: String) -> String { isEmpty ? fallback : self }
}
