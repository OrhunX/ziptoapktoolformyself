package com.sen0413.ultimatewidget.data

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** One song inside a share code. Local files travel as their name only, never the file. */
data class SharedEntry(
    val sourcePackage: String?,
    val sourceLabel: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val mediaId: String?,
)

data class SharedPlaylist(
    val name: String,
    val description: String,
    val color: Int,
    val emoji: String,
    val from: String,
    val entries: List<SharedEntry>,
)

/**
 * Playlist share codes: "UMW1-" followed by compressed, AES-256-GCM encrypted playlist data
 * in URL-safe Base64, so it survives WhatsApp, e-mail and copy-paste and fits in a QR code.
 *
 * Without a PIN the key is derived from a constant: the code is unreadable and tamper-proof,
 * but anyone with the app can open it. With a PIN the key comes from PBKDF2(PIN, random
 * salt), so only people who know the PIN can open it. Pure Kotlin, unit-tested.
 */
object ShareCode {
    const val PREFIX = "UMW1-"
    const val DEEP_LINK = "umw://import?c="

    private const val VERSION: Byte = 1
    private const val FLAG_PIN = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val PBKDF2_ROUNDS = 40_000
    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'

    sealed class Result {
        data class Ok(val playlist: SharedPlaylist) : Result()
        object NeedsPin : Result()
        object WrongPin : Result()
        object Invalid : Result()
    }

    fun encode(playlist: SharedPlaylist, pin: String? = null): String {
        val plain = deflate(serialize(playlist).toByteArray(Charsets.UTF_8))
        val random = SecureRandom()
        val usePin = !pin.isNullOrBlank()
        val salt = ByteArray(SALT_BYTES).also { if (usePin) random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(if (usePin) pin else null, salt), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(plain)

        val out = ByteArrayOutputStream()
        out.write(VERSION.toInt())
        out.write(if (usePin) FLAG_PIN else 0)
        if (usePin) out.write(salt)
        out.write(iv)
        out.write(sealed)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** Accepts the bare code, a message that contains it, or the umw:// link. */
    fun decode(text: String, pin: String? = null): Result {
        val code = find(text) ?: return Result.Invalid
        val bytes = runCatching { Base64.getUrlDecoder().decode(code.substring(PREFIX.length)) }.getOrNull()
            ?: return Result.Invalid
        if (bytes.size < 2 + IV_BYTES + 16 || bytes[0] != VERSION) return Result.Invalid
        val hasPin = bytes[1].toInt() and FLAG_PIN != 0
        if (hasPin && pin.isNullOrBlank()) return Result.NeedsPin
        var offset = 2
        val salt = if (hasPin) bytes.copyOfRange(offset, offset + SALT_BYTES).also { offset += SALT_BYTES } else ByteArray(SALT_BYTES)
        if (bytes.size < offset + IV_BYTES + 16) return Result.Invalid
        val iv = bytes.copyOfRange(offset, offset + IV_BYTES)
        offset += IV_BYTES
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(if (hasPin) pin else null, salt), GCMParameterSpec(128, iv))
            cipher.doFinal(bytes, offset, bytes.size - offset)
        } catch (e: Exception) {
            return if (hasPin) Result.WrongPin else Result.Invalid
        }
        val playlist = runCatching { parse(String(inflate(plain), Charsets.UTF_8)) }.getOrNull() ?: return Result.Invalid
        return Result.Ok(playlist)
    }

    /** True when [text] contains something that looks like a share code. */
    fun looksLikeCode(text: String?): Boolean = text != null && find(text) != null

    private fun find(text: String): String? {
        val start = text.indexOf(PREFIX, ignoreCase = true)
        if (start < 0) return null
        val body = StringBuilder(PREFIX)
        var i = start + PREFIX.length
        while (i < text.length) {
            val c = text[i]
            if (c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_') body.append(c) else break
            i++
        }
        return body.toString().takeIf { it.length > PREFIX.length + 8 }
    }

    // ---------------------------------------------------------------- payload

    private fun clean(s: String) = s.replace(FIELD, ' ').replace(RECORD, ' ')

    private fun serialize(p: SharedPlaylist): String = buildString {
        append(listOf("p", clean(p.name), clean(p.description), p.color.toString(), clean(p.emoji), clean(p.from)).joinToString(FIELD.toString()))
        for (e in p.entries) {
            append(RECORD)
            append(
                listOf(
                    e.sourcePackage.orEmpty(), clean(e.sourceLabel), clean(e.title), clean(e.artist),
                    (e.durationMs / 1000).toString(), e.mediaId.orEmpty(),
                ).joinToString(FIELD.toString()),
            )
        }
    }

    private fun parse(text: String): SharedPlaylist {
        val records = text.split(RECORD)
        val head = records.first().split(FIELD)
        require(head.size >= 6 && head[0] == "p")
        val entries = records.drop(1).map { record ->
            val f = record.split(FIELD)
            require(f.size >= 6)
            SharedEntry(
                sourcePackage = f[0].ifEmpty { null },
                sourceLabel = f[1],
                title = f[2],
                artist = f[3],
                durationMs = (f[4].toLongOrNull() ?: 0L) * 1000L,
                mediaId = f[5].ifEmpty { null },
            )
        }
        return SharedPlaylist(head[1], head[2], head[3].toIntOrNull() ?: 0, head[4], head[5], entries)
    }

    // ---------------------------------------------------------------- crypto and compression

    private fun key(pin: String?, salt: ByteArray): SecretKeySpec {
        val bytes = if (pin == null) {
            MessageDigest.getInstance("SHA-256").digest("SEN0413 Ultimate Music Widget share code v1".toByteArray())
        } else {
            val spec = PBEKeySpec(pin.trim().toCharArray(), salt, PBKDF2_ROUNDS, 256)
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        }
        return SecretKeySpec(bytes, "AES")
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var guard = 0
        while (!inflater.finished() && guard++ < 10_000) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, n)
            require(out.size() < 2_000_000)
        }
        inflater.end()
        return out.toByteArray()
    }
}
