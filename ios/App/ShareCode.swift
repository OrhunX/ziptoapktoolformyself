import CommonCrypto
import Compression
import CryptoKit
import Foundation

struct SharedEntry {
    var sourcePackage: String?
    var sourceLabel: String
    var title: String
    var artist: String
    var durationMs: Int64
    var mediaId: String?
}

struct SharedPlaylist {
    var name: String
    var description: String
    var color: Int32
    var emoji: String
    var from: String
    var entries: [SharedEntry]
}

/// Playlist share codes, byte-for-byte compatible with the Android app:
/// "UMW1-" + URL-safe Base64 of [version, flags, (salt), iv, AES-256-GCM(raw deflate(payload))].
enum ShareCode {
    static let prefix = "UMW1-"
    static let deepLink = "umw://import?c="

    private static let version: UInt8 = 1
    private static let flagPin: UInt8 = 1
    private static let saltBytes = 16
    private static let ivBytes = 12
    private static let rounds: UInt32 = 40_000
    private static let field = "\u{1F}"
    private static let record = "\u{1E}"

    enum Result {
        case ok(SharedPlaylist)
        case needsPin, wrongPin, invalid
    }

    static func encode(_ playlist: SharedPlaylist, pin: String?) -> String? {
        guard let plain = deflate(Data(serialize(playlist).utf8)) else { return nil }
        let usePin = !(pin ?? "").trimmingCharacters(in: .whitespaces).isEmpty
        var salt = Data(count: saltBytes)
        if usePin { salt = random(saltBytes) }
        let iv = random(ivBytes)
        guard let key = key(pin: usePin ? pin : nil, salt: salt),
              let nonce = try? AES.GCM.Nonce(data: iv),
              let box = try? AES.GCM.seal(plain, using: key, nonce: nonce) else { return nil }
        var out = Data([version, usePin ? flagPin : 0])
        if usePin { out.append(salt) }
        out.append(iv)
        out.append(box.ciphertext)
        out.append(box.tag)
        return prefix + base64url(out)
    }

