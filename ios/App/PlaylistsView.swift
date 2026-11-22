import CoreImage.CIFilterBuiltins
import SwiftUI

struct PlaylistsView: View {
    @Environment(PlaybackHub.self) private var hub
    var openPlayer: () -> Void

    @State private var creating = false
    @State private var newName = ""
    @State private var importing = false
    @State private var mixMode = false
    @State private var selected: Set<UUID> = []

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if mixMode { mixBar }
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)], spacing: 14) {
                        ForEach(hub.playlists.sorted) { p in
                            if mixMode {
                                Button { toggle(p.id) } label: { PlaylistCard(playlist: p, selected: selected.contains(p.id)) }
                                    .buttonStyle(.plain)
                            } else {
                                NavigationLink(value: p.id) { PlaylistCard(playlist: p, selected: false) }
                                    .buttonStyle(.plain)
                                    .contextMenu {
                                        Button("Play", systemImage: "play.fill") { hub.play(playlist: p); openPlayer() }
                                        Button("Shuffle", systemImage: "shuffle") { hub.play(playlist: p, forceShuffle: true); openPlayer() }
                                        if !p.isFavorites {
                                            Button("Delete", systemImage: "trash", role: .destructive) { hub.playlists.delete(p.id) }
                                        }
                                    }
                            }
                        }
                    }
                }
                .padding(20)
            }
            .navigationTitle("Playlists")
            .navigationDestination(for: UUID.self) { id in
                PlaylistDetailView(playlistId: id, openPlayer: openPlayer)
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(mixMode ? "Done" : "Mix") {
                        mixMode.toggle()
                        selected = []
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { importing = true } label: { Image(systemName: "qrcode.viewfinder") }
                    Button { creating = true } label: { Image(systemName: "plus") }
                }
            }
            .alert("New playlist", isPresented: $creating) {
                TextField("Name", text: $newName)
                Button("Create") {
                    hub.playlists.create(name: newName.trimmingCharacters(in: .whitespaces))
                    newName = ""
                }
                Button("Cancel", role: .cancel) { newName = "" }
            }
            .sheet(isPresented: $importing) { ImportCodeSheet(initialCode: UIPasteboard.general.string ?? "") }
        }
    }

    private var mixBar: some View {
        Card {
            Text("Mix playlists").font(Palette.heading(16)).foregroundStyle(Palette.paper)
            Text("Pick two or more. Play them shuffled together, or save the mix as a new playlist.")
                .font(Palette.body(13)).foregroundStyle(Palette.mist)
            HStack {
                Button {
                    let lists = hub.playlists.sorted.filter { selected.contains($0.id) }
                    hub.playMix(lists)
                    mixMode = false
                    openPlayer()
                } label: { Label("Play mix", systemImage: "shuffle") }
                    .buttonStyle(.borderedProminent).tint(Palette.violet)
                Button {
                    let lists = hub.playlists.sorted.filter { selected.contains($0.id) }
                    let id = hub.playlists.create(name: lists.map(\.name).joined(separator: " + "))
                    for l in lists { hub.playlists.merge(l.id, into: id) }
                    hub.toast = "Saved as a new playlist"
                    mixMode = false
                } label: { Label("Save", systemImage: "square.and.arrow.down") }
                    .buttonStyle(.bordered).tint(Palette.lavender)
            }
            .disabled(selected.count < 2)
        }
    }

    private func toggle(_ id: UUID) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
    }
}

struct PlaylistBadge: View {
    let playlist: Playlist
    var size: CGFloat = 44

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
                .fill(LinearGradient(colors: [playlist.color, playlist.color.opacity(0.45)], startPoint: .topLeading, endPoint: .bottomTrailing))
            if !playlist.emoji.isEmpty {
                Text(playlist.emoji).font(.system(size: size * 0.48))
            } else {
                Image(systemName: playlist.isFavorites ? "heart.fill" : "music.note.list")
                    .font(.system(size: size * 0.4, weight: .semibold)).foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
    }
}

