package io.github.mirkotappini.opencoderemote

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Palette — token reali del tema builtin "opencode" (variante dark) del binario
// OpenCode 2.1.4. Non è una reinterpretazione: i valori sotto sono quelli del
// TUI, riportati 1:1. Il tema scuro è il default del prodotto; il chiaro è
// derivato con gli stessi gradini di superficie e resta leggibile ad AA.
// ---------------------------------------------------------------------------

/**
 * Token semantici che Material 3 non copre: feedback, accento, interattivo,
 * diff e syntax. Sono esposti a parte perché i composable li usano con il
 * significato che hanno nel TUI, non come semplici "colori in più".
 */
@Immutable
data class OpenPalette(
    val dark: Boolean,
    val background: Color,
    val raised: Color,
    val high: Color,
    val max: Color,
    val formField: Color,
    val text: Color,
    val muted: Color,
    val mutedText: Color,
    val border: Color,
    val scrollbar: Color,
    val error: Color,
    val warning: Color,
    val warningInk: Color,
    val success: Color,
    val info: Color,
    val accent: Color,
    val interactive: Color,
    val onAccent: Color,
    val onInteractive: Color,
    val diffAdded: Color,
    val diffAddedBg: Color,
    val diffRemoved: Color,
    val diffRemovedBg: Color,
    val diffContext: Color,
    val diffContextBg: Color,
    val diffHighlightAdded: Color,
    val diffHighlightRemoved: Color,
    val lineNumber: Color,
    val syntaxComment: Color,
    val syntaxKeyword: Color,
    val syntaxFunction: Color,
    val syntaxVariable: Color,
    val syntaxString: Color,
    val syntaxNumber: Color,
    val syntaxType: Color,
    val syntaxOperator: Color,
    val syntaxPunctuation: Color,
) {
    val markdownHeading: Color get() = accent
    val markdownLink: Color get() = interactive
    val markdownLinkText: Color get() = info
    val markdownCode: Color get() = syntaxString
    val markdownQuote: Color get() = syntaxType
    val markdownStrong: Color get() = warningInk
    val markdownHr: Color get() = muted
}

private val DarkPalette = OpenPalette(
    dark = true,
    background = Color(0xFF0A0A0A),
    raised = Color(0xFF141414),
    high = Color(0xFF1E1E1E),
    max = Color(0xFF4C4C4C),
    formField = Color(0xFF0A0A0A),
    text = Color(0xFFEEEEEE),
    muted = Color(0xFF808080),
    mutedText = Color(0xFF808080),
    border = Color(0xFF484848),
    scrollbar = Color(0xFF606060),
    error = Color(0xFFE06C75),
    warning = Color(0xFFF5A742),
    warningInk = Color(0xFFF5A742),
    success = Color(0xFF7FD88F),
    info = Color(0xFF56B6C2),
    accent = Color(0xFF9D7CD8),
    interactive = Color(0xFFFAB283),
    onAccent = Color(0xFF0A0A0A),
    onInteractive = Color(0xFF0A0A0A),
    diffAdded = Color(0xFF4FD6BE),
    diffAddedBg = Color(0xFF20303B),
    diffRemoved = Color(0xFFC53B53),
    diffRemovedBg = Color(0xFF37222C),
    diffContext = Color(0xFF828BB8),
    diffContextBg = Color(0xFF141414),
    diffHighlightAdded = Color(0xFFB8DB87),
    diffHighlightRemoved = Color(0xFFE26A75),
    lineNumber = Color(0xFF8F8F8F),
    syntaxComment = Color(0xFF808080),
    syntaxKeyword = Color(0xFF9D7CD8),
    syntaxFunction = Color(0xFFFAB283),
    syntaxVariable = Color(0xFFE06C75),
    syntaxString = Color(0xFF7FD88F),
    syntaxNumber = Color(0xFFF5A742),
    syntaxType = Color(0xFFE5C07B),
    syntaxOperator = Color(0xFF56B6C2),
    syntaxPunctuation = Color(0xFFEEEEEE),
)

/**
 * Chiaro derivato: stessa scala di superfici del dark (background → raised →
 * high → max) ma in discesa dal bianco. I toni-feedback sono scuriti quanto
 * basta per restare AA su fondo bianco; `warning` e `muted` restano quelli
 * richiesti come token, l'inchiostro leggibile è `warningInk` / `mutedText`.
 */
