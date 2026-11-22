import Foundation
import SwiftUI

/// Where a song plays. Android package names are kept for share codes, so the same code opens
/// on both platforms.
enum SourceKind: String, Codable, CaseIterable {
    case local, appleMusic, spotify, youtubeMusic, other

    static let spotifyPackage = "com.spotify.music"
    static let youtubeMusicPackage = "com.google.android.apps.youtube.music"
    static let youtubePackage = "com.google.android.youtube"
    static let appleMusicPackage = "com.apple.android.music"

    init(package: String?) {
        switch package {
        case nil, "": self = .local
        case SourceKind.spotifyPackage: self = .spotify
        case SourceKind.youtubeMusicPackage, SourceKind.youtubePackage: self = .youtubeMusic
        case SourceKind.appleMusicPackage: self = .appleMusic
        default: self = .other
        }
    }

    var package: String? {
        switch self {
        case .local: return nil
        case .spotify: return SourceKind.spotifyPackage
        case .youtubeMusic: return SourceKind.youtubeMusicPackage
        case .appleMusic: return SourceKind.appleMusicPackage
        case .other: return "other"
        }
    }

    var label: String {
        switch self {
        case .local: return "Local file"
        case .appleMusic: return "Apple Music"
        case .spotify: return "Spotify"
        case .youtubeMusic: return "YouTube Music"
        case .other: return "Other app"
        }
    }

    var colorHex: UInt32 {
        switch self {
        case .local: return 0xFFC857
        case .appleMusic: return 0xFF5E7A
        case .spotify: return 0x1ED760
        case .youtubeMusic: return 0xFF7A7A
        case .other: return 0x8FB8FF
        }
    }

    var color: Color { Color(hex: colorHex) }

    /// iPhone can play it: our own player, Apple Music, or Spotify through its SDK.
    var playableOnIOS: Bool { self == .local || self == .appleMusic || self == .spotify }
}

/// One song in a playlist.
///  - local: [fileName] is an imported file in Documents/Music (matched by title when missing)
///  - appleMusic: [mediaId] is the library persistent id ("pid:123") or a catalog id ("store:123")
///  - spotify: [mediaId] is "spotify:track:..." when known
struct Entry: Codable, Identifiable, Hashable {
    var id = UUID()
    var source: SourceKind
    var title: String
    var artist: String
    var durationMs: Int64 = 0
    var mediaId: String?
    var fileName: String?

    var durationText: String { Entry.format(ms: durationMs) }

    static func format(ms: Int64) -> String {
        guard ms > 0 else { return "" }
        let s = Int(ms / 1000)
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

enum RepeatMode: String, Codable, CaseIterable {
    case off, all, one

    var next: RepeatMode {
        switch self {
        case .off: return .all
        case .all: return .one
        case .one: return .off
        }
    }

    var symbol: String { self == .one ? "repeat.1" : "repeat" }
}

struct Playlist: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var description: String = ""
    var emoji: String = ""
    var colorHex: UInt32 = 0x6B4FD8
    var pinned = false
    var isFavorites = false
    var defaultShuffle = false
    var createdAt = Date()
    var lastPlayedAt: Date?
    var entries: [Entry] = []

    var color: Color { Color(hex: colorHex) }

    var totalDurationMs: Int64 { entries.reduce(0) { $0 + $1.durationMs } }

    var summary: String {
        let count = entries.count == 1 ? "1 song" : "\(entries.count) songs"
        let minutes = Int(totalDurationMs / 60_000)
        return minutes > 0 ? "\(count) · \(minutes) min" : count
    }
}

/// Normalizes titles so "Song (Remastered 2011)" matches "song".
enum TrackMatcher {
    static func normalize(_ s: String) -> String {
        var t = s.lowercased()
        for open in ["(", "[", " - "] {
            if let r = t.range(of: open), r.lowerBound > t.startIndex { t = String(t[..<r.lowerBound]) }
        }
        let allowed = t.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }
        return String(String.UnicodeScalarView(allowed))
    }

    static func sameTitle(_ a: String, _ b: String) -> Bool {
        let x = normalize(a), y = normalize(b)
        if x.isEmpty || y.isEmpty { return false }
        if x == y { return true }
        let (short, long) = x.count <= y.count ? (x, y) : (y, x)
        return short.count >= 4 && long.contains(short)
    }

    static func key(_ title: String, _ artist: String) -> String { normalize(title) + "|" + normalize(artist) }
}
