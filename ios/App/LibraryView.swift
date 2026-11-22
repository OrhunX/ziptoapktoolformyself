import MediaPlayer
import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    enum LibSection: String, CaseIterable, Identifiable {
        case files = "Files", apple = "Apple Music", spotify = "Spotify"
        var id: String { rawValue }
    }

    @Environment(PlaybackHub.self) private var hub
    @State private var section: LibSection = .files
    @State private var query = ""
    @State private var importing = false
    @State private var adding: [Entry]?

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Picker("Source", selection: $section) {
                    ForEach(LibSection.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 20)

                switch section {
                case .files: filesList
                case .apple: appleList
                case .spotify: SpotifySearchList(query: $query, adding: $adding)
                }
            }
            .navigationTitle("Library")
            .toolbar {
                if section == .files {
                    Button {
                        importing = true
                    } label: {
                        Label("Import", systemImage: "plus")
                    }
                }
            }
            .searchable(text: $query, prompt: section == .spotify ? "Search Spotify" : "Search")
            .scrollContentBackground(.hidden)
            .background(Color.clear)
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.audio], allowsMultipleSelection: true) { result in
            guard case let .success(urls) = result else { return }
            Task {
                let n = await hub.library.importFiles(urls)
                hub.toast = n == 1 ? "1 song imported" : "\(n) songs imported"
            }
        }
        .sheet(isPresented: Binding(get: { adding != nil }, set: { if !$0 { adding = nil } })) {
            AddToPlaylistSheet(entries: adding ?? [])
        }
    }

    // MARK: files

    private var filteredFiles: [LocalSong] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return hub.library.files }
        return hub.library.files.filter { $0.title.localizedCaseInsensitiveContains(q) || $0.artist.localizedCaseInsensitiveContains(q) }
    }

    @ViewBuilder
    private var filesList: some View {
        let songs = filteredFiles
        if hub.library.files.isEmpty {
            EmptyState(
                symbol: "folder.badge.plus",
                title: "Import your music",
                text: "Tap + to bring MP3, M4A, WAV or FLAC files in from the Files app. They play in the app's own player, with a real visualizer.",
                action: ("Import files", { importing = true })
            )
        } else {
            List {
                ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in
                    SongRow(title: song.title, artist: song.artist, duration: song.durationMs, source: .local)
                        .contentShape(Rectangle())
                        .onTapGesture { hub.playSongs(songs.map(\.entry), index: index, name: "Files") }
                        .contextMenu {
                            Button("Add to playlist…", systemImage: "text.badge.plus") { adding = [song.entry] }
                            Button("Add to Favorites", systemImage: "heart") { hub.playlists.toggleFavorite(song.entry) }
                            Button("Delete file", systemImage: "trash", role: .destructive) {
                                Task { await hub.library.deleteFile(song) }
                            }
                        }
                        .swipeActions(edge: .trailing) {
                            Button { adding = [song.entry] } label: { Label("Add", systemImage: "text.badge.plus") }
                                .tint(Palette.violet)
                        }
                        .listRowBackground(Color.white.opacity(0.04))
                }
            }
            .listStyle(.plain)
        }
    }

    // MARK: Apple Music

    private var filteredApple: [AppleSong] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return hub.library.appleSongs }
        return hub.library.appleSongs.filter { $0.title.localizedCaseInsensitiveContains(q) || $0.artist.localizedCaseInsensitiveContains(q) }
    }

    @ViewBuilder
    private var appleList: some View {
        switch hub.library.appleAccess {
        case .authorized:
            let songs = filteredApple
            if hub.library.appleSongs.isEmpty {
                EmptyState(symbol: "music.note", title: "No songs in your library", text: "Songs you add to your library in the Music app appear here.", action: nil)
            } else {
                List {
                    ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in
                        SongRow(title: song.title, artist: song.artist, duration: song.durationMs, source: .appleMusic)
                            .contentShape(Rectangle())
                            .onTapGesture { hub.playSongs(songs.map(\.entry), index: index, name: "Apple Music") }
                            .contextMenu {
                                Button("Add to playlist…", systemImage: "text.badge.plus") { adding = [song.entry] }
                                Button("Add to Favorites", systemImage: "heart") { hub.playlists.toggleFavorite(song.entry) }
                            }
                            .swipeActions(edge: .trailing) {
                                Button { adding = [song.entry] } label: { Label("Add", systemImage: "text.badge.plus") }
                                    .tint(Palette.violet)
                            }
                            .listRowBackground(Color.white.opacity(0.04))
                    }
                }
                .listStyle(.plain)
            }
        case .denied, .restricted:
            EmptyState(symbol: "lock", title: "Music library access is off", text: "Allow it in iPhone Settings → Privacy → Media & Apple Music.", action: ("Open Settings", {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }))
        default:
            EmptyState(symbol: "music.note.house", title: "Use your Music library", text: "Play songs from the Music app (including Apple Music) in your hub playlists.", action: ("Allow access", { hub.library.requestAppleAccess() }))
        }
    }
}