private val LightPalette = OpenPalette(
    dark = false,
    background = Color(0xFFFFFFFF),
    raised = Color(0xFFF5F5F5),
    high = Color(0xFFEBEBEB),
    max = Color(0xFFC3C3C3),
    formField = Color(0xFFFFFFFF),
    text = Color(0xFF1A1A1A),
    muted = Color(0xFF8A8A8A),
    mutedText = Color(0xFF5F5F5F),
    border = Color(0xFFB8B8B8),
    scrollbar = Color(0xFFA0A0A0),
    error = Color(0xFFB23B47),
    warning = Color(0xFFD68C27),
    warningInk = Color(0xFF8A5A12),
    success = Color(0xFF1F7A3D),
    info = Color(0xFF0E6F7D),
    accent = Color(0xFF6B4BB5),
    interactive = Color(0xFFB45309),
    onAccent = Color(0xFFFFFFFF),
    onInteractive = Color(0xFFFFFFFF),
    diffAdded = Color(0xFF1F7A3D),
    diffAddedBg = Color(0xFFE8F7EE),
    diffRemoved = Color(0xFFB23B47),
    diffRemovedBg = Color(0xFFFBE9EC),
    diffContext = Color(0xFF5F5F5F),
    diffContextBg = Color(0xFFF5F5F5),
    diffHighlightAdded = Color(0xFF256B3A),
    diffHighlightRemoved = Color(0xFF9B2733),
    lineNumber = Color(0xFF636363),
    syntaxComment = Color(0xFF6B6B6B),
    syntaxKeyword = Color(0xFF6B4BB5),
    syntaxFunction = Color(0xFFB45309),
    syntaxVariable = Color(0xFFB23B47),
    syntaxString = Color(0xFF1F7A3D),
    syntaxNumber = Color(0xFF8A5A12),
    syntaxType = Color(0xFF7A5A00),
    syntaxOperator = Color(0xFF0E6F7D),
    syntaxPunctuation = Color(0xFF1A1A1A),
)

private val LocalOpenPalette = staticCompositionLocalOf { DarkPalette }

/** Token del tema OpenCode: usa questo negli schermi, non i colori a mano. */
object OpenColors {
    val current: OpenPalette
        @Composable get() = LocalOpenPalette.current
}

private val DarkColors = darkColorScheme(
    primary = DarkPalette.interactive,
    onPrimary = DarkPalette.onInteractive,
    primaryContainer = DarkPalette.high,
    onPrimaryContainer = DarkPalette.text,
    inversePrimary = DarkPalette.accent,
    secondary = DarkPalette.accent,
    onSecondary = DarkPalette.onAccent,
    secondaryContainer = Color(0xFF261F17),
    onSecondaryContainer = DarkPalette.interactive,
    tertiary = DarkPalette.warning,
    onTertiary = Color(0xFF0A0A0A),
    tertiaryContainer = Color(0xFF2A1E0D),
    onTertiaryContainer = DarkPalette.warning,
    error = DarkPalette.error,
    onError = Color(0xFF0A0A0A),
    errorContainer = Color(0xFF2A1519),
    onErrorContainer = DarkPalette.text,
    background = DarkPalette.background,
    onBackground = DarkPalette.text,
    surface = DarkPalette.background,
    onSurface = DarkPalette.text,
    surfaceVariant = DarkPalette.high,
    onSurfaceVariant = DarkPalette.lineNumber,
    surfaceTint = DarkPalette.interactive,
    inverseSurface = DarkPalette.text,
    inverseOnSurface = DarkPalette.background,
    outline = DarkPalette.scrollbar,
    outlineVariant = DarkPalette.border,
    scrim = Color(0xFF000000),
    surfaceDim = DarkPalette.background,
    surfaceBright = DarkPalette.max,
    surfaceContainerLowest = DarkPalette.background,
    surfaceContainerLow = Color(0xFF101010),
    surfaceContainer = DarkPalette.raised,
    surfaceContainerHigh = DarkPalette.high,
    surfaceContainerHighest = Color(0xFF262626),
)

