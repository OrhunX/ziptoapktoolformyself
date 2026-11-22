import Foundation

/// The app group shared by the app and the widget extension.
enum AppGroup {
    static let id = "group.com.sen0413.ultimatewidget"

    /// Shared defaults; when the group is not available (unsigned build), each side falls back
    /// to its own defaults and the widget simply shows its placeholder.
    static var defaults: UserDefaults { UserDefaults(suiteName: id) ?? .standard }

    static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id)
    }
}

/// What the widget shows: written by the app whenever the song, play state or look changes.
struct WidgetSnapshot: Codable, Equatable {
    var title: String
    var artist: String
    var sourceLabel: String
    var sourceColorHex: UInt32
    var isPlaying: Bool
    var accentHex: UInt32
    var bars: [Float]
    var hasArt: Bool
    var playlistName: String?
    var glass: Bool
    var visualizerStyle: String
    var updatedAt: Date

    static let kind = "UltimateMusicWidget"
    private static let key = "widget_snapshot_v1"

    static let empty = WidgetSnapshot(
        title: "Nothing playing",
        artist: "Open the app to start a playlist",
        sourceLabel: "Ultimate",
        sourceColorHex: 0xC4A8FF,
        isPlaying: false,
        accentHex: 0x6B4FD8,
        bars: Array(repeating: 0.15, count: 16),
        hasArt: false,
        playlistName: nil,
        glass: false,
        visualizerStyle: "bars",
        updatedAt: .distantPast
    )

    static func load() -> WidgetSnapshot {
        guard let data = AppGroup.defaults.data(forKey: key),
              let snap = try? JSONDecoder().decode(WidgetSnapshot.self, from: data) else { return .empty }
        return snap
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) {
            AppGroup.defaults.set(data, forKey: Self.key)
        }
    }

    static var artURL: URL? { AppGroup.containerURL?.appendingPathComponent("widget_art.jpg") }
}
