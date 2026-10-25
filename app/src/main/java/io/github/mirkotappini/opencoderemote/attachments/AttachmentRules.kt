package io.github.mirkotappini.opencoderemote.attachments

import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/**
 * Attachment rules, pure and verifiable on the JVM: no Android imports.
 *
 * File content never passes through here: only mime types, names, sizes, and
 * compression steps are manipulated.
 */
object AttachmentRules {

    /** Beyond this threshold, the file cannot be read at all. */
    const val MAX_INPUT_BYTES: Long = 50L * 1024 * 1024

    /** Safe ceiling for inline submission: 4 MB of decoded bytes. */
    const val MAX_INLINE_BYTES: Long = 4L * 1024 * 1024

    /** Resize step: first attempt, then a lighter retry. */
    data class ResizeStep(
        val maxSidePx: Int,
        val quality: Int,
    )

    /** In order of attempt; once steps are exhausted, the attachment is too large. */
    val RESIZE_STEPS: List<ResizeStep> = listOf(
        ResizeStep(maxSidePx = 1600, quality = 80),
        ResizeStep(maxSidePx = 1280, quality = 70),
    )

    /** Mimes accepted by the server; others produce a 400. */
    private val ACCEPTED_EXACT = setOf(
        "application/pdf",
        "application/json",
        "application/octet-stream",
    )

    private val EXTENSIONS = mapOf(
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "image/bmp" to "bmp",
        "application/pdf" to "pdf",
        "application/json" to "json",
        "application/zip" to "zip",
        "application/octet-stream" to "bin",
        "text/plain" to "txt",
        "text/markdown" to "md",
        "text/csv" to "csv",
        "text/html" to "html",
        "text/xml" to "xml",
        "text/css" to "css",
    )

    private val MIME_BY_EXTENSION = EXTENSIONS.entries
        .groupBy({ it.value }, { it.key })
        .mapValues { (_, mimes) -> mimes.first() }

    private val UNSAFE_NAME = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** Normalized MIME: lowercase and without parameters (`; charset=…`). */
    fun normalizeMime(mime: String?): String =
        mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    fun isImageMime(mime: String?): Boolean = normalizeMime(mime).startsWith("image/")

    /** The server accepts images, PDFs, JSON, text, and generic binaries. */
    fun isAcceptedMime(mime: String?): Boolean {
        val normalized = normalizeMime(mime)
        if (normalized.isEmpty()) return false
        return normalized.startsWith("image/") ||
            normalized.startsWith("text/") ||
            normalized in ACCEPTED_EXACT
    }

    /**
     * MIME used for sending: the declared one if accepted, otherwise inferred from
     * the extension, otherwise `application/octet-stream`.
     */
    fun outboundMime(mime: String?, name: String?): String {
        val declared = normalizeMime(mime)
        if (isAcceptedMime(declared)) return declared
        val guessed = mimeForName(name)
        return if (guessed != null && isAcceptedMime(guessed)) {
            guessed
        } else {
            "application/octet-stream"
        }
    }

    /** Mime inferred from the extension; `null` if unrecognized. */
    fun mimeForName(name: String?): String? {
        val extension = name
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return MIME_BY_EXTENSION[extension]
    }

    /**
     * Extension for the known mime, otherwise the one from the name, otherwise
     * `bin`.
     */
    fun extensionFor(mime: String?, name: String? = null): String {
        EXTENSIONS[normalizeMime(mime)]?.let { return it }
        val fromName = name
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && it.length <= 8 && it.all { c -> c.isLetterOrDigit() } }
        return fromName ?: "bin"
    }

    /**
     * Presentable name for the server: no paths, no control characters. If the
     * original name has no extension, append one consistent with the mime.
     */
    fun fileNameFor(mime: String?, originalName: String?): String {
        val extension = extensionFor(mime, originalName)
        val cleaned = originalName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            ?.replace(UNSAFE_NAME, "_")
            ?.trim('.', ' ', '_')
            ?.take(120)
        return when {
            cleaned.isNullOrEmpty() -> "attachment.$extension"
            '.' in cleaned -> cleaned
            else -> "$cleaned.$extension"
        }
    }

    /** Inline Data URI as the server requires. */
    fun dataUri(mime: String, base64: String): String =
        "data:${normalizeMime(mime)};base64,$base64"

    fun isWithinInputLimit(bytes: Long): Boolean = bytes in 0..MAX_INPUT_BYTES

    fun isWithinInlineLimit(bytes: Long): Boolean = bytes in 0..MAX_INLINE_BYTES

    /** Resize step per attempt; `null` = no further step. */
    fun resizeStep(attempt: Int): ResizeStep? = RESIZE_STEPS.getOrNull(attempt)

    /** Length of the base64 encoding (without extra padding) for [bytes]. */
    fun base64Length(bytes: Long): Long = if (bytes <= 0L) 0L else ceil(bytes / 3.0).toLong() * 4

    /**
     * Human-readable weight in English, base 1000 like the system: "840 kB", "1.2
     * MB". Below kB, bytes remain.
     */
    fun formatSize(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < 1_000L -> "$safe B"
            safe < 1_000_000L -> unit(safe / 1_000.0, "kB")
            safe < 1_000_000_000L -> unit(safe / 1_000_000.0, "MB")
            else -> unit(safe / 1_000_000_000.0, "GB")
        }
    }

    private fun unit(value: Double, suffix: String): String {
        // Below 10, keep one decimal place; above 10, round.
        val text = if (value < 10.0) {
            val tenths = (value * 10.0).roundToLong()
            val whole = tenths / 10L
            val fraction = tenths % 10L
            if (fraction == 0L) whole.toString() else "$whole.$fraction"
        } else {
            value.roundToInt().toString()
        }
        return "$text $suffix"
    }
}