struct PlaylistCard: View {
    @Environment(PlaybackHub.self) private var hub
    let playlist: Playlist
    let selected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack(alignment: .topTrailing) {
                ZStack {
                    LinearGradient(colors: [playlist.color, playlist.color.opacity(0.35), Palette.night.opacity(0.4)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    if !playlist.emoji.isEmpty {
                        Text(playlist.emoji).font(.system(size: 54))
                    } else {
                        Image(systemName: playlist.isFavorites ? "heart.fill" : "music.note.list")
                            .font(.system(size: 40, weight: .semibold)).foregroundStyle(.white.opacity(0.9))
                    }
                }
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                if playlist.pinned {
                    Image(systemName: "pin.fill").font(.system(size: 12)).foregroundStyle(.white).padding(8)
                }
                if selected {
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 24)).foregroundStyle(Palette.paper, Palette.violet).padding(8)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(playlist.name).font(Palette.heading(15)).foregroundStyle(Palette.paper).lineLimit(1)
                Text(playlist.summary).font(Palette.body(12)).foregroundStyle(Palette.mist).lineLimit(1)
            }
            HStack(spacing: 4) {
                ForEach(sources, id: \.self) { s in Circle().fill(s.color).frame(width: 7, height: 7) }
            }
            .frame(height: 8)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(Color.white.opacity(selected ? 0.16 : 0.06)))
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(selected ? Palette.lavender : Color.white.opacity(0.1), lineWidth: selected ? 2 : 1))
    }

    private var sources: [SourceKind] {
        SourceKind.allCases.filter { kind in playlist.entries.contains { $0.source == kind } }
    }
}

// MARK: - Detail

struct PlaylistDetailView: View {
    @Environment(PlaybackHub.self) private var hub
    @Environment(\.dismiss) private var dismiss
    let playlistId: UUID
    var openPlayer: () -> Void

    @State private var editing = false
    @State private var sharing = false
    @State private var merging = false
    @State private var confirmDelete = false

    private var playlist: Playlist? { hub.playlists.playlist(playlistId) }

    var body: some View {
        Group {
            if let p = playlist {
                List {
                    header(p).listRowBackground(Color.clear).listRowSeparator(.hidden)
                    if p.entries.isEmpty {
                        Text("Add songs from the Library tab (long-press or swipe a song).")
                            .font(Palette.body(14)).foregroundStyle(Palette.mist)
                            .listRowBackground(Color.clear)
                    }
                    ForEach(Array(p.entries.enumerated()), id: \.element.id) { index, e in
                        SongRow(title: e.title, artist: e.artist, duration: e.durationMs, source: e.source, missing: isMissing(e))
                            .contentShape(Rectangle())
                            .onTapGesture {
                                hub.play(playlist: p, startAt: index)
                                openPlayer()
                            }
                            .contextMenu { entryMenu(e) }
                            .listRowBackground(hub.current?.id == e.id ? Palette.violet.opacity(0.25) : Color.white.opacity(0.04))
                    }
                    .onMove { hub.playlists.moveEntries(from: $0, to: $1, in: p.id) }
                    .onDelete { hub.playlists.removeEntries(at: $0, from: p.id) }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .navigationTitle(p.name)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        EditButton()
                        Menu {
                            Button("Edit details", systemImage: "pencil") { editing = true }
                            Button("Share code", systemImage: "qrcode") { sharing = true }
                            Button("Duplicate", systemImage: "plus.square.on.square") {
                                hub.playlists.duplicate(p.id, name: "\(p.name) copy")
                                hub.toast = "Copied"
                            }
                            Button("Add into another playlist…", systemImage: "arrow.triangle.merge") { merging = true }
                            if !p.isFavorites {
                                Button("Delete", systemImage: "trash", role: .destructive) { confirmDelete = true }
                            }
                        } label: { Image(systemName: "ellipsis.circle") }
                    }
                }
                .sheet(isPresented: $editing) { EditPlaylistSheet(playlist: p) }
                .sheet(isPresented: $sharing) { ShareCodeSheet(playlist: p) }
                .sheet(isPresented: $merging) { MergeSheet(source: p) }
                .confirmationDialog("Delete \(p.name)?", isPresented: $confirmDelete, titleVisibility: .visible) {
                    Button("Delete", role: .destructive) {
                        hub.playlists.delete(p.id)
                        dismiss()
                    }
                }
            } else {
                EmptyState(symbol: "questionmark", title: "Playlist not found", text: "It may have been deleted.", action: nil)
            }
        }
    }

    private func header(_ p: Playlist) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 16) {
                PlaylistBadge(playlist: p, size: 96)
                VStack(alignment: .leading, spacing: 4) {
                    Text(p.name).font(Palette.display(24)).foregroundStyle(Palette.paper).lineLimit(2)
                    if !p.description.isEmpty {
                        Text(p.description).font(Palette.body(13)).foregroundStyle(Palette.mist).lineLimit(3)
                    }
                    Text(p.summary).font(Palette.body(12)).foregroundStyle(Palette.mist)
                }
            }
            HStack(spacing: 12) {
                Button {
                    hub.play(playlist: p)
                    openPlayer()
                } label: { Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).tint(Palette.violet)
                Button {
                    hub.play(playlist: p, forceShuffle: true)
                    openPlayer()
                } label: { Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered).tint(Palette.lavender)
            }
            .disabled(p.entries.isEmpty)
            let blocked = p.entries.filter { !$0.source.playableOnIOS }.count
            let missing = p.entries.filter { isMissing($0) }.count
            if blocked > 0 {
                Text("\(blocked) YouTube Music song\(blocked == 1 ? "" : "s") will be skipped: iPhone does not let apps control YouTube Music.")
                    .font(Palette.body(12)).foregroundStyle(Palette.ember)
            }
            if missing > 0 {
                Text("\(missing) local song\(missing == 1 ? " is" : "s are") not on this iPhone. Long-press a song to search for it.")
                    .font(Palette.body(12)).foregroundStyle(Palette.ember)
            }
        }
        .padding(.vertical, 8)
    }

    private func isMissing(_ e: Entry) -> Bool {
        e.source == .local && !hub.library.fileExists(e) && hub.library.match(title: e.title, artist: e.artist) == nil
    }

    @ViewBuilder
    private func entryMenu(_ e: Entry) -> some View {
        Button("Add to Favorites", systemImage: "heart") { hub.playlists.toggleFavorite(e) }
        let q = "\(e.title) \(e.artist)".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        if e.source != .spotify {
            Button("Search on Spotify", systemImage: "magnifyingglass") {
                if let url = URL(string: "spotify:search:\(q)") { UIApplication.shared.open(url) }
            }
        }
        if e.source != .appleMusic {
            Button("Search on Apple Music", systemImage: "magnifyingglass") {
                if let url = URL(string: "https://music.apple.com/search?term=\(q)") { UIApplication.shared.open(url) }
            }
        }
        if e.source == .youtubeMusic {
            Button("Open in YouTube Music", systemImage: "play.rectangle") {
                if let url = URL(string: "https://music.youtube.com/search?q=\(q)") { UIApplication.shared.open(url) }
            }
        }
    }
}

