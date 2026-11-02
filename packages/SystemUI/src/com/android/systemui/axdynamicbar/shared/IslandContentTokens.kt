@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axdynamicbar.shared

import android.app.ActivityOptions
import android.app.BroadcastOptions
import com.android.systemui.res.R
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.android.systemui.axdynamicbar.model.IslandEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector

internal val SpaceXxs = 2.dp
internal val SpaceXs = 4.dp
internal val SpaceSm = 6.dp
internal val SpaceMd = 8.dp
internal val SpaceLg = 12.dp
internal val SpaceXl = 14.dp
internal val SpaceXxl = 16.dp
internal val SpaceSection = 20.dp
internal val SpacePanel = 24.dp
internal val SpacePanelLarge = 28.dp

internal val ShapeXs = RoundedCornerShape(8.dp)
internal val ShapeSm = RoundedCornerShape(12.dp)
internal val ShapeLg = RoundedCornerShape(24.dp)
internal val KeyguardCardCorner = 32.dp
internal val CardCorner = 28.dp
internal val ShapeXl = RoundedCornerShape(KeyguardCardCorner)
internal val ShapeCard = RoundedCornerShape(CardCorner)

internal val SizeBadge = 14.dp
internal val SizeIconSm = 20.dp
internal val SizeIconMd = 28.dp
internal val SizeButton = 48.dp
internal val SizeButtonLg = 48.dp
internal val SizeButtonXl = 64.dp
internal val SizeAlbumSm = 52.dp
internal val SizeAlbumLg = 200.dp
internal val SizeSeekHeight = 16.dp
internal val SizeProgressHeight = 8.dp
internal val SizeStrokeWidth = 2.dp
internal val SizeStrokeThin = 1.5f
internal val SizeCompactIcon = 40.dp
internal val SizeActionHeight = 48.dp

internal const val AlphaSecondary = 0.7f
internal const val AlphaTertiary = 0.5f
internal const val AlphaHint = 0.4f
internal const val AlphaDisabled = 0.3f
internal const val AlphaSubtle = 0.15f
internal const val AlphaFaint = 0.1f
internal const val AlphaBorder = 0.08f
internal const val AlphaStatusChip = 0.14f

/** How much event accent is mixed into the status bar Dynamic Bar pill (rest is theme surface). */
internal const val StatusBarDynamicChipAccentBlend = 0.18f
internal const val AlphaIconBg = 0.16f
internal const val AlphaTrack = 0.25f

internal val TsBadge: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(
        fontSize = 8.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )


internal val BatteryChargingColor = Color(0xFF66BB6A)
internal val BatteryPowerSaveColor = Color(0xFFFFA726)
internal val BatteryNeutralColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant
internal val ChipContentDark = Color(0xFF1B1B1B)

// Semantic accents follow the Monet scheme (primary / secondary / tertiary / error)
// instead of a fixed palette. Warm is the theme tertiary pulled toward error so
// alarms and hotspots stay distinct from charging (tertiary) and recording (error).
internal val RedAccent: Color
    @Composable get() = MaterialTheme.colorScheme.error
internal val PinkAccent: Color
    @Composable get() = MaterialTheme.colorScheme.error
internal val OrangeAccent: Color
    @Composable get() = warmAccent()
internal val YellowAccent: Color
    @Composable get() = warmAccent()
internal val GreenAccent: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary
internal val MintAccent: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary
internal val TealAccent: Color
    @Composable get() = MaterialTheme.colorScheme.secondary
internal val BlueAccent: Color
    @Composable get() = MaterialTheme.colorScheme.primary
internal val IndigoAccent: Color
    @Composable get() = MaterialTheme.colorScheme.secondary
internal val PurpleAccent: Color
    @Composable get() = MaterialTheme.colorScheme.primary
internal val PausedGray: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

internal val ExpressiveCardColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

@Composable
private fun warmAccent(): Color =
    lerp(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.error, 0.42f)

/** Event accent mixed into a scheme surface, the Material tonal-container equivalent. */
@Composable
internal fun tonalContainer(accent: Color, emphasis: Float = 0.22f): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = ColorUtils.calculateLuminance(scheme.surface.toArgb()) < 0.5
    val amount = (if (dark) emphasis else emphasis * 0.65f).coerceIn(0f, 1f)
    return lerp(scheme.surfaceContainerHighest, accent, amount)
}

