package io.github.mirkotappini.opencoderemote

import android.content.ContentResolver
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mirkotappini.opencoderemote.net.MessageFileUi
import java.nio.ByteBuffer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// M8 — attachments in the prompt.
//
// No image library: the thumbnail comes from ContentResolver (local URI selected by
// the user) or from base64 bytes already present in the message, using
// `ImageDecoder` and an in-memory LruCache keyed by a stable key (`messageId|index`
// for bubbles, `pick:<uri>` for the composer).
//
// This part is presentation only: preparing, compressing, and sending the file is
// handled by `attachments/*` + `AppViewModel` (data lane). Here we read the
// `AttachmentUi` / `MessageFileUi` contract and do not rewrite anything.
// ---------------------------------------------------------------------------

/** Bubble/row thumbnail: 220 dp at 3x ≈ 660 px, 720 also covers tablets. */
private const val THUMB_MAX_PX = 720

/** Fullscreen viewer: decodes once, no cache. */
private const val FULL_MAX_PX = 2048

private val BUBBLE_IMAGE_MAX = 220.dp
private const val BUBBLE_IMAGE_MIN = 96f

// ---------------------------------------------------------------------------
// Glyphs: same stroke as the glyphs in `Theme.kt` (Canvas, 1.5–1.8 dp, round cap).
// They live here because they are only used by attachments; if they are needed
// elsewhere later, move them to `Theme.kt` without changing the design.
// ---------------------------------------------------------------------------

/** Paperclip: the glyph for the "Attach a file" button. */
@Composable
internal fun GlyphPaperclip(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    strokeWidth: Dp = 1.7.dp,
    color: Color? = null,
) {
    val tint = color ?: LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        // Icon geometry in a 24×24 space, then scaled to the usable side. The three
        // curves are exact arcs (radii 6, 4, and 2): using a single quadratic
        // Bezier per curve flattens the "U" shapes of the paperclip, making them
        // look angular.
        val unit = s * 0.88f / 24f
        val origin = s * 0.06f
        fun px(value: Float) = origin + value * unit
        val path = Path().apply {
            moveTo(px(21.44f), px(11.05f))
            lineTo(px(12.25f), px(20.24f))
            arcTo(
                rect = Rect(px(2.005f), px(9.995f), px(14.005f), px(21.995f)),
                startAngleDegrees = 45f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            lineTo(px(12.95f), px(2.56f))
            arcTo(
                rect = Rect(px(11.78f), px(1.39f), px(19.78f), px(9.39f)),
                startAngleDegrees = 225f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            lineTo(px(9.41f), px(17.41f))
            arcTo(
                rect = Rect(px(5.995f), px(13.995f), px(9.995f), px(17.995f)),
                startAngleDegrees = 45f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            lineTo(px(15.07f), px(6.10f))
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/** Document with folded corner: file, not image. */
@Composable
internal fun GlyphDocument(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    strokeWidth: Dp = 1.5.dp,
    color: Color? = null,
) {
    val tint = color ?: LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = strokeWidth.toPx()
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val sheet = Path().apply {
            moveTo(s * 0.24f, s * 0.10f)
            lineTo(s * 0.60f, s * 0.10f)
            lineTo(s * 0.78f, s * 0.28f)
            lineTo(s * 0.78f, s * 0.90f)
            lineTo(s * 0.24f, s * 0.90f)
            close()
        }
        drawPath(sheet, tint, style = stroke)
        val fold = Path().apply {
            moveTo(s * 0.60f, s * 0.10f)
            lineTo(s * 0.60f, s * 0.28f)
            lineTo(s * 0.78f, s * 0.28f)
        }
        drawPath(fold, tint, style = stroke)
        drawLine(
            tint,
            Offset(s * 0.36f, s * 0.54f),
            Offset(s * 0.66f, s * 0.54f),
            w,
            StrokeCap.Round,
        )
        drawLine(
            tint,
            Offset(s * 0.36f, s * 0.68f),
            Offset(s * 0.58f, s * 0.68f),
            w,
            StrokeCap.Round,
        )
    }
}

/** Frame with mountain and sun: "gallery". */
@Composable
internal fun GlyphGallery(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    strokeWidth: Dp = 1.5.dp,
    color: Color? = null,
) {
    val tint = color ?: LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = strokeWidth.toPx()
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(s * 0.10f, s * 0.16f),
            size = Size(s * 0.80f, s * 0.68f),
            cornerRadius = CornerRadius(s * 0.13f),
            style = stroke,
        )
        drawCircle(
            color = tint,
            radius = s * 0.045f,
            center = Offset(s * 0.33f, s * 0.36f),
        )
        val ridge = Path().apply {
            moveTo(s * 0.15f, s * 0.72f)
            lineTo(s * 0.40f, s * 0.47f)
            lineTo(s * 0.57f, s * 0.64f)
            lineTo(s * 0.68f, s * 0.53f)
            lineTo(s * 0.85f, s * 0.70f)
        }
        drawPath(ridge, tint, style = stroke)
    }
}

/** Body with objective: "camera". */
@Composable
internal fun GlyphCamera(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    strokeWidth: Dp = 1.5.dp,
    color: Color? = null,
) {
    val tint = color ?: LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = strokeWidth.toPx()
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(s * 0.10f, s * 0.30f),
            size = Size(s * 0.80f, s * 0.52f),
            cornerRadius = CornerRadius(s * 0.11f),
            style = stroke,
        )
        val bump = Path().apply {
            moveTo(s * 0.34f, s * 0.30f)
            lineTo(s * 0.40f, s * 0.18f)
            lineTo(s * 0.60f, s * 0.18f)
            lineTo(s * 0.66f, s * 0.30f)
        }
        drawPath(bump, tint, style = stroke)
        drawCircle(
            color = tint,
            radius = s * 0.14f,
            center = Offset(s * 0.50f, s * 0.56f),
            style = stroke,
        )
    }
}

