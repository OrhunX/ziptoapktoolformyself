import Foundation

/// Widget buttons (audio-playback intents) are performed here, inside the app.
enum IntentRouter {
    static func run(_ command: HubCommand) async {
        await MainActor.run { PlaybackHub.shared.handle(command) }
    }
}