internal val ExpandedMaxWidth = 420.dp

internal val SubtleGray: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val CardBg: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceBright
internal val DarkCard: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
internal val OnCardText: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
internal val OnCardSecondary: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val ActionBg: Color
    @Composable get() = MaterialTheme.colorScheme.primary
internal val OnActionText: Color
    @Composable get() = MaterialTheme.colorScheme.onPrimary
internal val DestructiveBg: Color
    @Composable get() = MaterialTheme.colorScheme.errorContainer
internal val OnDestructiveText: Color
    @Composable get() = MaterialTheme.colorScheme.onErrorContainer

internal val CardBorderBrush: Brush
    @Composable
    get() =
        Brush.verticalGradient(
            colors =
                listOf(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f),
                )
        )

internal val PillPrimary: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.SemiBold,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

internal val PillAccent: TextStyle
    @Composable get() = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
internal val PillMono: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.Monospace,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
internal val TsMono: TextStyle
    @Composable get() = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)

internal val ShapeIconLarge = RoundedCornerShape(20.dp)
internal val ShapeIconMedium = RoundedCornerShape(16.dp)
internal val ShapeAlbum = RoundedCornerShape(16.dp)
internal val ShapeChip = RoundedCornerShape(percent = 50)
internal val ShapeCompact = RoundedCornerShape(12.dp)

@Composable
internal fun accentColorFor(event: IslandEvent): Color = eventStyleFor(event).accent

internal fun chipContentColorOn(background: Color): Color {
    val luminance = ColorUtils.calculateLuminance(background.toArgb())
    return if (luminance > 0.4) ChipContentDark else Color.White
}

/** Status bar pill: tinted surface instead of a flat accent fill so it sits quietly on the bar. */
@Composable
internal fun statusBarDynamicChipFill(accent: Color): Color {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    return lerp(base, accent, StatusBarDynamicChipAccentBlend)
}

internal fun darkenColor(color: Color, keep: Float = 0.35f): Color =
    Color(
        red = color.red * keep,
        green = color.green * keep,
        blue = color.blue * keep,
        alpha = 1f,
    )

private const val DARK_THEME_MIN_L = 0.15
private const val LIGHT_THEME_MAX_L = 0.4

private fun ensureContrast(color: Color, isDark: Boolean): Color {
    val lum = ColorUtils.calculateLuminance(color.toArgb())
    if (isDark && lum >= DARK_THEME_MIN_L) return color
    if (!isDark && lum <= LIGHT_THEME_MAX_L) return color
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    if (isDark) {
        hsl[2] = (hsl[2] + 0.15f).coerceAtMost(0.65f)
    } else {
        hsl[2] = (hsl[2] - 0.15f).coerceAtLeast(0.25f)
    }
    return Color(ColorUtils.HSLToColor(hsl))
}

internal data class IslandColorScheme(
    val accent: Color,
    val onAccent: Color,
    val tonal: Color,
    val surfaceTint: Color,
)

@Composable
internal fun rememberIslandColors(event: IslandEvent): IslandColorScheme =
    buildColorScheme(accentColorFor(event))

@Composable
internal fun rememberMediaColors(event: IslandEvent.Media): IslandColorScheme {
    val raw = if (event.mediaColor != 0) Color(event.mediaColor) else PurpleAccent
    return buildColorScheme(raw)
}

@Composable
private fun buildColorScheme(raw: Color): IslandColorScheme {
    val isDark = ColorUtils.calculateLuminance(
        MaterialTheme.colorScheme.surface.toArgb()
    ) < 0.5
    val accent = if (!isDark) ensureContrast(raw) else raw
    return IslandColorScheme(
        accent = accent,
        onAccent = chipContentColorOn(accent),
        tonal = tonalContainer(accent),
        surfaceTint = tonalContainer(accent, emphasis = 0.12f),
    )
}