    static func decode(_ text: String, pin: String?) -> Result {
        guard let code = find(text), let bytes = unbase64url(String(code.dropFirst(prefix.count))) else { return .invalid }
        let b = [UInt8](bytes)
        guard b.count >= 2 + ivBytes + 16, b[0] == version else { return .invalid }
        let hasPin = b[1] & flagPin != 0
        if hasPin && (pin ?? "").trimmingCharacters(in: .whitespaces).isEmpty { return .needsPin }
        var offset = 2
        var salt = Data(count: saltBytes)
        if hasPin {
            salt = Data(b[offset..<offset + saltBytes])
            offset += saltBytes
        }
        guard b.count >= offset + ivBytes + 16 else { return .invalid }
        let iv = Data(b[offset..<offset + ivBytes])
        offset += ivBytes
        let sealed = Data(b[offset...])
        let cipher = sealed.prefix(sealed.count - 16)
        let tag = sealed.suffix(16)
        guard let key = key(pin: hasPin ? pin : nil, salt: salt),
              let nonce = try? AES.GCM.Nonce(data: iv),
              let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: cipher, tag: tag),
              let plain = try? AES.GCM.open(box, using: key) else {
            return hasPin ? .wrongPin : .invalid
        }
        guard let inflated = inflate(plain), let text = String(data: inflated, encoding: .utf8),
              let playlist = parse(text) else { return .invalid }
        return .ok(playlist)
    }

    static func looksLikeCode(_ text: String?) -> Bool {
        guard let text else { return false }
        return find(text) != nil
    }

    private static func find(_ text: String) -> String? {
        guard let range = text.range(of: prefix, options: .caseInsensitive) else { return nil }
        var body = prefix
        for ch in text[range.upperBound...] {
            if (ch.isASCII && (ch.isLetter || ch.isNumber)) || ch == "-" || ch == "_" { body.append(ch) } else { break }
        }
        return body.count > prefix.count + 8 ? body : nil
    }

    // MARK: payload

    private static func clean(_ s: String) -> String {
        s.replacingOccurrences(of: field, with: " ").replacingOccurrences(of: record, with: " ")
    }

    private static func serialize(_ p: SharedPlaylist) -> String {
        var parts = [["p", clean(p.name), clean(p.description), String(p.color), clean(p.emoji), clean(p.from)].joined(separator: field)]
        for e in p.entries {
            parts.append([
                e.sourcePackage ?? "", clean(e.sourceLabel), clean(e.title), clean(e.artist),
                String(e.durationMs / 1000), e.mediaId ?? "",
            ].joined(separator: field))
        }
        return parts.joined(separator: record)
    }

    private static func parse(_ text: String) -> SharedPlaylist? {
        let records = text.components(separatedBy: record)
        guard let headRecord = records.first else { return nil }
        let head = headRecord.components(separatedBy: field)
        guard head.count >= 6, head[0] == "p" else { return nil }
        var entries: [SharedEntry] = []
        for r in records.dropFirst() {
            let f = r.components(separatedBy: field)
            guard f.count >= 6 else { return nil }
            entries.append(SharedEntry(
                sourcePackage: f[0].isEmpty ? nil : f[0],
                sourceLabel: f[1], title: f[2], artist: f[3],
                durationMs: (Int64(f[4]) ?? 0) * 1000,
                mediaId: f[5].isEmpty ? nil : f[5]
            ))
        }
        return SharedPlaylist(name: head[1], description: head[2], color: Int32(head[3]) ?? 0, emoji: head[4], from: head[5], entries: entries)
    }

    // MARK: crypto

    private static func key(pin: String?, salt: Data) -> SymmetricKey? {
        guard let pin else {
            let digest = SHA256.hash(data: Data("SEN0413 Ultimate Music Widget share code v1".utf8))
            return SymmetricKey(data: Data(digest))
        }
        let password = Array(pin.trimmingCharacters(in: .whitespaces).utf8)
        var derived = [UInt8](repeating: 0, count: 32)
        let saltBytes = [UInt8](salt)
        let status = password.withUnsafeBufferPointer { pw in
            CCKeyDerivationPBKDF(
                CCPBKDFAlgorithm(kCCPBKDF2),
                pw.baseAddress.map { UnsafeRawPointer($0).assumingMemoryBound(to: CChar.self) }, pw.count,
                saltBytes, saltBytes.count,
                CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), rounds,
                &derived, derived.count
            )
        }
        return status == kCCSuccess ? SymmetricKey(data: Data(derived)) : nil
    }

    private static func random(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes)
    }

    private static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func unbase64url(_ s: String) -> Data? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t.append("=") }
        return Data(base64Encoded: t)
    }

    // MARK: raw deflate (same as java.util.zip with nowrap = true)

    private static func deflate(_ data: Data) -> Data? { process(data, operation: COMPRESSION_STREAM_ENCODE) }
    private static func inflate(_ data: Data) -> Data? { process(data, operation: COMPRESSION_STREAM_DECODE) }

    private static func process(_ input: Data, operation: compression_stream_operation) -> Data? {
        let streamPtr = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { streamPtr.deallocate() }
        guard compression_stream_init(streamPtr, operation, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else { return nil }
        defer { compression_stream_destroy(streamPtr) }
        let bufferSize = 64 * 1024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }
        var output = Data()
        let ok: Bool = input.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Bool in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return input.isEmpty }
            streamPtr.pointee.src_ptr = base
            streamPtr.pointee.src_size = input.count
            streamPtr.pointee.dst_ptr = buffer
            streamPtr.pointee.dst_size = bufferSize
            while true {
                let status = compression_stream_process(streamPtr, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                switch status {
                case COMPRESSION_STATUS_OK, COMPRESSION_STATUS_END:
                    let produced = bufferSize - streamPtr.pointee.dst_size
                    if produced > 0 { output.append(buffer, count: produced) }
                    if output.count > 2_000_000 { return false }
                    if status == COMPRESSION_STATUS_END { return true }
                    streamPtr.pointee.dst_ptr = buffer
                    streamPtr.pointee.dst_size = bufferSize
                default:
                    return false
                }
            }
        }
        return ok ? output : nil
    }
}