// ---------------------------------------------------------------------------
// Readable weight: "840 kB", "1.2 MB" (English decimal point, decimal units).
// ---------------------------------------------------------------------------

internal fun fileSizeLabel(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    return when {
        bytes < 1_000L -> "$bytes B"
        bytes < 1_000_000L -> decimalLabel(bytes / 1_000.0) + " kB"
        bytes < 1_000_000_000L -> decimalLabel(bytes / 1_000_000.0) + " MB"
        else -> decimalLabel(bytes / 1_000_000_000.0) + " GB"
    }
}

private fun decimalLabel(value: Double): String =
    if (value >= 10.0) {
        Math.round(value).toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }

// ---------------------------------------------------------------------------
// Thumbnail sources and in-memory cache.
// ---------------------------------------------------------------------------

/** Where the preview comes from: base64 bytes of the message or a local URI. */
internal sealed interface ImageSource {
    data class Inline(val base64: String) : ImageSource
    data class Local(val uri: String) : ImageSource
}

private object ThumbCache {
    private const val MAX_BYTES = 24 * 1024 * 1024
    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, value: ImageBitmap) {
        cache.put(key, value)
    }
}

/**
 * Memoized thumbnail for [key] (plus max side): decoding runs on IO, the cache
 * avoids re-decoding on every recomposition or scroll.
 */
@Composable
internal fun rememberAttachmentImage(
    key: String,
    source: ImageSource?,
    maxPx: Int = THUMB_MAX_PX,
    cache: Boolean = true,
): ImageBitmap? {
    val imageSource = source ?: return null
    val context = LocalContext.current
    val cacheKey = "$key@$maxPx"
    val hit = remember(cacheKey) { if (cache) ThumbCache.get(cacheKey) else null }
    if (hit != null) return hit
    val loaded by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = cacheKey,
        key2 = imageSource,
    ) {
        val decoded = withContext(Dispatchers.IO) { decodeImage(context, imageSource, maxPx) }
        if (decoded != null && cache) ThumbCache.put(cacheKey, decoded)
        value = decoded
    }
    return loaded
}

