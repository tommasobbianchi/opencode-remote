package io.github.mirkotappini.opencoderemote.attachments

import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/**
 * Regole degli allegati, pure e verificabili su JVM: nessun import Android.
 *
 * Il contenuto dei file non passa mai da qui: si manipolano solo mime, nomi,
 * dimensioni e il passo di compressione.
 */
object AttachmentRules {

    /** Oltre questa soglia il file non si legge nemmeno. */
    const val MAX_INPUT_BYTES: Long = 50L * 1024 * 1024

    /** Tetto sicuro per l'invio inline: 4 MB di byte decodificati. */
    const val MAX_INLINE_BYTES: Long = 4L * 1024 * 1024

    /** Passo di ridimensionamento: primo tentativo, poi ritentativo più leggero. */
    data class ResizeStep(
        val maxSidePx: Int,
        val quality: Int,
    )

    /** In ordine di tentativo; esauriti i passi l'allegato è troppo grande. */
    val RESIZE_STEPS: List<ResizeStep> = listOf(
        ResizeStep(maxSidePx = 1600, quality = 80),
        ResizeStep(maxSidePx = 1280, quality = 70),
    )

    /** Mime accettati dal server; gli altri producono un 400. */
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

    /** Mime normalizzato: minuscolo e senza parametri (`; charset=…`). */
    fun normalizeMime(mime: String?): String =
        mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    fun isImageMime(mime: String?): Boolean = normalizeMime(mime).startsWith("image/")

    /** Il server accetta immagini, PDF, JSON, testo e binari generici. */
    fun isAcceptedMime(mime: String?): Boolean {
        val normalized = normalizeMime(mime)
        if (normalized.isEmpty()) return false
        return normalized.startsWith("image/") ||
            normalized.startsWith("text/") ||
            normalized in ACCEPTED_EXACT
    }

    /**
     * Mime usato per l'invio: quello dichiarato se accettato, altrimenti una
     * deduzione dall'estensione, altrimenti `application/octet-stream`.
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

    /** Mime dedotto dall'estensione; `null` se non riconosciuta. */
    fun mimeForName(name: String?): String? {
        val extension = name
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return MIME_BY_EXTENSION[extension]
    }

    /** Estensione per il mime noto, altrimenti quella del nome, altrimenti `bin`. */
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
     * Nome presentabile per il server: niente percorsi, niente caratteri di
     * controllo. Se il nome originale non ha estensione si aggiunge quella
     * coerente col mime.
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
            cleaned.isNullOrEmpty() -> "allegato.$extension"
            '.' in cleaned -> cleaned
            else -> "$cleaned.$extension"
        }
    }

    /** Data URI inline come lo vuole il server. */
    fun dataUri(mime: String, base64: String): String =
        "data:${normalizeMime(mime)};base64,$base64"

    fun isWithinInputLimit(bytes: Long): Boolean = bytes in 0..MAX_INPUT_BYTES

    fun isWithinInlineLimit(bytes: Long): Boolean = bytes in 0..MAX_INLINE_BYTES

    /** Passo di ridimensionamento per tentativo; `null` = nessun altro passo. */
    fun resizeStep(attempt: Int): ResizeStep? = RESIZE_STEPS.getOrNull(attempt)

    /** Lunghezza della codifica base64 (senza padding extra) per [bytes]. */
    fun base64Length(bytes: Long): Long = if (bytes <= 0L) 0L else ceil(bytes / 3.0).toLong() * 4

    /**
     * Peso leggibile in italiano, base 1000 come il sistema: "840 kB",
     * "1,2 MB". Sotto il kB restano i byte.
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
        // Sotto 10 si tiene un decimale (virgola), sopra si arrotonda.
        val text = if (value < 10.0) {
            val tenths = (value * 10.0).roundToLong()
            val whole = tenths / 10L
            val fraction = tenths % 10L
            if (fraction == 0L) whole.toString() else "$whole,$fraction"
        } else {
            value.roundToInt().toString()
        }
        return "$text $suffix"
    }
}
