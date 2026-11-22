import Accelerate
import AVFoundation
import MediaPlayer
import UIKit

// MARK: - Spectrum

/// FFT of the audio our own player outputs, folded into log-spaced bands (0...1).
final class SpectrumAnalyzer {
    private let n = 1024
    private let log2n: vDSP_Length = 10
    private let setup: FFTSetup
    private var window: [Float]
    private var real: [Float]
    private var imag: [Float]
    private var smoothed: [Float]
    let bands: Int

    init(bands: Int) {
        self.bands = bands
        setup = vDSP_create_fftsetup(log2n, FFTRadix(kFFTRadix2))!
        window = [Float](repeating: 0, count: n)
        vDSP_hann_window(&window, vDSP_Length(n), Int32(vDSP_HANN_NORM))
        real = [Float](repeating: 0, count: n / 2)
        imag = [Float](repeating: 0, count: n / 2)
        smoothed = [Float](repeating: 0, count: bands)
    }

    deinit { vDSP_destroy_fftsetup(setup) }

    func process(_ samples: UnsafePointer<Float>, count: Int, sampleRate: Double, gain: Float) -> [Float] {
        guard count >= n else { return smoothed }
        var windowed = [Float](repeating: 0, count: n)
        vDSP_vmul(samples, 1, window, 1, &windowed, 1, vDSP_Length(n))
        var mags = [Float](repeating: 0, count: n / 2)
        let half = n / 2
        let l2 = log2n
        let fft = setup
        real.withUnsafeMutableBufferPointer { rp in
            imag.withUnsafeMutableBufferPointer { ip in
                var split = DSPSplitComplex(realp: rp.baseAddress!, imagp: ip.baseAddress!)
                windowed.withUnsafeBufferPointer { wp in
                    wp.baseAddress!.withMemoryRebound(to: DSPComplex.self, capacity: half) { cp in
                        vDSP_ctoz(cp, 2, &split, 1, vDSP_Length(half))
                    }
                }
                vDSP_fft_zrip(fft, &split, 1, l2, FFTDirection(FFT_FORWARD))
                vDSP_zvmags(&split, 1, &mags, 1, vDSP_Length(half))
            }
        }
        let binHz = sampleRate / Double(n)
        var out = [Float](repeating: 0, count: bands)
        for b in 0..<bands {
            let lo = 40.0 * pow(400.0, Double(b) / Double(bands))
            let hi = 40.0 * pow(400.0, Double(b + 1) / Double(bands))
            let first = max(1, Int(lo / binHz))
            let last = min(half - 1, max(first, Int(hi / binHz)))
            var sum: Float = 0
            for k in first...last { sum += mags[k] }
            let avg = sum / Float(last - first + 1)
            let amp = sqrt(avg) / Float(half)
            let db = 20 * log10(amp + 1e-9)
            // Higher bands carry less energy in music: tilt them up a little.
            let tilt = 1 + Float(b) / Float(bands) * 0.6
            var v = (db + 62) / 48 * gain * tilt
            v = min(max(v, 0), 1)
            let prev = smoothed[b]
            smoothed[b] = v > prev ? prev + (v - prev) * 0.65 : prev - (prev - v) * 0.18
            out[b] = smoothed[b]
        }
        return out
    }
}

// MARK: - Our own player (imported files)

/// Plays imported files through AVAudioEngine so the visualizer can hear the real audio.
final class LocalAudioPlayer {
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let analyzer = SpectrumAnalyzer(bands: 24)
    private var file: AVAudioFile?
    private var sampleRate: Double = 44_100
    private var frameOffset: AVAudioFramePosition = 0
    private var token = 0
    private(set) var isPlaying = false
    private(set) var hasFile = false

    var gain: Float = 1
    /// Called on the main thread when the song played to its end.
    var onEnded: (() -> Void)?
    /// Called on the main thread about 40 times a second while playing.
    var onBars: (([Float]) -> Void)?