private fun decodeImage(context: Context, source: ImageSource, maxPx: Int): ImageBitmap? =
    runCatching {
        when (source) {
            is ImageSource.Inline -> {
                val bytes = base64Bytes(source.base64)
                if (bytes == null) {
                    null
                } else {
                    decodeBitmap({ ImageDecoder.createSource(ByteBuffer.wrap(bytes)) }, maxPx)
                }
            }

            is ImageSource.Local -> {
                val uri = Uri.parse(source.uri)
                decodeBitmap({ ImageDecoder.createSource(context.contentResolver, uri) }, maxPx)
            }
        }
    }.getOrNull()

/**
 * Decode by resizing the long side to [maxPx]: `setTargetSampleSize` works on the
 * header, so full-resolution bitmaps are never allocated (a 30 MB JPEG stays
 * lightweight).
 */
private fun decodeBitmap(
    source: () -> ImageDecoder.Source,
    maxPx: Int,
): ImageBitmap? = runCatching {
    ImageDecoder.decodeBitmap(source()) { decoder, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > maxPx) {
            decoder.setTargetSampleSize((longest + maxPx - 1) / maxPx)
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }.asImageBitmap()
}.getOrNull()

private fun base64Bytes(raw: String): ByteArray? = runCatching {
    val payload = raw.substringAfter("base64,", raw).filterNot { it.isWhitespace() }
    if (payload.isEmpty()) null else Base64.decode(payload, Base64.DEFAULT)
}.getOrNull()

/** Approximate bytes from base64: this is the weight the server received. */
private fun base64ByteCount(raw: String): Long {
    val payload = raw.substringAfter("base64,", raw).filterNot { it.isWhitespace() }
    if (payload.isEmpty()) return 0L
    val padding = payload.takeLast(2).count { it == '=' }
    return (payload.length.toLong() * 3L / 4L - padding).coerceAtLeast(0L)
}

/**
 * Weight of a local URI (optimistic message): the query may be missing, never
 * crash.
 */
@Composable
private fun rememberLocalSize(uri: String?): Long? {
    val context = LocalContext.current
    val size by produceState<Long?>(initialValue = null, key1 = uri) {
        value = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) { querySize(context.contentResolver, uri) }
        }
    }
    return size
}

private fun querySize(resolver: ContentResolver, uri: String): Long? = runCatching {
    resolver.query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
        }
}.getOrNull()

// ---------------------------------------------------------------------------
// Square thumbnail (composer and chip) and full-screen viewer.
// ---------------------------------------------------------------------------

/** 40 dp thumbnail: decoded image, or type glyph. */
@Composable
internal fun FileThumb(
    cacheKey: String,
    source: ImageSource?,
    size: Dp = 40.dp,
) {
    val oc = OpenColors.current
    val bitmap = rememberAttachmentImage(cacheKey, source)
    Box(
        modifier = Modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(oc.high),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            source != null -> GlyphGallery(size = size * 0.5f, color = oc.mutedText)
            else -> GlyphDocument(size = size * 0.5f, color = oc.mutedText)
        }
    }
}

internal data class ImageViewerRequest(
    val key: String,
    val source: ImageSource,
    val name: String,
)

/** Full-screen image: `background` backdrop, "close" and tap to exit. */
@Composable
internal fun ImageViewer(
    request: ImageViewerRequest,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val bitmap = rememberAttachmentImage(
        key = "full:${request.key}",
        source = request.source,
        maxPx = FULL_MAX_PX,
        cache = false,
    )
    // Same layout as the model picker: full-screen dialog with the app surface, so
    // the background is `background` even over the chat.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.background,
            contentColor = scheme.onBackground,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Close the image",
                        onClick = onDismiss,
                    ),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = request.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(8.dp),
                    )
                } else {
                    WorkingIndicator(
                        size = 16.dp,
                        color = oc.mutedText,
                        description = "loading the image",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(8.dp)
                        .heightIn(min = 48.dp)
                        .clip(CircleShape)
                        .background(scheme.background.copy(alpha = 0.7f))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Close",
                            onClick = onDismiss,
                        )
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "close",
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.mutedText,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Composer attachment row.
// ---------------------------------------------------------------------------

/**
 * Above the field: thumbnail + name + weight and the X, or the preparation state,
 * or the dismissible error. The sheet is irrelevant: here we only see what the
 * ViewModel exposes.
 */
@Composable
internal fun AttachmentRow(
    attachment: AttachmentUi?,
    preparing: Boolean,
    error: String?,
    onClear: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachment == null && !preparing && error == null) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when {
            preparing -> PreparingRow()
            attachment != null -> AttachmentCard(attachment = attachment, onClear = onClear)
        }
        if (error != null) {
            AttachmentErrorRow(text = error, onDismiss = onDismissError)
        }
    }
}