// MARK: - Edit

struct EditPlaylistSheet: View {
    @Environment(PlaybackHub.self) private var hub
    @Environment(\.dismiss) private var dismiss
    @State var playlist: Playlist
    @State private var color: Color

    init(playlist: Playlist) {
        _playlist = State(initialValue: playlist)
        _color = State(initialValue: playlist.color)
    }

    private let presets: [UInt32] = [0x6B4FD8, 0xC4A8FF, 0xFF5E7A, 0xFF9F6E, 0xFFC857, 0x1ED760, 0x4FC3F7, 0x8FB8FF]

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Name", text: $playlist.name)
                    TextField("Description", text: $playlist.description, axis: .vertical).lineLimit(2...4)
                }
                Section("Cover") {
                    HStack {
                        PlaylistBadge(playlist: playlist, size: 56)
                        TextField("Emoji", text: $playlist.emoji)
                            .onChange(of: playlist.emoji) { _, v in if v.count > 1 { playlist.emoji = String(v.suffix(1)) } }
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(presets, id: \.self) { hex in
                                Circle().fill(Color(hex: hex)).frame(width: 32, height: 32)
                                    .overlay(Circle().stroke(.white, lineWidth: playlist.colorHex == hex ? 3 : 0))
                                    .onTapGesture { playlist.colorHex = hex }
                            }
                            ColorPicker("", selection: $color, supportsOpacity: false)
                                .labelsHidden()
                                .onChange(of: color) { _, c in playlist.colorHex = c.hexValue }
                        }
                        .padding(.vertical, 4)
                    }
                }
                Section("Playback") {
                    Toggle("Pin to top", isOn: $playlist.pinned)
                    Toggle("Shuffle by default", isOn: $playlist.defaultShuffle)
                }
            }
            .navigationTitle("Edit playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if playlist.name.trimmingCharacters(in: .whitespaces).isEmpty { playlist.name = "Playlist" }
                        hub.playlists.update(playlist)
                        dismiss()
                    }
                }
            }
        }
    }
}

extension Color {
    var hexValue: UInt32 {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(self).getRed(&r, green: &g, blue: &b, alpha: &a)
        let c = { (v: CGFloat) -> UInt32 in UInt32(max(0, min(255, (v * 255).rounded()))) }
        return (c(r) << 16) | (c(g) << 8) | c(b)
    }
}

// MARK: - Merge

struct MergeSheet: View {
    @Environment(PlaybackHub.self) private var hub
    @Environment(\.dismiss) private var dismiss
    let source: Playlist

