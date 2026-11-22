import Foundation
import Observation

/// All playlists, saved as JSON in Application Support. Favorites always exists.
@Observable
@MainActor
final class PlaylistStore {
    private(set) var playlists: [Playlist] = []

    private let fileURL: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("playlists.json")
    }()

    init() {
        load()
    }

    var favorites: Playlist? { playlists.first { $0.isFavorites } }

    /// Pinned first, Favorites next, then most recently played or created.
    var sorted: [Playlist] {
        playlists.sorted { a, b in
            if a.pinned != b.pinned { return a.pinned }
            if a.isFavorites != b.isFavorites { return a.isFavorites }
            return (a.lastPlayedAt ?? a.createdAt) > (b.lastPlayedAt ?? b.createdAt)
        }
    }

    func playlist(_ id: UUID) -> Playlist? { playlists.first { $0.id == id } }

    // MARK: playlists

    @discardableResult
    func create(name: String, emoji: String = "", colorHex: UInt32 = 0x6B4FD8, entries: [Entry] = []) -> UUID {
        let p = Playlist(name: name.isEmpty ? "New playlist" : name, emoji: emoji, colorHex: colorHex, entries: entries)
        playlists.append(p)
        save()
        return p.id
    }

    func update(_ playlist: Playlist) {
        guard let i = playlists.firstIndex(where: { $0.id == playlist.id }) else { return }
        playlists[i] = playlist
        save()
    }

    func delete(_ id: UUID) {
        playlists.removeAll { $0.id == id && !$0.isFavorites }
        save()
    }

    @discardableResult
    func duplicate(_ id: UUID, name: String) -> UUID? {
        guard let p = playlist(id) else { return nil }
        let copy = p.entries.map { e -> Entry in var c = e; c.id = UUID(); return c }
        return create(name: name, emoji: p.emoji, colorHex: p.colorHex, entries: copy)
    }

    /// Adds the songs of [sourceId] to [targetId], skipping songs it already has. Returns how many.
    @discardableResult
    func merge(_ sourceId: UUID, into targetId: UUID) -> Int {
        guard let source = playlist(sourceId), let i = playlists.firstIndex(where: { $0.id == targetId }) else { return 0 }
        var keys = Set(playlists[i].entries.map { TrackMatcher.key($0.title, $0.artist) })
        var added = 0
        for e in source.entries where keys.insert(TrackMatcher.key(e.title, e.artist)).inserted {
            var c = e
            c.id = UUID()
            playlists[i].entries.append(c)
            added += 1
        }
        save()
        return added
    }

    func markPlayed(_ ids: [UUID]) {
        let now = Date()
        for i in playlists.indices where ids.contains(playlists[i].id) { playlists[i].lastPlayedAt = now }
        save()
    }

    // MARK: entries

    /// Returns false when the song is already in the playlist.
    @discardableResult
    func add(_ entry: Entry, to id: UUID) -> Bool {
        guard let i = playlists.firstIndex(where: { $0.id == id }) else { return false }
        let key = TrackMatcher.key(entry.title, entry.artist)
        if playlists[i].entries.contains(where: { TrackMatcher.key($0.title, $0.artist) == key && $0.source == entry.source }) {
            return false
        }
        var e = entry
        e.id = UUID()
        playlists[i].entries.append(e)
        save()
        return true
    }

    func removeEntries(at offsets: IndexSet, from id: UUID) {
        guard let i = playlists.firstIndex(where: { $0.id == id }) else { return }
        for index in offsets.sorted(by: >) where index < playlists[i].entries.count {
            playlists[i].entries.remove(at: index)
        }
        save()
    }

    func moveEntries(from source: IndexSet, to destination: Int, in id: UUID) {
        guard let i = playlists.firstIndex(where: { $0.id == id }) else { return }
        playlists[i].entries.move(fromOffsets: source, toOffset: destination)
        save()
    }

    func isFavorite(title: String, artist: String) -> Bool {
        let key = TrackMatcher.key(title, artist)
        return favorites?.entries.contains { TrackMatcher.key($0.title, $0.artist) == key } ?? false
    }

    /// Adds to or removes from Favorites. Returns the new state.
    @discardableResult
    func toggleFavorite(_ entry: Entry) -> Bool {
        ensureFavorites()
        guard let i = playlists.firstIndex(where: { $0.isFavorites }) else { return false }
        let key = TrackMatcher.key(entry.title, entry.artist)
        if let j = playlists[i].entries.firstIndex(where: { TrackMatcher.key($0.title, $0.artist) == key }) {
            playlists[i].entries.remove(at: j)
            save()
            return false
        }
        var e = entry
        e.id = UUID()
        playlists[i].entries.append(e)
        save()
        return true
    }

    /// Remembers ids learned later (Spotify track ids), for every copy of the same song.
    func setMediaIds(_ ids: [UUID: String]) {
        guard !ids.isEmpty else { return }
        for p in playlists.indices {
            for e in playlists[p].entries.indices {
                if let id = ids[playlists[p].entries[e].id] { playlists[p].entries[e].mediaId = id }
            }
        }
        save()
    }

    func setFileName(_ fileName: String, for entryIds: Set<UUID>) {
        for p in playlists.indices {
            for e in playlists[p].entries.indices where entryIds.contains(playlists[p].entries[e].id) {
                playlists[p].entries[e].fileName = fileName
            }
        }
        save()
    }

    // MARK: sharing

    func shared(_ p: Playlist) -> SharedPlaylist {
        SharedPlaylist(
            name: p.name, description: p.description,
            color: Int32(bitPattern: 0xFF00_0000 | p.colorHex),
            emoji: p.emoji, from: "iPhone",
            entries: p.entries.map {
                SharedEntry(
                    sourcePackage: $0.source.package, sourceLabel: $0.source.label,
                    title: $0.title, artist: $0.artist, durationMs: $0.durationMs,
                    mediaId: $0.source == .spotify ? $0.mediaId : nil
                )
            }
        )
    }

    @discardableResult
    func importShared(_ shared: SharedPlaylist, entries: [Entry]) -> UUID {
        let color = UInt32(bitPattern: shared.color) & 0xFFFFFF
        let id = create(name: shared.name, emoji: shared.emoji, colorHex: color == 0 ? 0x6B4FD8 : color, entries: entries)
        if var p = playlist(id) {
            p.description = shared.description
            update(p)
        }
        return id
    }

    // MARK: disk

    private func ensureFavorites() {
        if !playlists.contains(where: { $0.isFavorites }) {
            playlists.insert(Playlist(name: "Favorites", emoji: "♥︎", colorHex: 0xFF5E7A, isFavorites: true), at: 0)
            save()
        }
    }

    private func load() {
        if let data = try? Data(contentsOf: fileURL),
           let list = try? JSONDecoder().decode([Playlist].self, from: data) {
            playlists = list
        }
        ensureFavorites()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(playlists) {
            try? data.write(to: fileURL, options: .atomic)
        }
    }
}