private val LightColors = lightColorScheme(
    primary = LightPalette.interactive,
    onPrimary = LightPalette.onInteractive,
    primaryContainer = LightPalette.high,
    onPrimaryContainer = LightPalette.text,
    inversePrimary = LightPalette.accent,
    secondary = LightPalette.accent,
    onSecondary = LightPalette.onAccent,
    secondaryContainer = Color(0xFFF0EAFA),
    onSecondaryContainer = LightPalette.accent,
    tertiary = LightPalette.warning,
    onTertiary = Color(0xFF2A1A05),
    tertiaryContainer = Color(0xFFFBEFDD),
    onTertiaryContainer = LightPalette.warningInk,
    error = LightPalette.error,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE9EC),
    onErrorContainer = Color(0xFF7A1F29),
    background = LightPalette.background,
    onBackground = LightPalette.text,
    surface = LightPalette.background,
    onSurface = LightPalette.text,
    surfaceVariant = LightPalette.high,
    onSurfaceVariant = LightPalette.mutedText,
    surfaceTint = LightPalette.interactive,
    inverseSurface = LightPalette.text,
    inverseOnSurface = LightPalette.background,
    outline = LightPalette.scrollbar,
    outlineVariant = LightPalette.border,
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFFF0F0F0),
    surfaceBright = LightPalette.background,
    surfaceContainerLowest = LightPalette.background,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = LightPalette.raised,
    surfaceContainerHigh = LightPalette.high,
    surfaceContainerHighest = Color(0xFFE0E0E0),
)

// ---------------------------------------------------------------------------
// Tipografia — Space Grotesk per i titoli, IBM Plex Sans per il testo,
// JetBrains Mono per dati tecnici, log e output dei tool.
// ---------------------------------------------------------------------------

val DisplayFamily = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

val BodyFamily = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

val MonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 46.sp,
        lineHeight = 52.sp,
        letterSpacing = (-1.2).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 38.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.9).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 27.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 23.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
    ),
)

/** Stile monospace coerente per log, tool e dati numerici. */
fun monoStyle(
    fontSize: TextUnit = 12.sp,
    lineHeight: TextUnit = 17.sp,
    weight: FontWeight = FontWeight.Normal,
): TextStyle = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = weight,
    fontSize = fontSize,
    lineHeight = lineHeight,
    letterSpacing = 0.sp,
)

private val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// ---------------------------------------------------------------------------
// Misure condivise
// ---------------------------------------------------------------------------

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Layout {
    val setupMaxWidth = 480.dp
    val listMaxWidth = 680.dp
    val chatMaxWidth = 920.dp
    val logMaxWidth = 960.dp
}

/** Tema dell'app. Scuro di default: è il contesto d'uso principale. */
@Composable
fun AppTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    val palette = if (darkTheme) DarkPalette else LightPalette

    CompositionLocalProvider(LocalOpenPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// Glifi disegnati a mano: tratto coerente, nessuna dipendenza da icone extra.
// ---------------------------------------------------------------------------

enum class ChevronDirection(val degrees: Float) {
    Right(0f),
    Down(90f),
    Left(180f),
    Up(270f),
}

@Composable
fun GlyphChevron(
    direction: ChevronDirection = ChevronDirection.Right,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    strokeWidth: Dp = 1.8.dp,
) {
    val color = LocalContentColor.current
    Canvas(modifier.size(size).rotate(direction.degrees)) {
        val s = this.size.minDimension
        val path = Path().apply {
            moveTo(s * 0.33f, s * 0.20f)
            lineTo(s * 0.67f, s * 0.50f)
            lineTo(s * 0.33f, s * 0.80f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

@Composable
fun GlyphArrowRight(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    strokeWidth: Dp = 1.8.dp,
) {
    val color = LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = strokeWidth.toPx()
        drawLine(color, Offset(s * 0.18f, s * 0.5f), Offset(s * 0.8f, s * 0.5f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.57f, s * 0.28f), Offset(s * 0.8f, s * 0.5f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.57f, s * 0.72f), Offset(s * 0.8f, s * 0.5f), w, StrokeCap.Round)
    }
}

/** Quadrato "stop": interrompe l'esecuzione in corso. */
@Composable
fun GlyphStop(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    val color = LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.16f, s * 0.16f),
            size = Size(s * 0.68f, s * 0.68f),
            cornerRadius = CornerRadius(s * 0.14f),
        )
    }
}

@Composable
fun GlyphRefresh(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    strokeWidth: Dp = 1.7.dp,
) {
    val color = LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = strokeWidth.toPx()
        val c = s / 2f
        val r = s * 0.32f
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(c - r, c - r),
            size = Size(r * 2f, r * 2f),
            style = stroke,
        )
        val angle = Math.toRadians((-55f + 280f).toDouble())
        val end = Offset(
            c + (r * cos(angle)).toFloat(),
            c + (r * sin(angle)).toFloat(),
        )
        val tangent = Offset((-sin(angle)).toFloat(), cos(angle).toFloat())
        val normal = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        val len = s * 0.15f
        val back = end - tangent * len
        drawLine(color, back + normal * len * 0.8f, end, w, StrokeCap.Round)
        drawLine(color, back - normal * len * 0.8f, end, w, StrokeCap.Round)
    }
}

