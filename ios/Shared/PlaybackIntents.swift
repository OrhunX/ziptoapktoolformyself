import AppIntents

/// Commands the widget buttons send. They are audio-playback intents, so iOS runs them inside
/// the app (launching it in the background when needed), where the player lives.
enum HubCommand: String {
    case playPause, next, previous
}

struct PlayPauseIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Play or pause"
    static var description = IntentDescription("Plays or pauses the current song.")

    init() {}

    func perform() async throws -> some IntentResult {
        await IntentRouter.run(.playPause)
        return .result()
    }
}

struct NextTrackIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Next song"
    static var description = IntentDescription("Skips to the next song in the playlist.")

    init() {}

    func perform() async throws -> some IntentResult {
        await IntentRouter.run(.next)
        return .result()
    }
}

struct PreviousTrackIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Previous song"
    static var description = IntentDescription("Goes back to the previous song.")

    init() {}

    func perform() async throws -> some IntentResult {
        await IntentRouter.run(.previous)
        return .result()
    }
}
