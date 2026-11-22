import AVFoundation
import Foundation
import MediaPlayer
import Observation
import UIKit
import WidgetKit

/// The hub: plays hub playlists across our own player, Apple Music and Spotify, owns the one
/// "now playing" state every screen and the widget show, and moves on when a song ends.
@Observable
@MainActor
final class PlaybackHub {
    static let shared = PlaybackHub()

    let playlists = PlaylistStore()
    let library = MusicLibrary()
    let settings = AppSettings.shared

    @ObservationIgnored private let local = LocalAudioPlayer()
    @ObservationIgnored private let apple = AppleMusicPlayer()
    @ObservationIgnored let spotify = SpotifyRemote.shared
    @ObservationIgnored private let keepAlive = KeepAlive()

    // Session (the playlist being played)
    private(set) var sessionName: String?
    private(set) var sessionPlaylistIds: [UUID] = []
    private(set) var entries: [Entry] = []
    private(set) var order: [Int] = []
    private(set) var pos = 0

    // Now playing
    private(set) var current: Entry?
    private(set) var activeSource: SourceKind?
    private(set) var isPlaying = false
    private(set) var positionMs: Int64 = 0
    private(set) var durationMs: Int64 = 0
    private(set) var artwork: UIImage?
    private(set) var artAccentHex: UInt32 = 0x6B4FD8
    private(set) var bars: [Float] = Array(repeating: 0.05, count: 24)
    /// True when the bars come from the real audio (our own player); otherwise they are an
    /// ambient animation, because iOS does not let apps hear Apple Music or Spotify.
    private(set) var barsAreLive = false
    private(set) var spotifyConnected = false

    /// One-off message for a toast.
    var toast: String?

    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var misses = 0
    @ObservationIgnored private var spotifyWatch: SpotifyWatch?
    @ObservationIgnored private var ticker: Timer?
    @ObservationIgnored private var tickCount = 0
    @ObservationIgnored private var ambientPhase: Double = 0
    @ObservationIgnored private var lastWidgetKey = ""
    @ObservationIgnored private var lastArtTitle = ""

    private struct SpotifyWatch {
        var uri: String?
        let title: String
        var armed = false
        let startedAt = Date()
        let generation: Int
    }

    var accentHex: UInt32 { settings.customAccentHex != 0 ? settings.customAccentHex : artAccentHex }

    var shuffle: Bool { settings.shuffle }
    var repeatMode: RepeatMode { settings.repeatMode }

    struct UpNextItem: Identifiable {
        let position: Int
        let entry: Entry
        var id: Int { position }
    }

    /// Songs after the current one, with their position in the play order.
    var upNext: [UpNextItem] {
        guard !order.isEmpty, pos + 1 < order.count else { return [] }
        return (pos + 1..<min(order.count, pos + 31)).map { UpNextItem(position: $0, entry: entries[order[$0]]) }
    }