private fun ensureContrast(color: Color): Color {
    val lum = ColorUtils.calculateLuminance(color.toArgb()).toFloat()
    return if (lum > 0.5f) darkenColor(color, keep = 0.55f) else color
}

@Composable
internal fun chipAccentColorFor(event: IslandEvent): Color {
    if (event is IslandEvent.Media && event.mediaColor != 0) {
        return darkenColor(Color(event.mediaColor))
    }
    if (event is IslandEvent.AppSwitch) {
        val app = event.previousApp ?: event.recentApps.firstOrNull()
        if (app?.appIcon != null) {
            val color = rememberPaletteColor(app.appIcon!!)
            if (color != null) return color
        }
    }
    if (event is IslandEvent.Notification && event.appIcon != null) {
        val isDark = isSystemInDarkTheme()
        val color = rememberPaletteColor(event.appIcon!!)
        if (color != null) return ensureContrast(color, isDark)
    }
    if (event is IslandEvent.AospChip) {
        val ctx = LocalContext.current
        return Color(event.active.colors.background(ctx).defaultColor)
    }
    return accentColorFor(event)
}

@Composable
private fun rememberPaletteColor(drawable: Drawable): Color? {
    val color by produceState<Color?>(null, drawable) {
        
        val bitmap = try {
            withContext(Dispatchers.Main) { drawable.toBitmap(24, 24) }
        } catch (_: Exception) { null }
        value = bitmap?.let {
            withContext(Dispatchers.Default) {
                try {
                    val palette = Palette.from(it).generate()
                    val swatch =
                        palette.darkVibrantSwatch
                            ?: palette.vibrantSwatch
                            ?: palette.darkMutedSwatch
                            ?: palette.dominantSwatch
                    swatch?.let { s -> Color(s.rgb) }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    return color
}

@Composable
internal fun StatusChip(text: String, color: Color = SubtleGray) {
    Box(
        modifier =
            Modifier.background(color.copy(alpha = AlphaSubtle), ShapeChip)
                .padding(horizontal = SpaceXl, vertical = SpaceSm)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun CircleButton(
    color: Color = ActionBg,
    size: Dp = 48.dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = color,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) { content() }
    }
}

@Composable
internal fun ExpandedCardLayout(
    accentColor: Color,
    icon: @Composable () -> Unit,
    iconSize: Dp = 44.dp,
    iconBackground: Boolean = true,
    title: @Composable ColumnScope.() -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    actions: @Composable (ColumnScope.() -> Unit)? = null,
) {
    val blurActive = rememberSurfaceBlurActive()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SpaceLg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeLg)
                .background(
                    if (blurActive) accentColor.copy(alpha = AlphaIconBg)
                    else tonalContainer(accentColor)
                )
                .padding(SpaceLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceLg),
        ) {
            if (iconBackground) {
                Box(
                    modifier = Modifier
                        .size(iconSize)
                        .clip(CircleShape)
                        .background(
                            if (blurActive) accentColor.copy(alpha = AlphaSubtle)
                            else tonalContainer(accentColor, emphasis = 0.4f)
                        ),
                    contentAlignment = Alignment.Center,
                ) { icon() }
            } else {
                Box(
                    modifier = Modifier.size(iconSize),
                    contentAlignment = Alignment.Center,
                ) { icon() }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SpaceXs),
            ) { title() }
            trailing?.invoke()
        }
        actions?.invoke(this)
    }
}

@Composable
internal fun ActionChip(
    label: String,
    icon: ImageVector? = null,
    color: Color = OnActionText,
    bg: Color = ActionBg,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ShapeChip,
        color = bg,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(SizeActionHeight).padding(horizontal = SpacePanel),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceMd, Alignment.CenterHorizontally),
        ) {
            if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size(SizeIconSm))
            }
            Text(
                label,
                color = color,
                style = MaterialTheme.typography.labelLargeEmphasized,
                maxLines = 1,
            )
        }
    }
}

/** Click action supplied by a caller that does not have a notification [android.app.PendingIntent]. */
internal data class IslandCardAction(
    val label: String,
    val onClick: () -> Unit,
    val emphasized: Boolean = false,
)

