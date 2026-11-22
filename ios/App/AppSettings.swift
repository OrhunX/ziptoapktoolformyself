import Foundation
import Observation

/// Look and feel, saved in UserDefaults.
@Observable
@MainActor
final class AppSettings {
    static let shared = AppSettings()

    private let d = UserDefaults.standard

    var glass: Bool { didSet { d.set(glass, forKey: "glass"); changed() } }
    /// 0 = follow the cover art.
    var customAccentHex: UInt32 { didSet { d.set(Int(customAccentHex), forKey: "accent"); changed() } }
    var visualizerStyle: VisualizerStyle { didSet { d.set(visualizerStyle.rawValue, forKey: "viz_style"); changed() } }
    var sensitivity: Double { didSet { d.set(sensitivity, forKey: "sensitivity"); changed() } }
    var shuffle: Bool { didSet { d.set(shuffle, forKey: "shuffle") } }
    var repeatMode: RepeatMode { didSet { d.set(repeatMode.rawValue, forKey: "repeat") } }

    /// Lets the hub refresh the widget when the look changes.
    @ObservationIgnored var onChange: (() -> Void)?

    private init() {
        glass = d.bool(forKey: "glass")
        customAccentHex = UInt32(truncatingIfNeeded: d.integer(forKey: "accent"))
        visualizerStyle = VisualizerStyle(rawValue: d.string(forKey: "viz_style") ?? "") ?? .bars
        sensitivity = d.object(forKey: "sensitivity") as? Double ?? 1.0
        shuffle = d.bool(forKey: "shuffle")
        repeatMode = RepeatMode(rawValue: d.string(forKey: "repeat") ?? "") ?? .all
    }

    private func changed() { onChange?() }
}