    init() {
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: nil)
        engine.mainMixerNode.installTap(onBus: 0, bufferSize: 1024, format: nil) { [weak self] buffer, _ in
            guard let self, self.isPlaying, let data = buffer.floatChannelData else { return }
            let bars = self.analyzer.process(data[0], count: Int(buffer.frameLength), sampleRate: buffer.format.sampleRate, gain: self.gain)
            DispatchQueue.main.async { self.onBars?(bars) }
        }
    }

    var durationMs: Int64 {
        guard let file else { return 0 }
        return Int64(Double(file.length) / sampleRate * 1000)
    }

    var positionMs: Int64 {
        guard let file else { return 0 }
        var frames = frameOffset
        if let nodeTime = node.lastRenderTime, let playerTime = node.playerTime(forNodeTime: nodeTime) {
            frames += playerTime.sampleTime
        }
        frames = min(max(frames, 0), file.length)
        return Int64(Double(frames) / sampleRate * 1000)
    }

    func load(_ url: URL) throws {
        stop()
        let f = try AVAudioFile(forReading: url)
        file = f
        hasFile = true
        sampleRate = f.processingFormat.sampleRate
        engine.disconnectNodeOutput(node)
        engine.connect(node, to: engine.mainMixerNode, format: f.processingFormat)
        frameOffset = 0
    }

    func play(fromMs ms: Int64 = 0) {
        guard file != nil else { return }
        schedule(fromMs: ms)
        startEngine()
        node.play()
        isPlaying = true
    }

    func pause() {
        node.pause()
        isPlaying = false
    }

    func resume() {
        guard file != nil else { return }
        startEngine()
        node.play()
        isPlaying = true
    }

    func seek(toMs ms: Int64) {
        guard file != nil else { return }
        let wasPlaying = isPlaying
        schedule(fromMs: ms)
        if wasPlaying {
            startEngine()
            node.play()
        }
    }

    func stop() {
        token += 1
        node.stop()
        if engine.isRunning { engine.pause() }
        isPlaying = false
    }

    private func startEngine() {
        if !engine.isRunning { try? engine.start() }
    }

    private func schedule(fromMs ms: Int64) {
        guard let file else { return }
        token += 1
        let my = token
        node.stop()
        let start = min(max(AVAudioFramePosition(Double(ms) / 1000 * sampleRate), 0), file.length)
        frameOffset = start
        let remaining = AVAudioFrameCount(max(file.length - start, 0))
        guard remaining > 0 else { return }
        node.scheduleSegment(file, startingFrame: start, frameCount: remaining, at: nil, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            DispatchQueue.main.async {
                guard let self, self.token == my else { return }
                self.isPlaying = false
                self.onEnded?()
            }
        }
    }
}

// MARK: - Apple Music

/// Plays songs from the Music library (including Apple Music) with the app's own queue player.
final class AppleMusicPlayer {
    private let player = MPMusicPlayerController.applicationQueuePlayer
    private var active = false
    private var wasPlaying = false
    private var lastPosition: TimeInterval = 0
    private var lastDuration: TimeInterval = 0

    /// The song ended by itself (main thread).
    var onEnded: (() -> Void)?
    /// Song or play state changed (main thread).
    var onChange: (() -> Void)?

    init() {
        player.beginGeneratingPlaybackNotifications()
        let nc = NotificationCenter.default
        nc.addObserver(forName: .MPMusicPlayerControllerPlaybackStateDidChange, object: player, queue: .main) { [weak self] _ in
            self?.stateChanged()
        }
        nc.addObserver(forName: .MPMusicPlayerControllerNowPlayingItemDidChange, object: player, queue: .main) { [weak self] _ in
            self?.onChange?()
        }
    }

    var isPlaying: Bool { active && player.playbackState == .playing }

    var positionMs: Int64 {
        let t = player.currentPlaybackTime
        return t.isFinite ? Int64(t * 1000) : 0
    }

    var durationMs: Int64 { Int64((player.nowPlayingItem?.playbackDuration ?? 0) * 1000) }

    var artwork: UIImage? { player.nowPlayingItem?.artwork?.image(at: CGSize(width: 400, height: 400)) }

    /// Starts the song from the beginning. False when it is not in the Music library.
    func play(_ entry: Entry, onFailed: @escaping () -> Void) -> Bool {
        if let id = entry.mediaId, id.hasPrefix("store:") {
            player.setQueue(with: [String(id.dropFirst(6))])
        } else if let item = item(for: entry) {
            player.setQueue(with: MPMediaItemCollection(items: [item]))
        } else {
            return false
        }
        player.repeatMode = .none
        player.shuffleMode = .off
        active = true
        wasPlaying = false
        lastPosition = 0
        player.prepareToPlay { [weak self] error in
            DispatchQueue.main.async {
                guard let self, self.active else { return }
                if error != nil {
                    onFailed()
                    return
                }
                self.player.currentPlaybackTime = 0
                self.player.play()
            }
        }
        return true
    }

