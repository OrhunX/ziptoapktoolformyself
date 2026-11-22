import AVFoundation
import Foundation
import MediaPlayer
import Observation
import UIKit

/// A music file imported into the app (Documents/Music).
struct LocalSong: Identifiable, Hashable {
    var id: String { fileName }
    let fileName: String
    let title: String
    let artist: String
    let durationMs: Int64

    var entry: Entry {
        Entry(source: .local, title: title, artist: artist, durationMs: durationMs, mediaId: nil, fileName: fileName)
    }
}

/// A song from the Music app library (bought, synced, or added from Apple Music).
struct AppleSong: Identifiable, Hashable {
    var id: UInt64 { persistentID }
    let persistentID: UInt64
    let title: String
    let artist: String
    let durationMs: Int64

    var entry: Entry {
        Entry(source: .appleMusic, title: title, artist: artist, durationMs: durationMs, mediaId: "pid:\(persistentID)")
    }
}

@Observable
@MainActor
final class MusicLibrary {
    private(set) var files: [LocalSong] = []
    private(set) var appleSongs: [AppleSong] = []
    private(set) var appleAccess: MPMediaLibraryAuthorizationStatus = MPMediaLibrary.authorizationStatus()

    nonisolated static let musicDir: URL = {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Music")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    init() {
        Task { await reloadFiles() }
        if appleAccess == .authorized { reloadApple() }
    }

    nonisolated static func url(forFile name: String) -> URL { musicDir.appendingPathComponent(name) }

    // MARK: files

    func reloadFiles() async {
        let names = ((try? FileManager.default.contentsOfDirectory(atPath: Self.musicDir.path)) ?? [])
            .filter { !$0.hasPrefix(".") }
            .sorted()
        var songs: [LocalSong] = []
        for name in names {
            songs.append(await Self.readSong(name))
        }
        files = songs.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
    }

    private static func readSong(_ name: String) async -> LocalSong {
        let asset = AVURLAsset(url: url(forFile: name))
        var title = (name as NSString).deletingPathExtension
        var artist = ""
        var duration: Int64 = 0
        if let loaded = try? await asset.load(.commonMetadata, .duration) {
            let (metadata, time) = loaded
            duration = Int64(CMTimeGetSeconds(time).isFinite ? CMTimeGetSeconds(time) * 1000 : 0)
            for item in metadata {
                guard let key = item.commonKey else { continue }
                if key == .commonKeyTitle, let v = try? await item.load(.stringValue), !v.isEmpty { title = v }
                if key == .commonKeyArtist, let v = try? await item.load(.stringValue), !v.isEmpty { artist = v }
            }
        }
        return LocalSong(fileName: name, title: title, artist: artist, durationMs: duration)
    }

    /// Copies picked files into the app. Returns how many were imported.
    func importFiles(_ urls: [URL]) async -> Int {
        var count = 0
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            var target = Self.url(forFile: url.lastPathComponent)
            var n = 1
            while FileManager.default.fileExists(atPath: target.path) {
                let base = url.deletingPathExtension().lastPathComponent
                target = Self.url(forFile: "\(base) \(n).\(url.pathExtension)")
                n += 1
            }
            do {
                try FileManager.default.copyItem(at: url, to: target)
                count += 1
            } catch {
                continue
            }
        }
        await reloadFiles()
        return count
    }

    func deleteFile(_ song: LocalSong) async {
        try? FileManager.default.removeItem(at: Self.url(forFile: song.fileName))
        await reloadFiles()
    }

    // MARK: Apple Music library

    func requestAppleAccess() {
        MPMediaLibrary.requestAuthorization { status in
            Task { @MainActor in
                self.appleAccess = status
                if status == .authorized { self.reloadApple() }
            }
        }
    }

    func reloadApple() {
        Task.detached(priority: .userInitiated) {
            let items = MPMediaQuery.songs().items ?? []
            let songs = items.map {
                AppleSong(
                    persistentID: $0.persistentID,
                    title: $0.title ?? "Unknown",
                    artist: $0.artist ?? "",
                    durationMs: Int64($0.playbackDuration * 1000)
                )
            }
            .sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
            await MainActor.run { self.appleSongs = songs }
        }
    }

    nonisolated static func mediaItem(persistentID: UInt64) -> MPMediaItem? {
        let query = MPMediaQuery.songs()
        query.addFilterPredicate(MPMediaPropertyPredicate(value: NSNumber(value: persistentID), forProperty: MPMediaItemPropertyPersistentID))
        return query.items?.first
    }

    // MARK: matching (songs from share codes or Android)

    /// Finds a song on this phone with the same title: imported files first, then the Music library.
    func match(title: String, artist: String) -> Entry? {
        if let f = files.first(where: { TrackMatcher.sameTitle(title, $0.title) && (artist.isEmpty || $0.artist.isEmpty || TrackMatcher.sameTitle(artist, $0.artist)) }) {
            return f.entry
        }
        if let a = appleSongs.first(where: { TrackMatcher.sameTitle(title, $0.title) && (artist.isEmpty || TrackMatcher.sameTitle(artist, $0.artist)) }) {
            return a.entry
        }
        return nil
    }

    func fileExists(_ entry: Entry) -> Bool {
        guard let name = entry.fileName else { return false }
        return FileManager.default.fileExists(atPath: Self.url(forFile: name).path)
    }

    /// Artwork of a local file, read from its tags.
    static func artwork(forFile name: String) async -> UIImage? {
        let asset = AVURLAsset(url: url(forFile: name))
        guard let metadata = try? await asset.load(.commonMetadata) else { return nil }
        for item in metadata where item.commonKey == .commonKeyArtwork {
            if let data = try? await item.load(.dataValue), let image = UIImage(data: data) { return image }
        }
        return nil
    }
}