    private init() {
        local.onEnded = { [weak self] in self?.songEnded(source: .local) }
        local.onBars = { [weak self] bars in
            guard let self, self.activeSource == .local else { return }
            self.bars = bars
        }
        apple.onEnded = { [weak self] in self?.songEnded(source: .appleMusic) }
        apple.onChange = { [weak self] in self?.appleChanged() }
        spotify.onState = { [weak self] state in self?.spotifyChanged(state) }
        spotify.onConnectionChange = { [weak self] connected in self?.spotifyConnected = connected }
        settings.onChange = { [weak self] in
            guard let self else { return }
            self.local.gain = Float(self.settings.sensitivity)
            self.publishWidget(force: true)
        }
        local.gain = Float(settings.sensitivity)
        restoreSession()
        setUpRemoteCommands()
        ticker = Timer.scheduledTimer(withTimeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        publishWidget(force: true)
    }

    // MARK: starting playlists

    func play(playlist: Playlist, startAt index: Int? = nil, forceShuffle: Bool = false) {
        let shuffleNow = forceShuffle || (index == nil && playlist.defaultShuffle)
        startSession(name: playlist.name, ids: [playlist.id], entries: playlist.entries, index: index, forceShuffle: shuffleNow)
    }

    /// Several playlists shuffled together, without saving a new playlist.
    func playMix(_ lists: [Playlist]) {
        var seen = Set<String>()
        var all: [Entry] = []
        for p in lists {
            for e in p.entries where seen.insert(TrackMatcher.key(e.title, e.artist)).inserted { all.append(e) }
        }
        startSession(name: lists.map(\.name).joined(separator: " + "), ids: lists.map(\.id), entries: all, index: nil, forceShuffle: true)
    }

    /// Songs from the library (files, Apple Music, Spotify search).
    func playSongs(_ songs: [Entry], index: Int, name: String) {
        startSession(name: name, ids: [], entries: songs, index: index, forceShuffle: false)
    }

    private func startSession(name: String, ids: [UUID], entries list: [Entry], index: Int?, forceShuffle: Bool) {
        guard !list.isEmpty else {
            toast = "This playlist is empty"
            return
        }
        if forceShuffle { settings.shuffle = true }
        sessionName = name
        sessionPlaylistIds = ids
        entries = list
        order = Array(list.indices)
        var start = min(max(index ?? 0, 0), list.count - 1)
        if settings.shuffle {
            order.shuffle()
            if let index, let at = order.firstIndex(of: index) {
                order.swapAt(0, at)
            }
            start = 0
        }
        misses = 0
        if !ids.isEmpty { playlists.markPlayed(ids) }
        startEntry(at: start)
        saveSession()
    }

    // MARK: transport

    func togglePlay() {
        if isPlaying { pauseCurrent() } else { resumeCurrent() }
    }

    func setPlaying(_ play: Bool) {
        if play != isPlaying { togglePlay() }
    }

    func next() {
        guard !order.isEmpty else { return }
        advance(auto: false)
    }

    func previous() {
        guard !order.isEmpty else { return }
        if positionMs > 3000 {
            seek(toMs: 0)
        } else {
            startEntry(at: max(pos - 1, 0))
        }
    }

    func skipTo(position: Int) {
        guard order.indices.contains(position) else { return }
        startEntry(at: position)
    }

    func seek(toMs ms: Int64) {
        switch activeSource ?? .other {
        case .local: local.seek(toMs: ms)
        case .appleMusic: apple.seek(toMs: ms)
        case .spotify: spotify.seek(toMs: ms)
        default: break
        }
        positionMs = ms
        updateNowPlayingInfo()
    }

    func toggleShuffle() {
        settings.shuffle.toggle()
        guard !order.isEmpty else { return }
        let cur = order[pos]
        if settings.shuffle {
            order = [cur] + order.filter { $0 != cur }.shuffled()
            pos = 0
        } else {
            order = Array(entries.indices)
            pos = cur
        }
        saveSession()
    }

    func cycleRepeat() {
        settings.repeatMode = settings.repeatMode.next
    }

    func handle(_ command: HubCommand) {
        switch command {
        case .playPause: togglePlay()
        case .next: next()
        case .previous: previous()
        }
    }

    // MARK: favorites

    var currentIsFavorite: Bool {
        guard let c = current else { return false }
        return playlists.isFavorite(title: c.title, artist: c.artist)
    }

    func toggleFavorite() {
        guard let c = current else { return }
        let added = playlists.toggleFavorite(c)
        toast = added ? "Added to Favorites" : "Removed from Favorites"
    }

    // MARK: playing one song

    private func startEntry(at p: Int) {
        guard order.indices.contains(p) else {
            stopEverything()
            return
        }
        pos = p
        generation += 1
        let gen = generation
        let entry = entries[order[p]]
        current = entry
        positionMs = 0
        durationMs = entry.durationMs
        artwork = nil
        lastArtTitle = ""
        spotifyWatch = nil

        switch entry.source {
        case .local:
            playLocal(entry, gen: gen)
        case .appleMusic:
            playApple(entry, gen: gen)
        case .spotify:
            playSpotify(entry, gen: gen)
        case .youtubeMusic:
            missed("YouTube Music can't be controlled on iPhone: skipped \(entry.title)")
        case .other:
            missed("\(entry.title) is from an app iPhone can't control: skipped")
        }
        publishWidget(force: true)
        saveSession()
    }

    private func playLocal(_ entry: Entry, gen: Int) {
        var fileName = entry.fileName.flatMap { library.fileExists(entry) ? $0 : nil }
        if fileName == nil, let match = library.match(title: entry.title, artist: entry.artist) {
            if match.source == .appleMusic {
                // Not imported, but the same song is in the Music library.
                playApple(match, gen: gen)
                return
            }
            fileName = match.fileName
            if let name = match.fileName { playlists.setFileName(name, for: [entry.id]) }
        }
        guard let name = fileName else {
            missed("\(entry.title) is not on this iPhone: skipped")
            return
        }
        apple.stop()
        spotify.pause()
        keepAlive.stop()
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [])
        try? session.setActive(true)
        do {
            try local.load(MusicLibrary.url(forFile: name))
        } catch {
            missed("\(entry.title) could not be opened: skipped")
            return
        }
        local.play()
        activeSource = .local
        isPlaying = true
        barsAreLive = true
        durationMs = local.durationMs
        misses = 0
        Task {
            let art = await MusicLibrary.artwork(forFile: name)
            guard gen == self.generation else { return }
            self.setArtwork(art)
        }
        updateNowPlayingInfo()
    }