    private func item(for entry: Entry) -> MPMediaItem? {
        if let id = entry.mediaId, id.hasPrefix("pid:"), let pid = UInt64(id.dropFirst(4)),
           let item = MusicLibrary.mediaItem(persistentID: pid) {
            return item
        }
        let query = MPMediaQuery.songs()
        query.addFilterPredicate(MPMediaPropertyPredicate(value: entry.title, forProperty: MPMediaItemPropertyTitle, comparisonType: .contains))
        let items = query.items ?? []
        return items.first { entry.artist.isEmpty || TrackMatcher.sameTitle(entry.artist, $0.artist ?? "") } ?? items.first
    }

    func pause() { if active { player.pause() } }
    func resume() { if active { player.play() } }
    func seek(toMs ms: Int64) { if active { player.currentPlaybackTime = TimeInterval(ms) / 1000 } }

    func stop() {
        guard active else { return }
        active = false
        player.stop()
    }

    /// Called about once a second: a single-song queue that stopped at its end means "done".
    func tick() {
        guard active else { return }
        let playing = player.playbackState == .playing
        if playing {
            lastPosition = player.currentPlaybackTime.isFinite ? player.currentPlaybackTime : lastPosition
            lastDuration = player.nowPlayingItem?.playbackDuration ?? lastDuration
            wasPlaying = true
        } else if wasPlaying {
            let atEnd = lastDuration > 0 && lastPosition >= lastDuration - 3
            if atEnd || player.nowPlayingItem == nil || player.playbackState == .stopped {
                wasPlaying = false
                if atEnd || player.playbackState == .stopped { onEnded?() }
            }
        }
    }

    private func stateChanged() {
        if active && player.playbackState == .playing { wasPlaying = true }
        onChange?()
    }
}

// MARK: - Staying alive in the background

/// While Spotify or Apple Music plays a hub playlist, iOS would suspend our app and the playlist
/// could not move on. A silent, mixable audio loop keeps the app running (sideloaded builds only;
/// this is not something the App Store would accept).
final class KeepAlive {
    private var player: AVAudioPlayer?

    func start() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
        try? session.setActive(true)
        if player == nil {
            player = try? AVAudioPlayer(data: Self.silentWav())
            player?.numberOfLoops = -1
            player?.volume = 0
        }
        if player?.isPlaying != true { player?.play() }
    }

    func stop() {
        player?.stop()
    }

    var isRunning: Bool { player?.isPlaying == true }

    /// One second of 8 kHz mono silence as a WAV file.
    private static func silentWav() -> Data {
        let sampleRate: UInt32 = 8000
        let samples = Int(sampleRate)
        var d = Data()
        func u32(_ v: UInt32) { var x = v.littleEndian; d.append(Data(bytes: &x, count: 4)) }
        func u16(_ v: UInt16) { var x = v.littleEndian; d.append(Data(bytes: &x, count: 2)) }
        d.append(contentsOf: Array("RIFF".utf8)); u32(UInt32(36 + samples * 2))
        d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); u32(16); u16(1); u16(1); u32(sampleRate); u32(sampleRate * 2); u16(2); u16(16)
        d.append(contentsOf: Array("data".utf8)); u32(UInt32(samples * 2))
        d.append(Data(count: samples * 2))
        return d
    }
}

// MARK: - Colors from artwork

extension UIImage {
    /// The average color, brightened a little so it works as an accent on the dark background.
    var accentHex: UInt32? {
        guard let cg = cgImage else { return nil }
        let size = 8
        var pixels = [UInt8](repeating: 0, count: size * size * 4)
        guard let ctx = CGContext(
            data: &pixels, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: size, height: size))
        var r = 0, g = 0, b = 0
        for i in stride(from: 0, to: pixels.count, by: 4) {
            r += Int(pixels[i]); g += Int(pixels[i + 1]); b += Int(pixels[i + 2])
        }
        let count = size * size
        var color = UIColor(red: CGFloat(r / count) / 255, green: CGFloat(g / count) / 255, blue: CGFloat(b / count) / 255, alpha: 1)
        var h: CGFloat = 0, s: CGFloat = 0, v: CGFloat = 0, a: CGFloat = 0
        if color.getHue(&h, saturation: &s, brightness: &v, alpha: &a) {
            color = UIColor(hue: h, saturation: max(s, 0.45), brightness: max(v, 0.75), alpha: 1)
        }
        var rr: CGFloat = 0, gg: CGFloat = 0, bb: CGFloat = 0
        color.getRed(&rr, green: &gg, blue: &bb, alpha: &a)
        return (UInt32(rr * 255) << 16) | (UInt32(gg * 255) << 8) | UInt32(bb * 255)
    }
}