@Composable
fun GlyphMore(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val color = LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val r = s * 0.07f
        listOf(0.26f, 0.5f, 0.74f).forEach { x ->
            drawCircle(color, radius = r, center = Offset(s * x, s * 0.5f))
        }
    }
}

/** Pannello laterale: rettangolo con la colonna di destra segnata. */
@Composable
fun GlyphSidebar(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    strokeWidth: Dp = 1.5.dp,
) {
    val color = LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = strokeWidth.toPx()
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.13f, s * 0.21f),
            size = Size(s * 0.74f, s * 0.58f),
            cornerRadius = CornerRadius(s * 0.12f),
            style = stroke,
        )
        drawLine(
            color,
            Offset(s * 0.63f, s * 0.24f),
            Offset(s * 0.63f, s * 0.76f),
            w,
            StrokeCap.Round,
        )
    }
}

@Composable
fun GlyphClose(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    strokeWidth: Dp = 1.6.dp,
) {
    val color = LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = strokeWidth.toPx()
        drawLine(color, Offset(s * 0.26f, s * 0.26f), Offset(s * 0.74f, s * 0.74f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.74f, s * 0.26f), Offset(s * 0.26f, s * 0.74f), w, StrokeCap.Round)
    }
}

@Composable
fun GlyphSearch(
    modifier: Modifier = Modifier,
    size: Dp = 17.dp,
    strokeWidth: Dp = 1.6.dp,
) {
    val color = LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = strokeWidth.toPx()
        val c = s * 0.43f
        val r = s * 0.26f
        drawCircle(
            color = color,
            radius = r,
            center = Offset(c, c),
            style = Stroke(width = w),
        )
        drawLine(
            color,
            Offset(c + r * 0.72f, c + r * 0.72f),
            Offset(s * 0.85f, s * 0.85f),
            w,
            StrokeCap.Round,
        )
    }
}

@Composable
fun GlyphCheck(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    strokeWidth: Dp = 1.8.dp,
    color: Color? = null,
) {
    val tint = color ?: LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = strokeWidth.toPx()
        val path = Path().apply {
            moveTo(s * 0.20f, s * 0.53f)
            lineTo(s * 0.42f, s * 0.74f)
            lineTo(s * 0.81f, s * 0.29f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
fun GlyphLock(
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
    strokeWidth: Dp = 1.4.dp,
) {
    val color = LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = strokeWidth.toPx()
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(s * 0.35f, s * 0.2f),
            size = Size(s * 0.3f, s * 0.3f),
            style = stroke,
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.25f, s * 0.45f),
            size = Size(s * 0.5f, s * 0.38f),
            cornerRadius = CornerRadius(s * 0.08f),
            style = stroke,
        )
        drawCircle(color, radius = s * 0.035f, center = Offset(s * 0.5f, s * 0.61f))
    }
}

@Composable
fun GlyphWarning(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    strokeWidth: Dp = 1.5.dp,
    color: Color? = null,
) {
    val tint = color ?: LocalContentColor.current
    Canvas(modifier.size(size)) {
        val color = tint
        val s = this.size.minDimension
        val w = strokeWidth.toPx()
        val path = Path().apply {
            moveTo(s * 0.5f, s * 0.12f)
            lineTo(s * 0.9f, s * 0.84f)
            lineTo(s * 0.1f, s * 0.84f)
            close()
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = w, join = StrokeJoin.Round),
        )
        drawLine(color, Offset(s * 0.5f, s * 0.38f), Offset(s * 0.5f, s * 0.6f), w, StrokeCap.Round)
        drawCircle(color, radius = w * 0.55f, center = Offset(s * 0.5f, s * 0.71f))
    }
}

