package io.github.mirkotappini.opencoderemote.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import io.github.mirkotappini.opencoderemote.AttachmentUi
import io.github.mirkotappini.opencoderemote.net.PromptFile
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.roundToInt

/** Esito tipizzato della preparazione: il chiamante non vede eccezioni. */
sealed interface AttachmentResult {
    data class Ok(
        val file: PromptFile,
        val ui: AttachmentUi,
    ) : AttachmentResult

    /** Oltre il tetto in ingresso (50 MB). */
    data object TooLarge : AttachmentResult

    /** URI non leggibile o contenuto non decodificabile. */
    data object NotReadable : AttachmentResult

    /** Leggibile ma ancora oltre il tetto inline: `isImage` sceglie il messaggio. */
    data class TooLargeAfterCompression(val isImage: Boolean) : AttachmentResult
}

/**
 * Prepara un allegato per l'invio inline.
 *
 * Legge dal `ContentResolver` (tetto 50 MB), per le immagini decodifica a
 * campioni, ridimensiona e comprime in JPEG; per gli altri file applica il
 * tetto di 4 MB sui byte letti. Nessuna scrittura su disco e nessun log del
 * contenuto.
 */
class AttachmentPreparer(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun prepare(uri: Uri): AttachmentResult {
        val info = sourceInfo(uri)
        if (info.size != null && info.size > AttachmentRules.MAX_INPUT_BYTES) {
            return AttachmentResult.TooLarge
        }
        val looksImage = AttachmentRules.isImageMime(info.mime) ||
            AttachmentRules.isImageMime(AttachmentRules.mimeForName(info.name))
        if (looksImage && info.size == null) {
            // Senza SIZE dichiarata il tetto va comunque rispettato: per le
            // immagini si decodifica a campioni e non si leggerebbero i byte.
            val measured = measureSize(uri) ?: return AttachmentResult.NotReadable
            if (measured > AttachmentRules.MAX_INPUT_BYTES) {
                return AttachmentResult.TooLarge
            }
        }
        return if (looksImage) {
            prepareImage(uri, info)
        } else {
            prepareFile(uri, info)
        }
    }

    // --- file non immagine ---------------------------------------------------

    private fun prepareFile(uri: Uri, info: SourceInfo): AttachmentResult {
        val bytes = try {
            readLimited(uri, AttachmentRules.MAX_INPUT_BYTES)
        } catch (_: InputTooLarge) {
            return AttachmentResult.TooLarge
        } catch (_: Exception) {
            return AttachmentResult.NotReadable
        }

        if (!AttachmentRules.isWithinInlineLimit(bytes.size.toLong())) {
            return AttachmentResult.TooLargeAfterCompression(isImage = false)
        }

        val mime = AttachmentRules.outboundMime(info.mime, info.name)
        val name = AttachmentRules.fileNameFor(mime, info.name)
        return AttachmentResult.Ok(
            file = PromptFile(
                uri = AttachmentRules.dataUri(
                    mime = mime,
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                ),
                name = name,
            ),
            ui = AttachmentUi(
                name = name,
                mime = mime,
                sizeBytes = info.size ?: bytes.size.toLong(),
                isImage = false,
                previewUri = uri.toString(),
            ),
        )
    }

    // --- immagini ------------------------------------------------------------

    private fun prepareImage(uri: Uri, info: SourceInfo): AttachmentResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = try {
            val stream = resolver.openInputStream(uri) ?: return AttachmentResult.NotReadable
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
            true
        } catch (_: Exception) {
            false
        }
        if (!opened) return AttachmentResult.NotReadable
        // Formato che BitmapFactory non decodifica (es. SVG): si prova l'invio
        // del contenuto originale, sempre col cap inline.
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return prepareFile(uri, info)

        val decoded = decodeSampled(uri, bounds) ?: return prepareFile(uri, info)
        val rotation = rotationDegrees(uri)
        val name = jpegName(info.name)

        try {
            // Primo tentativo 1600 px/80, poi 1280 px/70: la sorgente decodificata
            // resta la stessa, si ricomprime solo più piccola.
            for (attempt in AttachmentRules.RESIZE_STEPS.indices) {
                val step = AttachmentRules.resizeStep(attempt) ?: break
                val scaled = scaleToMaxSide(decoded, step.maxSidePx)
                val oriented = if (rotation == 0) scaled else rotate(scaled, rotation)
                val bytes = compressJpeg(oriented, step.quality)
                if (oriented !== scaled) oriented.recycle()
                if (scaled !== decoded) scaled.recycle()
                if (bytes == null) return AttachmentResult.NotReadable
                if (!AttachmentRules.isWithinInlineLimit(bytes.size.toLong())) continue

                return AttachmentResult.Ok(
                    file = PromptFile(
                        uri = AttachmentRules.dataUri(
                            mime = JPEG_MIME,
                            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        ),
                        name = name,
                    ),
                    ui = AttachmentUi(
                        name = name,
                        mime = JPEG_MIME,
                        sizeBytes = info.size ?: 0L,
                        isImage = true,
                        previewUri = uri.toString(),
                    ),
                )
            }
            return AttachmentResult.TooLargeAfterCompression(isImage = true)
        } finally {
            decoded.recycle()
        }
    }

    /** Decodifica con `inSampleSize`: mai un bitmap più grande del necessario. */
    private fun decodeSampled(uri: Uri, bounds: BitmapFactory.Options): Bitmap? {
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val target = AttachmentRules.RESIZE_STEPS.first().maxSidePx
        var sample = 1
        // Si ferma al primo campione che resta almeno il doppio del target:
        // il ridimensionamento finale conserva i dettagli senza sprecare RAM.
        while (longest / (sample * 2) >= target) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleToMaxSide(source: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide) return source
        val ratio = maxSide.toFloat() / longest
        val width = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (source.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun compressJpeg(source: Bitmap, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        return try {
            if (!source.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                null
            } else {
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Nome coerente col JPEG prodotto, anche se in ingresso era un PNG/HEIC. */
    private fun jpegName(originalName: String?): String {
        val named = AttachmentRules.fileNameFor(JPEG_MIME, originalName)
        return named.substringBeforeLast('.', named) + ".jpg"
    }

    /**
     * Rotazione dichiarata dal resolver (MediaStore); se il provider non la
     * espone si legge l'EXIF dal flusso. Niente dipendenze aggiuntive.
     */
    @Suppress("DEPRECATION")
    private fun rotationDegrees(uri: Uri): Int {
        val fromResolver = runCatching {
            resolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.ORIENTATION),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getInt(index)
                } else {
                    0
                }
            } ?: 0
        }.getOrDefault(0)
        if (fromResolver != 0) return normalizeDegrees(fromResolver)

        return runCatching {
            resolver.openInputStream(uri)?.use { stream: InputStream ->
                val exif = ExifInterface(stream)
                when (
                    exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
    }

    private fun normalizeDegrees(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
        90, 180, 270 -> ((degrees % 360) + 360) % 360
        else -> 0
    }

    // --- lettura -------------------------------------------------------------

    private fun readLimited(uri: Uri, limit: Long): ByteArray {
        val stream = resolver.openInputStream(uri) ?: throw IllegalStateException("uri non apribile")
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw InputTooLarge()
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun sourceInfo(uri: Uri): SourceInfo {
        var name: String? = uri.lastPathSegment
        var size: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        if (size == null) {
            // Alcuni provider non espongono SIZE nella query: la lunghezza
            // del descrittore evita una lettura completa solo per contare.
            size = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length.takeIf { it >= 0L }
                }
            }.getOrNull()
        }
        return SourceInfo(
            name = name,
            size = size?.takeIf { it >= 0 },
            mime = mime,
        )
    }

    /**
     * Conta i byte con il tetto in ingresso. Restituisce `MAX_INPUT_BYTES + 1`
     * appena lo supera: nessun dato viene trattenuto.
     */
    private fun measureSize(uri: Uri): Long? {
        val stream = try {
            resolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        } ?: return null
        return stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > AttachmentRules.MAX_INPUT_BYTES) {
                    return@use AttachmentRules.MAX_INPUT_BYTES + 1
                }
            }
            total
        }
    }

    private data class SourceInfo(
        val name: String?,
        val size: Long?,
        val mime: String?,
    )

    private class InputTooLarge : Exception()

    private companion object {
        const val JPEG_MIME = "image/jpeg"
    }
}