@Composable
internal fun IslandCardActionRow(actions: List<IslandCardAction>, accent: Color) {
    if (actions.isEmpty()) return
    if (actions.size == 1) {
        val action = actions.first()
        ActionChip(
            label = action.label,
            color = if (action.emphasized) accent else OnActionText,
            bg = if (action.emphasized) accent.copy(alpha = AlphaIconBg) else ActionBg,
            modifier = Modifier.fillMaxWidth(),
            onClick = action.onClick,
        )
        return
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SpaceMd)) {
        actions.forEach { action ->
            ActionChip(
                label = action.label,
                color = if (action.emphasized) accent else OnActionText,
                bg = if (action.emphasized) accent.copy(alpha = AlphaIconBg) else ActionBg,
                modifier = Modifier.weight(1f),
                onClick = action.onClick,
            )
        }
    }
}

@Composable
internal fun ExpressivePillButton(
    label: String,
    icon: ImageVector? = null,
    contentColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = backgroundColor,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = SpaceXxl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceMd, Alignment.CenterHorizontally),
        ) {
            if (icon != null) {
                Icon(
                    icon, null, tint = contentColor, modifier = Modifier.size(SizeIconSm),
                )
            }
            Text(
                label,
                color = contentColor,
                style = MaterialTheme.typography.labelLargeEmphasized,
                maxLines = 1,
            )
        }
    }
}

internal data class MediaProgress(val progress: Float, val positionMs: Long)

@Composable
internal fun rememberMediaProgress(event: IslandEvent.Media): MediaProgress {
    return MediaProgress(event.progress, event.position)
}

internal fun formatElapsedTime(ms: Long): String {
    val secs = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(secs / 60, secs % 60)
}