    private func playApple(_ entry: Entry, gen: Int) {
        local.stop()
        spotify.pause()
        keepAlive.start()
        let started = apple.play(entry) { [weak self] in
            guard let self, gen == self.generation else { return }
            self.missed("Apple Music could not play \(entry.title): skipped")
        }
        guard started else {
            missed("\(entry.title) is not in your Music library: skipped")
            return
        }
        activeSource = .appleMusic
        isPlaying = true
        barsAreLive = false
        misses = 0
        clearNowPlayingInfo()
    }

    private func playSpotify(_ entry: Entry, gen: Int) {
        local.stop()
        apple.stop()
        keepAlive.start()
        activeSource = .spotify
        barsAreLive = false
        clearNowPlayingInfo()
        spotifyWatch = SpotifyWatch(uri: entry.mediaId, title: entry.title, generation: gen)

        let request: (String) -> Void = { [weak self] uri in
            guard let self, gen == self.generation else { return }
            self.spotifyWatch?.uri = uri
            self.spotify.play(uri: uri) { [weak self] ok in
                guard let self, gen == self.generation else { return }
                if !ok { self.missed("Connect Spotify in Settings to play \(entry.title)") }
            }
        }
        if let uri = entry.mediaId, uri.hasPrefix("spotify:") {
            request(uri)
        } else if spotify.tokenValid {
            Task {
                let uri = await self.spotify.findURI(title: entry.title, artist: entry.artist)
                guard gen == self.generation else { return }
                guard let uri else {
                    self.missed("\(entry.title) was not found on Spotify: skipped")
                    return
                }
                self.rememberSpotifyURI(uri, for: entry)
                request(uri)
            }
        } else {
            missed("Open Settings → Spotify to connect, then \(entry.title) can play")
            return
        }
        // Never play something else in its place: give up on this song after 15 s.
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self, gen == self.generation, let w = self.spotifyWatch, !w.armed else { return }
            self.missed("Spotify did not start \(entry.title): skipped")
        }
    }

    private func rememberSpotifyURI(_ uri: String, for entry: Entry) {
        let key = TrackMatcher.key(entry.title, entry.artist)
        var ids: [UUID: String] = [:]
        for p in playlists.playlists {
            for e in p.entries where e.source == .spotify && e.mediaId == nil && TrackMatcher.key(e.title, e.artist) == key {
                ids[e.id] = uri
            }
        }
        ids[entry.id] = uri
        playlists.setMediaIds(ids)
        if let i = entries.firstIndex(where: { $0.id == entry.id }) { entries[i].mediaId = uri }
    }

    private func pauseCurrent() {
        switch activeSource ?? .other {
        case .local: local.pause()
        case .appleMusic: apple.pause()
        case .spotify: spotify.pause()
        default: break
        }
        isPlaying = false
        publishWidget(force: true)
        updateNowPlayingInfo()
    }

    private func resumeCurrent() {
        guard current != nil else {
            if !order.isEmpty { startEntry(at: pos) }
            return
        }
        switch activeSource ?? .other {
        case .local where local.hasFile:
            local.resume()
        case .appleMusic:
            apple.resume()
        case .spotify where spotify.isConnected:
            spotify.resume()
        default:
            startEntry(at: pos)
            return
        }
        isPlaying = true
        publishWidget(force: true)
        updateNowPlayingInfo()
    }

    private func stopEverything() {
        local.stop()
        apple.stop()
        if activeSource == .spotify { spotify.pause() }
        keepAlive.stop()
        isPlaying = false
        publishWidget(force: true)
        updateNowPlayingInfo()
    }

    /// A song that cannot be played here: say so and move on; stop when nothing can be played.
    private func missed(_ reason: String) {
        toast = reason
        misses += 1
        if misses >= order.count {
            toast = "Nothing in this playlist can be played on iPhone right now"
            stopEverything()
            return
        }
        let gen = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            guard let self, gen == self.generation else { return }
            self.advance(auto: false)
        }
    }

    private func songEnded(source: SourceKind) {
        guard activeSource == source else { return }
        advance(auto: true)
    }

    /// [auto]: the song ended by itself ("repeat one" plays it again). At the end of the list,
    /// "repeat all" starts over (reshuffled when shuffle is on).
    private func advance(auto: Bool) {
        if auto { misses = 0 }
        if auto && settings.repeatMode == .one {
            startEntry(at: pos)
        } else if pos + 1 < order.count {
            startEntry(at: pos + 1)
        } else if settings.repeatMode != .off {
            if settings.shuffle { order.shuffle() }
            startEntry(at: 0)
        } else {
            stopEverything()
        }
    }

    // MARK: source callbacks

    private func appleChanged() {
        guard activeSource == .appleMusic else { return }
        isPlaying = apple.isPlaying
        let d = apple.durationMs
        if d > 0 { durationMs = d }
        if let c = current, lastArtTitle != c.title, let art = apple.artwork {
            lastArtTitle = c.title
            setArtwork(art)
        }
        publishWidget(force: false)
    }

    private func spotifyChanged(_ state: SpotifyState) {
        guard activeSource == .spotify, var w = spotifyWatch, w.generation == generation else { return }
        let matches = (w.uri != nil && state.uri == w.uri) || TrackMatcher.sameTitle(w.title, state.name)
        if !w.armed {
            guard matches, !state.isPaused else { return }
            w.armed = true
            spotifyWatch = w
            misses = 0
            if let c = current, c.mediaId == nil, state.uri.hasPrefix("spotify:track:") { rememberSpotifyURI(state.uri, for: c) }
            // A chosen song always starts from the beginning, even if Spotify resumed it.
            let sinceRequest = Int64(Date().timeIntervalSince(w.startedAt) * 1000)
            if state.positionMs > sinceRequest + 3000 { spotify.seek(toMs: 0) }
            let gen = generation
            spotify.fetchImage(for: state.track) { [weak self] image in
                guard let self, gen == self.generation else { return }
                self.setArtwork(image)
            }
        } else {
            let atEnd = state.isPaused && state.durationMs > 0 && state.positionMs >= state.durationMs - 2500
            if !matches || atEnd {
                spotifyWatch = nil
                spotify.pause()
                advance(auto: true)
                return
            }
        }
        isPlaying = !state.isPaused
        positionMs = state.positionMs
        if state.durationMs > 0 { durationMs = state.durationMs }
        publishWidget(force: false)
    }

    // MARK: ticking

    private func tick() {
        tickCount += 1
        if tickCount % 8 == 0 {
            switch activeSource ?? .other {
            case .local: positionMs = local.positionMs
            case .appleMusic: positionMs = apple.positionMs
            case .spotify where isPlaying: positionMs += 267
            default: break
            }
        }
        if tickCount % 30 == 0 { apple.tick() }
        if activeSource == .local {
            if !isPlaying { bars = bars.map { max($0 * 0.9, 0.04) } }
        } else {
            animateAmbient()
        }
    }

    /// iOS does not let apps hear Apple Music or Spotify, so their bars are a gentle animation.
    private func animateAmbient() {
        let n = bars.count
        guard isPlaying, activeSource != nil else {
            bars = bars.map { max($0 * 0.9, 0.04) }
            return
        }
        ambientPhase += 1.0 / 30.0
        let beat = pow(max(0, sin(ambientPhase * .pi * 2 * 1.0)), 6)
        let gain = settings.sensitivity
        bars = (0..<n).map { i in
            let x = Double(i) / Double(n)
            let wave = 0.5 + 0.5 * sin(ambientPhase * (1.3 + x * 2.1) + Double(i) * 0.7)
            let shape = 1.0 - pow(x - 0.25, 2) * 1.2
            let v = (0.18 + 0.42 * wave * shape + 0.3 * beat * (1 - x)) * gain
            return Float(min(max(v, 0.04), 1))
        }
    }

    private func setArtwork(_ image: UIImage?) {
        artwork = image
        artAccentHex = image?.accentHex ?? 0x6B4FD8
        publishWidget(force: true, artChanged: true)
        updateNowPlayingInfo()
    }

    // MARK: widget

    func publishWidget(force: Bool, artChanged: Bool = false) {
        let c = current
        let key = "\(c?.id.uuidString ?? "")|\(isPlaying)|\(artwork != nil)|\(accentHex)|\(settings.glass)|\(settings.visualizerStyle.rawValue)"
        if !force && key == lastWidgetKey { return }
        lastWidgetKey = key
        if artChanged {
            if let url = WidgetSnapshot.artURL {
                if let data = artwork?.preparingThumbnail(of: CGSize(width: 300, height: 300))?.jpegData(compressionQuality: 0.8) {
                    try? data.write(to: url, options: .atomic)
                } else {
                    try? FileManager.default.removeItem(at: url)
                }
            }
        }
        let source = c?.source
        let snap = WidgetSnapshot(
            title: c?.title ?? WidgetSnapshot.empty.title,
            artist: c?.artist ?? WidgetSnapshot.empty.artist,
            sourceLabel: source?.label ?? "Ultimate",
            sourceColorHex: source?.colorHex ?? 0xC4A8FF,
            isPlaying: isPlaying,
            accentHex: accentHex,
            bars: stride(from: 0, to: bars.count, by: max(bars.count / 16, 1)).map { max(bars[$0], 0.12) },
            hasArt: artwork != nil,
            playlistName: sessionName,
            glass: settings.glass,
            visualizerStyle: settings.visualizerStyle.rawValue,
            updatedAt: Date()
        )
        snap.save()
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetSnapshot.kind)
    }

    // MARK: lock screen (our own player)

    private func setUpRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { _ in
            Task { @MainActor in PlaybackHub.shared.setPlaying(true) }
            return .success
        }
        center.pauseCommand.addTarget { _ in
            Task { @MainActor in PlaybackHub.shared.setPlaying(false) }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { _ in
            Task { @MainActor in PlaybackHub.shared.togglePlay() }
            return .success
        }
        center.nextTrackCommand.addTarget { _ in
            Task { @MainActor in PlaybackHub.shared.next() }
            return .success
        }
        center.previousTrackCommand.addTarget { _ in
            Task { @MainActor in PlaybackHub.shared.previous() }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let ms = Int64(e.positionTime * 1000)
            Task { @MainActor in PlaybackHub.shared.seek(toMs: ms) }
            return .success
        }
    }

    private func updateNowPlayingInfo() {
        guard activeSource == .local, let c = current else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: c.title,
            MPMediaItemPropertyArtist: c.artist,
            MPMediaItemPropertyPlaybackDuration: Double(durationMs) / 1000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(local.positionMs) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        if let name = sessionName { info[MPMediaItemPropertyAlbumTitle] = name }
        if let art = artwork {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: art.size) { _ in art }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func clearNowPlayingInfo() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    // MARK: remembering the session (so the widget's play button works after a restart)

    private struct SavedSession: Codable {
        var name: String?
        var ids: [UUID]
        var entries: [Entry]
        var order: [Int]
        var pos: Int
    }

    private func saveSession() {
        let s = SavedSession(name: sessionName, ids: sessionPlaylistIds, entries: entries, order: order, pos: pos)
        if let data = try? JSONEncoder().encode(s) { UserDefaults.standard.set(data, forKey: "session") }
    }

    private func restoreSession() {
        guard let data = UserDefaults.standard.data(forKey: "session"),
              let s = try? JSONDecoder().decode(SavedSession.self, from: data),
              !s.entries.isEmpty, s.order.count == s.entries.count else { return }
        sessionName = s.name
        sessionPlaylistIds = s.ids
        entries = s.entries
        order = s.order
        pos = min(max(s.pos, 0), s.order.count - 1)
        current = entries[order[pos]]
        durationMs = current?.durationMs ?? 0
    }

    /// Called when the app comes to the screen.
    func appBecameActive() {
        spotify.reconnectIfLinked()
        Task { await library.reloadFiles() }
        if library.appleAccess == .authorized { library.reloadApple() }
    }
}