@Composable
private fun PreparingRow() {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, oc.border.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clearAndSetSemantics { contentDescription = "preparing the attachment…" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WorkingIndicator(
                size = 14.dp,
                color = oc.success,
                description = "waiting",
            )
            Text(
                text = "preparing the attachment…",
                style = MaterialTheme.typography.labelMedium,
                color = oc.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AttachmentCard(
    attachment: AttachmentUi,
    onClear: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val source = remember(attachment.previewUri, attachment.isImage) {
        if (attachment.isImage) ImageSource.Local(attachment.previewUri) else null
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, oc.border.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FileThumb(
                cacheKey = "pick:${attachment.previewUri}",
                source = source,
                size = 40.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = oc.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = fileSizeLabel(attachment.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = oc.mutedText,
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClick = onClear,
                    )
                    .semantics { contentDescription = "Remove attachment" },
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides oc.mutedText) {
                    GlyphClose(size = 16.dp)
                }
            }
        }
    }
}

@Composable
private fun AttachmentErrorRow(
    text: String,
    onDismiss: () -> Unit,
) {
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = "Close the notice",
                onClick = onDismiss,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlyphWarning(size = 14.dp, color = oc.error)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = oc.error,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// Selection sheet: gallery, camera, file. Same sheet as `TurnDetail`.
// ---------------------------------------------------------------------------

internal enum class AttachTarget { Gallery, Camera, Document }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachSheet(
    onDismiss: () -> Unit,
    onPick: (AttachTarget) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Eyebrow(
                    text = "attach",
                    color = oc.mutedText,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Close",
                            onClick = onDismiss,
                        )
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "cancel",
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.mutedText,
                    )
                }
            }
            AttachSheetRow(
                label = "gallery",
                onClick = { onPick(AttachTarget.Gallery) },
                glyph = { GlyphGallery(size = 20.dp, color = oc.mutedText) },
            )
            SheetDivider()
            AttachSheetRow(
                label = "camera",
                onClick = { onPick(AttachTarget.Camera) },
                glyph = { GlyphCamera(size = 20.dp, color = oc.mutedText) },
            )
            SheetDivider()
            AttachSheetRow(
                label = "file",
                onClick = { onPick(AttachTarget.Document) },
                glyph = { GlyphDocument(size = 19.dp, color = oc.mutedText) },
            )
        }
    }
}

@Composable
private fun AttachSheetRow(
    label: String,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides oc.mutedText) {
            glyph()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = oc.text,
            maxLines = 1,
        )
    }
}

@Composable
private fun SheetDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = OpenColors.current.border.copy(alpha = 0.5f),
    )
}

// ---------------------------------------------------------------------------
// Attachments inside the user's bubble.
// ---------------------------------------------------------------------------

private data class AttachmentView(
    val key: String,
    val name: String,
    val isImage: Boolean,
    val source: ImageSource?,
    val sizeBytes: Long?,
    val localUri: String?,
)

/**
 * A bubble can carry multiple files (the contract is a list). The image is only
 * visible if the content is available: server base64, or a local URI for an
 * optimistic message still in the queue. With only the server `uri`, there is a
 * chip, no preview.
 */