internal fun formatCountdownSeconds(secs: Long): String {
    val s = secs.coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

internal fun formatCountdownLong(ms: Long): String {
    val secs = (ms / 1000).coerceAtLeast(0)
    val mins = secs / 60
    val hrs = mins / 60
    return if (hrs > 0) "%02d:%02d:%02d".format(hrs, mins % 60, secs % 60)
    else "%02d:%02d".format(mins, secs % 60)
}

internal fun formatStopwatch(ms: Long): String {
    val totalSecs = (ms / 1000).coerceAtLeast(0)
    val hrs = totalSecs / 3600
    val mins = (totalSecs % 3600) / 60
    val secs = totalSecs % 60
    val tenths = ((ms % 1000) / 100).coerceIn(0, 9)
    return if (hrs > 0) "%d:%02d:%02d.%d".format(hrs, mins, secs, tenths)
    else "%d:%02d.%d".format(mins, secs, tenths)
}

internal fun formatTimeAgo(timestampMs: Long, res: android.content.res.Resources): String {
    val diff = System.currentTimeMillis() - timestampMs
    if (diff < 0) return ""
    val secs = diff / 1000
    val mins = secs / 60
    val hrs = mins / 60
    val days = hrs / 24
    return when {
        secs < 60 -> res.getString(R.string.ax_dynamic_bar_just_now)
        mins < 60 -> res.getString(R.string.ax_dynamic_bar_mins_ago, mins.toInt())
        hrs < 24 -> res.getString(R.string.ax_dynamic_bar_hours_ago, hrs.toInt())
        days < 7 -> res.getString(R.string.ax_dynamic_bar_days_ago, days.toInt())
        else -> res.getString(R.string.ax_dynamic_bar_weeks_ago, (days / 7).toInt())
    }
}

@Composable
internal fun Drawable.toScaledBitmap(sizeDp: Dp): ImageBitmap {
    val px = with(LocalDensity.current) { sizeDp.roundToPx() }
    return remember(this, px) { toBitmap(px, px).asImageBitmap() }
}

internal fun chipProgressFor(event: IslandEvent, includeMediaProgress: Boolean = false): Float? =
    when (event) {
        is IslandEvent.Media ->
            if (!includeMediaProgress) null
            else if (event.duration > 0) (event.position.toFloat() / event.duration).coerceIn(0f, 1f)
            else event.progress.coerceIn(0f, 1f)
        is IslandEvent.PromotedOngoing ->
            if (event.progress >= 0f) event.progress.coerceIn(0f, 1f) else null
        is IslandEvent.Notification ->
            if (event.progress >= 0)
                (event.progress.toFloat() / event.progressMax).coerceIn(0f, 1f)
            else null
        else -> null
    }

internal fun iconKeyFor(event: IslandEvent): Any =
    when (event) {
        is IslandEvent.Media -> event.albumArt?.hashCode() ?: "media_default"
        is IslandEvent.Notification -> event.appIcon?.hashCode() ?: "notif_default"
        is IslandEvent.AppSwitch -> {
            val app = event.previousApp ?: event.recentApps.firstOrNull()
            app?.appIcon?.hashCode() ?: "app_default"
        }
        else -> event::class.simpleName ?: "default"
    }

internal fun textKeyFor(event: IslandEvent): Any =
    when (event) {
        is IslandEvent.Media -> "${event.track}|${event.artist}"
        is IslandEvent.Timer,
        is IslandEvent.Stopwatch,
        is IslandEvent.AudioRecording -> "tick_text"
        else -> event.id
    }

/**
 * Returns SF-style `@drawable/ax_accord_ic_nowplaying_*` for shuffle / repeat when the media
 * session’s custom-action label matches; otherwise null (use [resolveLabelIconVector]).
 */
internal fun resolveNowPlayingStyleActionDrawableRes(label: String): Int? {
    val lower = label.lowercase()
    return when {
        lower.contains("shuffle") -> R.drawable.ax_accord_ic_nowplaying_shuffle
        lower.contains("repeat one") ||
            lower.contains("repeat_one") ||
            lower.contains("repeat single") ||
            lower.contains("repeatsingle") ->
            R.drawable.ax_accord_ic_nowplaying_repeat_one
        lower.contains("repeat") -> R.drawable.ax_accord_ic_nowplaying_repeat
        else -> null
    }
}

internal fun resolveLabelIconVector(label: String): ImageVector {
    val lower = label.lowercase()
    return when {
        lower.contains("thumb") && lower.contains("up") -> Icons.Filled.ThumbUp
        lower.contains("thumb") && lower.contains("down") -> Icons.Filled.ThumbDown
        lower.contains("like") || lower.contains("love") || lower.contains("favorite") ->
            Icons.Filled.Favorite
        else -> Icons.Filled.Shuffle
    }
}

internal fun resolveLabelIcon(label: String): ImageVector = resolveLabelIconVector(label)

internal fun IslandEvent.MediaCustomAction.isShuffleAction(): Boolean {
    val labelLower = label.lowercase()
    val actionLower = action.lowercase()
    return labelLower.contains("shuffle")
        || actionLower.contains("shuffle")
        || actionLower.contains("set_shuffle_mode")
}

@Composable
internal fun CustomActionIcon(
    ca: IslandEvent.MediaCustomAction,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    if (ca.isShuffleAction()) {
        Icon(
            painter = painterResource(R.drawable.ax_accord_ic_nowplaying_shuffle),
            contentDescription = ca.label,
            tint = tint,
            modifier = modifier,
        )
        return
    }
    val appBitmap = ca.icon?.let { drawable ->
        remember(drawable) {
            try { drawable.toBitmap(48, 48).asImageBitmap() } catch (_: Exception) { null }
        }
    }
    if (appBitmap != null) {
        Icon(appBitmap, ca.label, tint = tint, modifier = modifier)
    } else {
        val vectorGlyphRes = resolveNowPlayingStyleActionDrawableRes(ca.label)
        if (vectorGlyphRes != null) {
            Icon(
                painter = painterResource(vectorGlyphRes),
                contentDescription = ca.label,
                tint = tint,
                modifier = modifier,
            )
        } else {
            Icon(resolveLabelIconVector(ca.label), ca.label, tint = tint, modifier = modifier)
        }
    }
}

internal fun PendingIntent.sendWithBal(context: Context, fillIntent: Intent? = null) {
    // The call card answers through here. A bare send is "BAL blocked", so mark the tap
    // interactive and allow the background activity start, the same way the shade does.
    val options =
        BroadcastOptions.makeBasic().apply {
            isInteractive = true
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        }
    send(context, 0, fillIntent, null, null, null, options.toBundle())
}