@Composable
fun GlyphEye(
    visible: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    strokeWidth: Dp = 1.6.dp,
) {
    val color = LocalContentColor.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = strokeWidth.toPx()
        drawOval(
            color = color,
            topLeft = Offset(s * 0.1f, s * 0.3f),
            size = Size(s * 0.8f, s * 0.4f),
            style = Stroke(width = w),
        )
        drawCircle(color, radius = s * 0.1f, center = Offset(s * 0.5f, s * 0.5f))
        if (!visible) {
            drawLine(
                color,
                Offset(s * 0.18f, s * 0.18f),
                Offset(s * 0.82f, s * 0.82f),
                w,
                StrokeCap.Round,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Componenti condivisi
// ---------------------------------------------------------------------------

@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    Text(
        text = text.uppercase(java.util.Locale.ITALIAN),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 6.dp,
    pulsing: Boolean = false,
) {
    val alpha = if (pulsing) {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "dot")
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(900),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "dot-alpha",
        )
        pulse
    } else {
        1f
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

/**
 * Indicatore di lavoro: un arco che ruota lentamente (1,2 s, lineare).
 * Se gli animatori di sistema sono disattivati ("rimuovi animazioni" o
 * animator scale a 0) resta un pallino pieno, senza movimento. È decorativo:
 * il testo accanto porta lo stato, la descrizione resta per TalkBack.
 */
@Composable
fun WorkingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    color: Color = OpenColors.current.success,
    description: String = "running",
) {
    val animatorsEnabled = ValueAnimator.areAnimatorsEnabled()
    val rotation = if (animatorsEnabled) {
        val transition = rememberInfiniteTransition(label = "working")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "working-angle",
        )
        angle
    } else {
        0f
    }
    Canvas(
        modifier
            .size(size)
            .rotate(rotation)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val s = this.size.minDimension
        if (animatorsEnabled) {
            val inset = s * 0.16f
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 250f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(s - inset * 2f, s - inset * 2f),
                style = Stroke(width = s * 0.16f, cap = StrokeCap.Round),
            )
        } else {
            drawCircle(
                color = color,
                radius = s * 0.30f,
                center = Offset(s / 2f, s / 2f),
            )
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    showDot: Boolean = true,
    pulsing: Boolean = false,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.22f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (showDot) StatusDot(color, size = 5.dp, pulsing = pulsing)
            Text(text = label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/**
 * Badge delle approvazioni in attesa. Usa `warning` (ambra), mai `error`:
 * una richiesta da approvare non è un guasto. La descrizione per TalkBack
 * chiarisce sempre il numero, anche quando in riga si mostra solo la cifra.
 */
@Composable
fun ApprovalBadge(
    count: Int,
    modifier: Modifier = Modifier,
    withLabel: Boolean = false,
) {
    if (count <= 0) return
    val oc = OpenColors.current
    val description = if (count == 1) {
        "1 approval waiting"
    } else {
        "$count approvals waiting"
    }
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        color = oc.warningInk.copy(alpha = 0.12f),
        contentColor = oc.warningInk,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, oc.warningInk.copy(alpha = 0.26f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            GlyphWarning(size = 12.dp, color = oc.warningInk)
            Text(
                text = if (withLabel) {
                    if (count == 1) "1 approval" else "$count approvals"
                } else {
                    count.toString()
                },
                style = if (withLabel) {
                    MaterialTheme.typography.labelSmall
                } else {
                    monoStyle(fontSize = 10.5.sp, lineHeight = 14.sp, weight = FontWeight.Medium)
                },
                maxLines = 1,
            )
        }
    }
}


@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "Something went wrong",
    onAction: (() -> Unit)? = null,
    actionLabel: String = "Retry",
    onDismiss: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = scheme.surfaceContainer,
        contentColor = scheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, oc.error.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlyphWarning(
                size = 18.dp,
                color = oc.error,
                modifier = Modifier.padding(top = 1.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = oc.error,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = oc.mutedText,
                )
                if (onAction != null) {
                    TextButton(
                        onClick = onAction,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = oc.interactive,
                        ),
                    ) {
                        Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .semantics { contentDescription = "Close the message" },
                ) {
                    GlyphClose(size = 14.dp)
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    symbol: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(18.dp),
                )
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = symbol,
                style = monoStyle(fontSize = 20.sp, lineHeight = 24.sp, weight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

@Composable
fun SkeletonBar(
    widthFraction: Float,
    height: Dp,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = alpha)),
    )
}

/** Alpha pulsante lenta e regolare: il loading non "balla". */
@Composable
fun rememberSkeletonAlpha(): Float {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1100),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    return alpha
}

/** Sfondo del setup: due aloni d'accento e una griglia di punti appena percettibile. */
@Composable
fun TechnicalBackdrop(modifier: Modifier = Modifier) {
    val oc = OpenColors.current
    Canvas(modifier) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(oc.interactive.copy(alpha = 0.07f), Color.Transparent),
                center = Offset(size.width * 0.18f, size.height * 0.02f),
                radius = size.width * 1.05f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(oc.accent.copy(alpha = 0.05f), Color.Transparent),
                center = Offset(size.width * 0.92f, size.height * 0.72f),
                radius = size.width * 0.95f,
            ),
        )
        val step = 28.dp.toPx()
        val radius = 0.7.dp.toPx()
        var y = step
        while (y < size.height) {
            var x = step
            while (x < size.width) {
                drawCircle(oc.border.copy(alpha = 0.22f), radius = radius, center = Offset(x, y))
                x += step
            }
            y += step
        }
    }
}
