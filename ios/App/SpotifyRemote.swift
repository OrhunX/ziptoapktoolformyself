import Foundation
import SpotifyiOS
import UIKit

struct SpotifyState {
    let uri: String
    let name: String
    let artist: String
    let durationMs: Int64
    let positionMs: Int64
    let isPaused: Bool
    let track: SPTAppRemoteTrack
}

struct SpotifyTrack: Identifiable, Hashable {
    var id: String { uri }
    let uri: String
    let name: String
    let artist: String
    let durationMs: Int64

    var entry: Entry { Entry(source: .spotify, title: name, artist: artist, durationMs: durationMs, mediaId: uri) }
}

/// Spotify's iOS SDK (App Remote). Plays exact tracks ("spotify:track:...") from the beginning.
///
/// iOS rules: App Remote only connects while the Spotify app is running. When it is not, the
/// SDK has to open Spotify for a moment (authorizeAndPlayURI), which then returns to our app.
/// The approval token is also used for Web API search.
final class SpotifyRemote: NSObject, SPTAppRemoteDelegate, SPTAppRemotePlayerStateDelegate {
    static let shared = SpotifyRemote()
    static let clientID = "2bd9780d81154855b3a7ba4d5fe5c77b"
    static let redirectURL = URL(string: "ultimatewidget://spotify-callback")!

    private let tokenKey = "spotify_token"
    private let expiryKey = "spotify_token_expiry"

    /// Player changes, on the main thread.
    var onState: ((SpotifyState) -> Void)?
    /// Connected or disconnected, on the main thread.
    var onConnectionChange: ((Bool) -> Void)?

    private var pending: (uri: String, done: (Bool) -> Void)?
    private var pauseAfterConnect = false

    private lazy var appRemote: SPTAppRemote = {
        let config = SPTConfiguration(clientID: Self.clientID, redirectURL: Self.redirectURL)
        let remote = SPTAppRemote(configuration: config, logLevel: .error)
        remote.connectionParameters.accessToken = token
        remote.delegate = self
        return remote
    }()

    var token: String? { UserDefaults.standard.string(forKey: tokenKey) }

    var tokenValid: Bool {
        token != nil && Date().timeIntervalSince1970 < UserDefaults.standard.double(forKey: expiryKey)
    }

    var isLinked: Bool { token != nil }
    var isConnected: Bool { appRemote.isConnected }

    var isInstalled: Bool {
        guard let url = URL(string: "spotify:") else { return false }
        return UIApplication.shared.canOpenURL(url)
    }

    // MARK: connecting

    /// From Settings: approve once. Spotify opens, resumes its last song, and comes back here;
    /// we pause that song right after connecting.
    func link() {
        pauseAfterConnect = true
        appRemote.authorizeAndPlayURI("") { _ in }
    }

    func unlink() {
        UserDefaults.standard.removeObject(forKey: tokenKey)
        UserDefaults.standard.removeObject(forKey: expiryKey)
        if appRemote.isConnected { appRemote.disconnect() }
    }

    /// Handles ultimatewidget://spotify-callback. Returns true when it was Spotify's answer.
    func handle(url: URL) -> Bool {
        guard url.scheme == Self.redirectURL.scheme else { return false }
        let params = appRemote.authorizationParameters(from: url)
        if let accessToken = params?[SPTAppRemoteAccessTokenKey] {
            UserDefaults.standard.set(accessToken, forKey: tokenKey)
            UserDefaults.standard.set(Date().timeIntervalSince1970 + 3500, forKey: expiryKey)
            appRemote.connectionParameters.accessToken = accessToken
            appRemote.connect()
        } else if params?[SPTAppRemoteErrorDescriptionKey] != nil {
            pending?.done(false)
            pending = nil
        }
        return true
    }

    /// Reconnects when our app comes back to the screen (Spotify may have been restarted).
    func reconnectIfLinked() {
        if isLinked && !appRemote.isConnected { appRemote.connect() }
    }

    // MARK: playback

    /// Plays [uri] from the beginning. [done] gets false when Spotify cannot be reached.
    func play(uri: String, done: @escaping (Bool) -> Void) {
        if appRemote.isConnected, let api = appRemote.playerAPI {
            api.play(uri) { _, error in
                DispatchQueue.main.async { done(error == nil) }
            }
            return
        }
        guard isLinked else {
            done(false)
            return
        }
        pending = (uri, done)
        appRemote.connect()
    }