@Composable
internal fun MessageAttachments(
    messageId: String,
    files: List<MessageFileUi>,
    pending: Boolean,
    onOpenImage: (ImageViewerRequest) -> Unit,
) {
    if (files.isEmpty()) return
    val views = remember(files, pending) {
        files.mapIndexed { index, file -> attachmentView(messageId, index, file, pending) }
    }
    // Without `fillMaxWidth`: the bubble must stay as wide as the image, not the
    // maximum allowed. `maxWidth` here is still the bubble's limit, so the
    // thumbnail cannot overflow.
    BoxWithConstraints {
        val maxSide = minOf(BUBBLE_IMAGE_MAX, maxWidth)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            views.forEach { view ->
                key(view.key) {
                    val source = view.source
                    if (view.isImage && source != null) {
                        AttachmentImage(
                            cacheKey = view.key,
                            source = source,
                            name = view.name,
                            maxSide = maxSide,
                            onClick = {
                                onOpenImage(
                                    ImageViewerRequest(
                                        key = view.key,
                                        source = source,
                                        name = view.name,
                                    ),
                                )
                            },
                        )
                    } else {
                        AttachmentChip(
                            name = view.name,
                            sizeBytes = view.sizeBytes ?: rememberLocalSize(view.localUri),
                        )
                    }
                }
            }
        }
    }
}

private fun attachmentView(
    messageId: String,
    index: Int,
    file: MessageFileUi,
    pending: Boolean,
): AttachmentView {
    val name = file.name?.takeIf { it.isNotBlank() } ?: "attachment"
    val mime = file.mime?.takeIf { it.isNotBlank() }
    val image = mime?.startsWith("image/", ignoreCase = true) == true || looksLikeImage(name)
    val inline = file.dataBase64?.takeIf { it.isNotBlank() }
    val local = file.uri?.takeIf { it.isNotBlank() }
    val source = when {
        inline != null -> ImageSource.Inline(inline)
        local != null && pending -> ImageSource.Local(local)
        else -> null
    }
    return AttachmentView(
        key = "$messageId|$index",
        name = name,
        isImage = image,
        source = if (image) source else null,
        sizeBytes = inline?.let { base64ByteCount(it) }?.takeIf { it > 0L },
        localUri = local.takeIf { pending && image },
    )
}

private val imageExtensions = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif",
)

private fun looksLikeImage(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in imageExtensions
}

@Composable
private fun AttachmentImage(
    cacheKey: String,
    source: ImageSource,
    name: String,
    maxSide: Dp,
    onClick: () -> Unit,
) {
    val oc = OpenColors.current
    val bitmap = rememberAttachmentImage(cacheKey, source)
    val size = previewSize(bitmap, maxSide)
    Box(
        modifier = Modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(oc.high)
            .clickable(
                role = Role.Button,
                onClickLabel = "Open the image",
                onClick = onClick,
            )
            .semantics { contentDescription = "image $name" },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GlyphGallery(size = 20.dp, color = oc.mutedText)
        }
    }
}

/** Max 220×220 dp frame, image aspect ratio, never below 96 dp. */
private fun previewSize(bitmap: ImageBitmap?, maxSide: Dp): DpSize {
    val ratio = bitmap?.let { it.width.toFloat() / it.height.toFloat() } ?: 1.4f
    val max = maxSide.value
    val min = minOf(BUBBLE_IMAGE_MIN, max)
    return if (ratio >= 1f) {
        DpSize(maxSide, (max / ratio).coerceIn(min, max).dp)
    } else {
        DpSize((max * ratio).coerceIn(min, max).dp, maxSide)
    }
}

@Composable
private fun AttachmentChip(
    name: String,
    sizeBytes: Long?,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surfaceContainerLowest)
            .border(1.dp, oc.border.copy(alpha = 0.6f), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlyphDocument(size = 18.dp, color = oc.mutedText)
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                color = oc.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sizeBytes != null) {
                Text(
                    text = fileSizeLabel(sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = oc.mutedText,
                    maxLines = 1,
                )
            }
        }
    }
}