/// Spotify: search through the Web API (with the token from connecting) and add to playlists.
struct SpotifySearchList: View {
    @Environment(PlaybackHub.self) private var hub
    @Binding var query: String
    @Binding var adding: [Entry]?
    @State private var results: [SpotifyTrack] = []
    @State private var searching = false

    var body: some View {
        Group {
            if !hub.spotify.isLinked {
                EmptyState(symbol: "link", title: "Connect Spotify", text: "Approve the app once; then search Spotify here and add songs to your playlists. Picking a song needs Spotify Premium.", action: ("Connect", { hub.spotify.link() }))
            } else if !hub.spotify.tokenValid {
                EmptyState(symbol: "arrow.clockwise", title: "Refresh the Spotify connection", text: "Spotify's search permission lasts an hour. Refreshing opens Spotify for a moment.", action: ("Refresh", { hub.spotify.link() }))
            } else if results.isEmpty {
                EmptyState(symbol: "magnifyingglass", title: searching ? "Searching…" : "Search Spotify", text: "Type a song or artist in the search field.", action: nil)
            } else {
                List {
                    ForEach(Array(results.enumerated()), id: \.element.id) { index, track in
                        SongRow(title: track.name, artist: track.artist, duration: track.durationMs, source: .spotify)
                            .contentShape(Rectangle())
                            .onTapGesture { hub.playSongs(results.map(\.entry), index: index, name: "Spotify search") }
                            .contextMenu {
                                Button("Add to playlist…", systemImage: "text.badge.plus") { adding = [track.entry] }
                                Button("Add to Favorites", systemImage: "heart") { hub.playlists.toggleFavorite(track.entry) }
                            }
                            .swipeActions(edge: .trailing) {
                                Button { adding = [track.entry] } label: { Label("Add", systemImage: "text.badge.plus") }
                                    .tint(Palette.violet)
                            }
                            .listRowBackground(Color.white.opacity(0.04))
                    }
                }
                .listStyle(.plain)
            }
        }
        .task(id: query) {
            let q = query
            guard q.count >= 2 else {
                results = []
                return
            }
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            searching = true
            let found = await hub.spotify.search(q)
            guard !Task.isCancelled else { return }
            results = found
            searching = false
        }
    }
}

struct SongRow: View {
    let title: String
    let artist: String
    let duration: Int64
    let source: SourceKind
    var missing = false

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10, style: .continuous).fill(source.color.opacity(0.16))
                Image(systemName: missing ? "exclamationmark.triangle" : symbol)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(missing ? Palette.ember : source.color)
            }
            .frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Palette.body(15)).foregroundStyle(Palette.paper).lineLimit(1)
                Text(artist.isEmpty ? source.label : "\(artist) · \(source.label)")
                    .font(Palette.body(12)).foregroundStyle(Palette.mist).lineLimit(1)
            }
            Spacer()
            Text(Entry.format(ms: duration)).font(Palette.body(12)).foregroundStyle(Palette.mist).monospacedDigit()
        }
        .padding(.vertical, 4)
    }

    private var symbol: String {
        switch source {
        case .local: return "doc.richtext"
        case .appleMusic: return "music.note"
        case .spotify: return "dot.radiowaves.left.and.right"
        case .youtubeMusic: return "play.rectangle"
        case .other: return "app"
        }
    }
}

struct EmptyState: View {
    let symbol: String
    let title: String
    let text: String
    let action: (String, () -> Void)?

    var body: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: symbol).font(.system(size: 40, weight: .semibold)).foregroundStyle(Palette.lavender)
            Text(title).font(Palette.heading(19)).foregroundStyle(Palette.paper)
            Text(text).font(Palette.body(14)).foregroundStyle(Palette.mist).multilineTextAlignment(.center)
            if let action {
                Button(action.0, action: action.1)
                    .buttonStyle(.borderedProminent)
                    .tint(Palette.violet)
                    .padding(.top, 6)
            }
            Spacer()
        }
        .padding(.horizontal, 36)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Pick a playlist (or make a new one) for one or more songs.
struct AddToPlaylistSheet: View {
    @Environment(PlaybackHub.self) private var hub
    @Environment(\.dismiss) private var dismiss
    let entries: [Entry]
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        TextField("New playlist", text: $newName)
                        Button("Create") {
                            let id = hub.playlists.create(name: newName.trimmingCharacters(in: .whitespaces))
                            add(to: id)
                        }
                        .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
                Section("Playlists") {
                    ForEach(hub.playlists.sorted) { p in
                        Button {
                            add(to: p.id)
                        } label: {
                            HStack {
                                PlaylistBadge(playlist: p, size: 36)
                                Text(p.name).foregroundStyle(Palette.paper)
                                Spacer()
                                Text(p.summary).font(.caption).foregroundStyle(Palette.mist)
                            }
                        }
                    }
                }
            }
            .navigationTitle(entries.count == 1 ? "Add \"\(entries[0].title)\"" : "Add \(entries.count) songs")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func add(to id: UUID) {
        var added = 0
        for e in entries where hub.playlists.add(e, to: id) { added += 1 }
        let name = hub.playlists.playlist(id)?.name ?? "playlist"
        hub.toast = added == 0 ? "Already in \(name)" : "Added to \(name)"
        dismiss()
    }
}