    func pause() { appRemote.playerAPI?.pause(nil) }
    func resume() { appRemote.playerAPI?.resume(nil) }
    func seek(toMs ms: Int64) { appRemote.playerAPI?.seek(toPosition: Int(ms), callback: nil) }

    func fetchImage(for track: SPTAppRemoteTrack, completion: @escaping (UIImage?) -> Void) {
        guard let api = appRemote.imageAPI else {
            completion(nil)
            return
        }
        api.fetchImage(forItem: track, with: CGSize(width: 400, height: 400)) { result, _ in
            DispatchQueue.main.async { completion(result as? UIImage) }
        }
    }

    // MARK: SPTAppRemoteDelegate

    func appRemoteDidEstablishConnection(_ appRemote: SPTAppRemote) {
        appRemote.playerAPI?.delegate = self
        appRemote.playerAPI?.subscribe(toPlayerState: nil)
        DispatchQueue.main.async {
            if self.pauseAfterConnect {
                self.pauseAfterConnect = false
                self.pause()
            }
            self.onConnectionChange?(true)
            if let p = self.pending {
                self.pending = nil
                self.play(uri: p.uri, done: p.done)
            }
        }
    }

    func appRemote(_ appRemote: SPTAppRemote, didFailConnectionAttemptWithError error: Error?) {
        DispatchQueue.main.async {
            guard let p = self.pending else { return }
            self.pending = nil
            if UIApplication.shared.applicationState == .active && self.isInstalled {
                // Spotify is not running: wake it with the song itself (opens Spotify briefly).
                appRemote.authorizeAndPlayURI(p.uri) { ok in
                    DispatchQueue.main.async { p.done(ok) }
                }
            } else {
                p.done(false)
            }
        }
    }

    func appRemote(_ appRemote: SPTAppRemote, didDisconnectWithError error: Error?) {
        DispatchQueue.main.async { self.onConnectionChange?(false) }
    }

    // MARK: SPTAppRemotePlayerStateDelegate

    func playerStateDidChange(_ playerState: SPTAppRemotePlayerState) {
        let track = playerState.track
        let state = SpotifyState(
            uri: track.uri,
            name: track.name,
            artist: track.artist.name,
            durationMs: Int64(track.duration),
            positionMs: Int64(playerState.playbackPosition),
            isPaused: playerState.isPaused,
            track: track
        )
        DispatchQueue.main.async { self.onState?(state) }
    }

    // MARK: Web API search (needs a token from the last approval, valid for an hour)

    func search(_ query: String, limit: Int = 20) async -> [SpotifyTrack] {
        guard tokenValid, let token, !query.trimmingCharacters(in: .whitespaces).isEmpty else { return [] }
        var comps = URLComponents(string: "https://api.spotify.com/v1/search")!
        comps.queryItems = [
            URLQueryItem(name: "type", value: "track"),
            URLQueryItem(name: "limit", value: String(limit)),
            URLQueryItem(name: "q", value: query),
        ]
        var request = URLRequest(url: comps.url!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        guard let result = try? await URLSession.shared.data(for: request) else { return [] }
        let (data, response) = result
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tracks = json["tracks"] as? [String: Any],
              let items = tracks["items"] as? [[String: Any]] else { return [] }
        return items.compactMap { item in
            guard let uri = item["uri"] as? String, let name = item["name"] as? String else { return nil }
            let artists = (item["artists"] as? [[String: Any]])?.compactMap { $0["name"] as? String } ?? []
            let duration = (item["duration_ms"] as? NSNumber)?.int64Value ?? 0
            return SpotifyTrack(uri: uri, name: name, artist: artists.joined(separator: ", "), durationMs: duration)
        }
    }

    /// The exact Spotify id for a saved song, or nil.
    func findURI(title: String, artist: String) async -> String? {
        let strict = artist.isEmpty ? "track:\(title)" : "track:\(title) artist:\(artist)"
        var results = await search(strict, limit: 5)
        if results.isEmpty { results = await search("\(title) \(artist)", limit: 5) }
        return results.first { TrackMatcher.sameTitle(title, $0.name) }?.uri
    }
}