    var body: some View {
        NavigationStack {
            List(hub.playlists.sorted.filter { $0.id != source.id }) { p in
                Button {
                    let n = hub.playlists.merge(source.id, into: p.id)
                    hub.toast = "\(n) song\(n == 1 ? "" : "s") added to \(p.name)"
                    dismiss()
                } label: {
                    HStack {
                        PlaylistBadge(playlist: p, size: 36)
                        Text(p.name).foregroundStyle(Palette.paper)
                    }
                }
            }
            .navigationTitle("Add \(source.name) into…")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - Share codes

struct ShareCodeSheet: View {
    @Environment(PlaybackHub.self) private var hub
    @Environment(\.dismiss) private var dismiss
    let playlist: Playlist
    @State private var pin = ""
    @State private var code: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("The code holds the song list (titles, artists, Spotify ids). Local files travel as their names only. It opens in the Android and iPhone apps.")
                        .font(Palette.body(13)).foregroundStyle(Palette.mist)
                    SecureField("PIN (optional)", text: $pin).keyboardType(.numberPad)
                    Button(code == nil ? "Create code" : "Create again") {
                        code = ShareCode.encode(hub.playlists.shared(playlist), pin: pin)
                    }
                }
                if let code {
                    Section("Code") {
                        if let qr = qrImage(ShareCode.deepLink + code) {
                            Image(uiImage: qr).interpolation(.none).resizable().scaledToFit()
                                .frame(maxWidth: 240).frame(maxWidth: .infinity)
                                .padding(8).background(Color.white).clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                        Text(code).font(.system(size: 12, design: .monospaced)).textSelection(.enabled).lineLimit(6)
                        Button("Copy", systemImage: "doc.on.doc") {
                            UIPasteboard.general.string = code
                            hub.toast = "Code copied"
                        }
                        ShareLink(item: "Playlist \"\(playlist.name)\" for Ultimate Music Widget:\n\(code)\(pin.isEmpty ? "" : "\n(PIN required)")") {
                            Label("Send", systemImage: "square.and.arrow.up")
                        }
                    }
                }
            }
            .navigationTitle("Share \(playlist.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    private func qrImage(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

struct ImportCodeSheet: View {
    @Environment(PlaybackHub.self) private var hub
    @Environment(\.dismiss) private var dismiss
    @State var initialCode: String
    @State private var text = ""
    @State private var pin = ""
    @State private var result: ShareCode.Result?
    @State private var preview: SharedPlaylist?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Paste a UMW1- code", text: $text, axis: .vertical).lineLimit(2...5)
                        .font(.system(size: 13, design: .monospaced))
                    Button("Paste", systemImage: "doc.on.clipboard") { text = UIPasteboard.general.string ?? "" }
                    if case .needsPin = result { SecureField("PIN", text: $pin).keyboardType(.numberPad) }
                    if case .wrongPin = result {
                        SecureField("PIN", text: $pin).keyboardType(.numberPad)
                        Text("Wrong PIN").foregroundStyle(Palette.ember)
                    }
                    if case .invalid = result { Text("This is not a valid playlist code").foregroundStyle(Palette.ember) }
                    Button("Open code") { open() }.disabled(text.isEmpty)
                }
                if let p = preview {
                    Section("\(p.emoji) \(p.name)") {
                        if !p.description.isEmpty { Text(p.description).foregroundStyle(Palette.mist) }
                        ForEach(Array(p.entries.enumerated()), id: \.offset) { _, e in
                            let entry = resolve(e)
                            SongRow(title: e.title, artist: e.artist, duration: e.durationMs, source: entry.source,
                                    missing: entry.source == .local && entry.fileName == nil && entry.mediaId == nil)
                        }
                    }
                    Section {
                        Button("Import \(p.entries.count) songs") {
                            hub.playlists.importShared(p, entries: p.entries.map(resolve))
                            hub.toast = "Imported \(p.name)"
                            dismiss()
                        }
                        .bold()
                    }
                }
            }
            .navigationTitle("Playlist code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .onAppear {
                if ShareCode.looksLikeCode(initialCode) {
                    text = initialCode
                    open()
                }
            }
        }
    }

    private func open() {
        let r = ShareCode.decode(text, pin: pin)
        result = r
        if case let .ok(p) = r { preview = p } else { preview = nil }
    }

    /// Local songs are matched to this iPhone's files or Music library by title.
    private func resolve(_ e: SharedEntry) -> Entry {
        let kind = SourceKind(package: e.sourcePackage)
        if kind == .local, let match = hub.library.match(title: e.title, artist: e.artist) {
            var m = match
            m.durationMs = m.durationMs > 0 ? m.durationMs : e.durationMs
            return m
        }
        return Entry(source: kind, title: e.title, artist: e.artist, durationMs: e.durationMs, mediaId: e.mediaId)
    }
}
