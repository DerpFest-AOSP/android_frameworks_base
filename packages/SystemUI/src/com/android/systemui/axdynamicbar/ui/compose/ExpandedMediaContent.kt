package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.widget.SeekBar
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.ui.widget.MediaBlendBackdropView
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.res.R
import kotlinx.coroutines.delay

private val AlbumArtSize = 80.dp
private val PlayPauseSize = 56.dp
private val ControlButtonSize = 44.dp
private val ControlIconSize = 22.dp
private val SeekBarHeight = 28.dp

/** Taller scrub hit-area + thicker bar for expanded cinematic media (was 28 dp / 4 dp track). */
private val CinematicSeekGestureHeightDp = 44.dp

private val CinematicSeekTrackThicknessDp = 7.dp

/** Accord full-player `OverlaySlider` `app:resizeFactor` — track thickens while touched (tuned down vs stock 2.5). */
private const val AccordOverlaySeekResizeFactor = 1.55f

/** Elapsed/duration labels: light emphasis on scrub (track grows more than text). */
private const val CinematicSeekTimeScaleFactor = 1.08f

/**
 * Softer than [Spring.StiffnessMedium] so seek emphasize (track height / alphas) eases like Accord’s
 * OverlaySlider resize instead of snapping. Use [SpringSpec] so [Dp] resolves on all Compose snapshots.
 */
private val AccordSeekEmphasizeSpringFloat =
    SpringSpec<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )

private val AccordSeekEmphasizeSpringDp =
    SpringSpec<Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )

private val MediaPopupCardShape = RoundedCornerShape(28.dp)
private val CinematicArtBoxSize = 64.dp
private val CinematicArtInnerRadius = RoundedCornerShape(11.dp)

private val CinematicTimeSlotWidth = 50.dp

/**
 * Accord `BottomNavigation.BottomSheet.ActionButton.Preview`: 54 dp pads, icon 28 dp — slightly tighter
 * for the Dynamic Bar card width. `@drawable/ax_accord_ic_prop_*` vectors: 123Duo3
 * (@@Duo3_123), 123duo3@gmail.com. Shuffle/repeat: `@drawable/ax_accord_ic_nowplaying_*`
 * (123Duo3 / @@Duo3_123).
 */
private val AccordPreviewTransportTouchDp = 48.dp

private val AccordPreviewSideIconDp = 26.dp
private val AccordPreviewMainIconDp = 24.dp

/**
 * Thin progress line echoing Accord full-player `OverlaySlider` (without vendoring Cupertino):
 * spring-thickened track ([AccordOverlaySeekResizeFactor]) and brighter fill while the pointer
 * is down, matching `resizeFactor` + emphasize styling; progress still follows [displayFraction].
 */
@Composable
private fun AccordCinematicLinearSeekVisual(
    displayFraction: Float,
    isPlaying: Boolean,
    isScrubbing: Boolean,
    seekPointerDown: Boolean,
    modifier: Modifier = Modifier,
) {
    val interaction = seekPointerDown || isScrubbing
    val trackHeight by animateDpAsState(
        targetValue =
            CinematicSeekTrackThicknessDp *
                if (interaction) AccordOverlaySeekResizeFactor else 1f,
        animationSpec = AccordSeekEmphasizeSpringDp,
        label = "accordSeekTrackH",
    )
    val trackAlphaRest = if (isPlaying || isScrubbing) 0.30f else 0.20f
    val fillAlphaRest = if (isPlaying || isScrubbing) 0.92f else 0.50f
    val trackAlpha by animateFloatAsState(
        targetValue = if (interaction) (trackAlphaRest + 0.10f).coerceAtMost(0.45f) else trackAlphaRest,
        animationSpec = AccordSeekEmphasizeSpringFloat,
        label = "accordSeekTrackA",
    )
    val fillAlpha by animateFloatAsState(
        targetValue = if (interaction) 1f else fillAlphaRest,
        animationSpec = AccordSeekEmphasizeSpringFloat,
        label = "accordSeekFillA",
    )
    val radius = trackHeight / 2
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(radius))
                .background(Color.White.copy(alpha = trackAlpha)),
    ) {
        val frac = displayFraction.coerceIn(0f, 1f)
        Box(
            Modifier
                .width(maxWidth * frac)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = fillAlpha)),
        )
    }
}

/**
 * Inspired by Accord `BlendView`: saturate art, blur, then translucent scrims — see
 * `uk.akane.cupertino.widget.special.BlendView` (blur radius, `enhanceBitmap` saturation).
 * Softer “mesh” when the source is downscaled and blur is strong (Compose Image fallback path).
 */
private const val MEDIA_BACKDROP_BLUR_RADIUS_PX = 120f

/** Max side for album-art bitmap before blur (fallback when [MediaBlendBackdropView] is unused). */
private val MediaBackdropAlbumArtMaxDp = 196.dp

/**
 * Accord uses 2× saturation on the source bitmap; dial down slightly for compact SystemUI chrome.
 */
private const val MEDIA_BACKDROP_SATURATION = 1.55f

/** Darker than Accord `frontShadeColor` (#59000000) so white media text stays readable. */
private val AccordFrontShadeColor = Color(0x80000000)

/** Opaque floor under album blur; the bar overlay is [android.graphics.PixelFormat.TRANSLUCENT]. */
private val CinematicCardBase = Color(0xFF161616)

private fun createMediaBackdropRenderEffect(): RenderEffect {
    val saturation =
        RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(MEDIA_BACKDROP_SATURATION)
                },
            ),
        )
    val blur =
        RenderEffect.createBlurEffect(
            MEDIA_BACKDROP_BLUR_RADIUS_PX,
            MEDIA_BACKDROP_BLUR_RADIUS_PX,
            Shader.TileMode.MIRROR,
        )

    return RenderEffect.createChainEffect(
        blur,
        saturation,
    )
}

@Composable
private fun rememberMediaBackdropRenderEffect(): androidx.compose.ui.graphics.RenderEffect? =
    remember {
        if (Build.VERSION.SDK_INT < 31) {
            null
        } else {
            createMediaBackdropRenderEffect().asComposeRenderEffect()
        }
    }

private data class MediaGlassBrushes(
    val albumWash: Brush,
    val vignette: Brush,
    val vignetteSides: Brush,
    val frostSheen: Brush,
)

/**
 * iOS-like frosted stack: blurred art + dynamic accent wash + soft vignette + top light edge.
 */
@Composable
private fun rememberMediaGlassBackgroundLayers(accent: Color, hasArt: Boolean): MediaGlassBrushes {
    val warmTint = remember(accent) { lerp(accent, Color(0xFFFFB74D), 0.42f) }
    val coolTint = remember(accent) { lerp(accent, IndigoAccent.copy(alpha = 1f), 0.38f) }
    val albumWash =
        remember(accent, warmTint, coolTint, hasArt) {
            Brush.linearGradient(
                0f to coolTint.copy(alpha = if (hasArt) 0.22f else 0.28f),
                0.45f to accent.copy(alpha = if (hasArt) 0.14f else 0.20f),
                1f to warmTint.copy(alpha = if (hasArt) 0.20f else 0.24f),
            )
        }
    val vignette =
        remember(hasArt) {
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0f),
                0.55f to Color.Transparent,
                1f to Color.Black.copy(alpha = if (hasArt) 0.28f else 0.14f),
            )
        }
    val vignetteSides =
        remember(hasArt) {
            Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = if (hasArt) 0.12f else 0.06f),
                0.14f to Color.Transparent,
                0.86f to Color.Transparent,
                1f to Color.Black.copy(alpha = if (hasArt) 0.12f else 0.06f),
            )
        }
    val frostSheen =
        remember {
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.14f),
                0.18f to Color.White.copy(alpha = 0.04f),
                0.42f to Color.Transparent,
                1f to Color.Transparent,
            )
        }
    return MediaGlassBrushes(albumWash, vignette, vignetteSides, frostSheen)
}

@Composable
internal fun AccordMediaSurface(
    event: IslandEvent.Media,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = rememberMediaColors(event)
    val accent = colors.accent
    val hasArt = event.albumArt != null
    val opaqueCardBase =
        remember(accent) { lerp(CinematicCardBase, darkenColor(accent, 0.18f), 0.40f) }
    val glassBrushes = rememberMediaGlassBackgroundLayers(accent, hasArt)
    val useAlbumBlendBackdrop =
        hasArt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val composeBackdropEffect = rememberMediaBackdropRenderEffect()

    Box(
        modifier =
            modifier
                .shadow(20.dp, MediaPopupCardShape)
                .clip(MediaPopupCardShape)
                .background(opaqueCardBase),
    ) {
        when {
            useAlbumBlendBackdrop -> {
                AndroidView(
                    factory = { MediaBlendBackdropView(it) },
                    modifier = Modifier.matchParentSize(),
                    update = { view -> view.bindAlbumArt(event.albumArt) },
                    onRelease = { view -> view.releaseBackdrop() },
                )
            }
            hasArt -> {
                Image(
                    bitmap = event.albumArt!!.toScaledBitmap(MediaBackdropAlbumArtMaxDp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier.matchParentSize().graphicsLayer {
                            if (composeBackdropEffect != null) {
                                renderEffect = composeBackdropEffect
                            }
                            scaleX = 1.08f
                            scaleY = 1.08f
                        },
                )
                Box(Modifier.matchParentSize().background(AccordFrontShadeColor))
            }
        }

        Box(Modifier.matchParentSize().background(glassBrushes.albumWash))
        Box(Modifier.matchParentSize().background(glassBrushes.vignette))
        Box(Modifier.matchParentSize().background(glassBrushes.vignetteSides))
        Box(Modifier.matchParentSize().background(glassBrushes.frostSheen))
        content()
    }
}

@Composable
internal fun MediaCard(event: IslandEvent.Media, interactor: IslandActions) {
    val colors = rememberMediaColors(event)
    val accent = colors.accent
    val onCard = Color.White
    val onCardSub = onCard.copy(alpha = 0.55f)

    AccordMediaSurface(event, Modifier.fillMaxWidth().wrapContentHeight()) {
        Column(
            modifier =
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier =
                        Modifier.size(CinematicArtBoxSize)
                            .clip(CinematicArtInnerRadius)
                            .background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        event.albumArt != null ->
                            Image(
                                bitmap = event.albumArt!!.toScaledBitmap(CinematicArtBoxSize),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CinematicArtInnerRadius),
                            )
                        event.appIcon != null ->
                            Image(
                                bitmap = event.appIcon!!.toScaledBitmap(42.dp),
                                contentDescription = null,
                                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(9.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        else ->
                            Icon(
                                Icons.Filled.MusicNote,
                                null,
                                tint = onCard.copy(alpha = 0.50f),
                                modifier = Modifier.size(30.dp),
                            )
                    }
                }

                val openApp = {
                    interactor.openMediaApp()
                    interactor.collapseIsland()
                }
                val titleTarget =
                    event.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_now_playing) }
                AnimatedContent(
                    targetState = titleTarget to event.artist,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (
                            fadeIn(
                                animationSpec = tween(240, easing = FastOutSlowInEasing),
                            ) + slideInVertically(
                                animationSpec = tween(240, easing = FastOutSlowInEasing),
                                initialOffsetY = { fullHeight -> fullHeight / 10 },
                            )
                        ) togetherWith (
                            fadeOut(
                                animationSpec = tween(200, easing = FastOutSlowInEasing),
                            ) + slideOutVertically(
                                animationSpec = tween(200, easing = FastOutSlowInEasing),
                                targetOffsetY = { fullHeight -> -fullHeight / 10 },
                            )
                        )
                    },
                    label = "cinematic_media_metadata",
                ) { (trackLine, artistLine) ->
                    Column(
                        modifier =
                            Modifier.fillMaxWidth().clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { openApp() },
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = trackLine,
                            style =
                                TextStyle(
                                    color = onCard,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.2).sp,
                                ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (artistLine.isNotEmpty()) {
                            Text(
                                text = artistLine,
                                style =
                                    TextStyle(
                                        color = onCardSub,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            MediaControls(
                event = event,
                interactor = interactor,
                accent = accent,
                cinematic = true,
            )

            if (event.duration > 0L) {
                Spacer(Modifier.height(12.dp))
                MediaSeekBar(
                    event = event,
                    interactor = interactor,
                    accent = accent,
                    cinematic = true,
                )
            }
        }
    }
}

@Composable
internal fun MediaExpanded(
    event: IslandEvent.Media,
    interactor: IslandActions,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMediaColors(event)
    val accent = colors.accent

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SpaceXxl)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                interactor.openMediaApp()
                interactor.collapseIsland()
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceXxl),
        ) {
            event.albumArt?.let { art ->
                Image(
                    bitmap = art.toScaledBitmap(SizeAlbumSm),
                    contentDescription = null,
                    modifier = Modifier.size(SizeAlbumSm).clip(ShapeLg),
                    contentScale = ContentScale.Crop,
                )
            } ?: Surface(
                modifier = Modifier.size(SizeAlbumSm),
                shape = ShapeLg,
                color = accent.copy(alpha = AlphaSubtle),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Filled.MusicNote, null,
                        tint = accent,
                        modifier = Modifier.size(SpacePanel),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SpaceXs),
            ) {
                Text(
                    event.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_now_playing) },
                    color = OnCardText,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (event.artist.isNotEmpty()) {
                    Text(
                        event.artist,
                        color = accent,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            event.appIcon?.let { icon ->
                Image(
                    bitmap = icon.toScaledBitmap(SizeIconSm),
                    contentDescription = null,
                    modifier = Modifier.size(SizeIconSm).clip(ShapeXs),
                    colorFilter = ColorFilter.tint(OnCardText),
                )
            }
        }

        MediaControls(event, interactor, accent)
        if (event.duration > 0L) {
            MediaSeekBar(event, interactor, accent)
        }
    }
}

@Composable
private fun MediaControls(
    event: IslandEvent.Media,
    interactor: IslandActions,
    accent: Color,
    cinematic: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (cinematic) {
        /** Accord preview player: Material 54 dp `@drawable/ic_prop_*`; prev = next @ 180° in full UI. */
        val onCard = Color.White
        val iconTint = ColorFilter.tint(onCard)
        val sideTargets = AccordPreviewTransportTouchDp
        val sideIcon = AccordPreviewSideIconDp
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (event.customActions.isNotEmpty()) {
                val ca = event.customActions.first()
                Box(
                    modifier =
                        Modifier.size(sideTargets)
                            .clip(CircleShape)
                            .clickable { ca.perform(interactor) },
                    contentAlignment = Alignment.Center,
                ) {
                    CustomActionIcon(
                        ca = ca,
                        tint = onCard,
                        modifier = Modifier.size(sideIcon),
                    )
                }
            } else {
                Box(
                    modifier = Modifier.size(sideTargets),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ax_accord_ic_nowplaying_shuffle),
                        contentDescription =
                            stringResource(R.string.ax_dynamic_bar_shuffle),
                        tint = onCard.copy(alpha = 0.35f),
                        modifier = Modifier.size(sideIcon),
                    )
                }
            }
            Box(
                modifier =
                    Modifier.size(sideTargets)
                        .clip(CircleShape)
                        .clickable { interactor.skipPrev() },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ax_accord_ic_prop_next),
                    contentDescription = stringResource(R.string.ax_dynamic_bar_previous),
                    colorFilter = iconTint,
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier.size(sideIcon).graphicsLayer { rotationZ = 180f },
                )
            }
            Box(
                modifier =
                    Modifier.size(AccordPreviewTransportTouchDp)
                        .clip(CircleShape)
                        .background(onCard.copy(alpha = 0.15f))
                        .clickable { interactor.togglePlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter =
                        painterResource(
                            if (event.isPlaying) R.drawable.ax_accord_ic_prop_pause
                            else R.drawable.ax_accord_ic_prop_play,
                        ),
                    contentDescription =
                        if (event.isPlaying) stringResource(R.string.ax_dynamic_bar_pause)
                        else stringResource(R.string.ax_dynamic_bar_play),
                    colorFilter = iconTint,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(AccordPreviewMainIconDp),
                )
            }
            Box(
                modifier =
                    Modifier.size(sideTargets)
                        .clip(CircleShape)
                        .clickable { interactor.skipNext() },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ax_accord_ic_prop_next),
                    contentDescription = stringResource(R.string.ax_dynamic_bar_next),
                    colorFilter = iconTint,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(sideIcon),
                )
            }
            if (event.customActions.size > 1) {
                val ca = event.customActions[1]
                Box(
                    modifier =
                        Modifier.size(sideTargets)
                            .clip(CircleShape)
                            .clickable { ca.perform(interactor) },
                    contentAlignment = Alignment.Center,
                ) {
                    CustomActionIcon(
                        ca = ca,
                        tint = onCard,
                        modifier = Modifier.size(sideIcon),
                    )
                }
            } else {
                Box(
                    modifier =
                        Modifier.size(sideTargets)
                            .clip(CircleShape)
                            .clickable {
                                interactor.openMediaOutputSwitcher()
                                interactor.collapseIsland()
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.VolumeUp,
                        stringResource(R.string.ax_dynamic_bar_media_output),
                        tint = onCard,
                        modifier = Modifier.size(sideIcon),
                    )
                }
            }
        }
        return
    }

    val onAccent = chipContentColorOn(accent)
    val tonalBg = accent.copy(alpha = AlphaSubtle)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaCustomActionButton(event, interactor, accent, tonalBg)

        Surface(
            onClick = { interactor.skipPrev() },
            shape = CircleShape,
            color = tonalBg,
            modifier = Modifier.size(ControlButtonSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.SkipPrevious, null,
                    tint = accent,
                    modifier = Modifier.size(ControlIconSize),
                )
            }
        }

        Surface(
            onClick = { interactor.togglePlayPause() },
            shape = CircleShape,
            color = accent,
            modifier = Modifier.size(PlayPauseSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (event.isPlaying)
                        stringResource(R.string.ax_dynamic_bar_pause)
                    else
                        stringResource(R.string.ax_dynamic_bar_play),
                    tint = onAccent,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        Surface(
            onClick = { interactor.skipNext() },
            shape = CircleShape,
            color = tonalBg,
            modifier = Modifier.size(ControlButtonSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.SkipNext, null,
                    tint = accent,
                    modifier = Modifier.size(ControlIconSize),
                )
            }
        }

        MediaEndActionButton(event, interactor, accent, tonalBg)
    }
}

@Composable
private fun MediaSquiggleSeekBarView(
    displayFraction: Float,
    isPlaying: Boolean,
    isScrubbing: Boolean,
    accentArgb: Int,
    trackAlphaArgb: Int,
    secondaryProgressArgb: Int,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            SeekBar(context).apply {
                max = 10_000
                splitTrack = false
                setPadding(0, 0, 0, 0)
                isEnabled = false
                thumb = createSeekBarThumb(context, accentArgb)
                thumbOffset = thumb.intrinsicWidth / 2
                val layer = (progressDrawable?.mutate() as? LayerDrawable)
                if (layer != null) {
                    layer.findDrawableByLayerId(android.R.id.background)
                        ?.mutate()?.setTint(trackAlphaArgb)
                    layer.findDrawableByLayerId(android.R.id.secondaryProgress)
                        ?.mutate()?.setTint(secondaryProgressArgb)
                    val squiggle = SquigglyProgress().apply {
                        waveLength = context.resources.getDimensionPixelSize(
                            R.dimen.qs_media_seekbar_progress_wavelength
                        ).toFloat()
                        lineAmplitude = context.resources.getDimensionPixelSize(
                            R.dimen.qs_media_seekbar_progress_amplitude
                        ).toFloat()
                        phaseSpeed = context.resources.getDimensionPixelSize(
                            R.dimen.qs_media_seekbar_progress_phase
                        ).toFloat()
                        strokeWidth = context.resources.getDimensionPixelSize(
                            R.dimen.qs_media_seekbar_progress_stroke_width
                        ).toFloat()
                        setTint(accentArgb)
                        transitionEnabled = false
                        animate = false
                    }
                    layer.setDrawableByLayerId(android.R.id.progress, squiggle)
                    progressDrawable = layer
                }
            }
        },
        update = { bar ->
            val target = (displayFraction * 10_000f).toInt().coerceIn(0, 10_000)
            bar.progress = target
            (bar.thumb as? GradientDrawable)?.setColor(accentArgb)
            val alpha = if (isPlaying) 255 else (255 * 0.55f).toInt()
            bar.thumb?.alpha = alpha
            val layer = bar.progressDrawable as? LayerDrawable
            layer?.findDrawableByLayerId(android.R.id.background)?.setTint(trackAlphaArgb)
            layer?.findDrawableByLayerId(android.R.id.secondaryProgress)
                ?.setTint(secondaryProgressArgb)
            val squiggle = layer?.findDrawableByLayerId(android.R.id.progress) as? SquigglyProgress
            squiggle?.apply {
                setTint(accentArgb)
                setAlpha(alpha)
                animate = isPlaying && !isScrubbing
            }
            layer?.alpha = alpha
        },
        modifier = modifier.fillMaxWidth().height(SeekBarHeight),
    )
}

@Composable
private fun MediaSeekBar(
    event: IslandEvent.Media,
    interactor: IslandActions,
    accent: Color,
    cinematic: Boolean = false,
) {
    val mediaProgress = rememberMediaProgress(event)
    val isPlaying = event.isPlaying
    val durationMs = event.duration
    val positionMs = mediaProgress.positionMs
    val serverFraction = mediaProgress.progress

    var isScrubbing by remember { mutableStateOf(false) }
    var displayFraction by remember { mutableStateOf(serverFraction) }
    var seekPointerDown by remember { mutableStateOf(false) }
    var cinematicOverscrollPx by remember { mutableStateOf(0f) }

    val cinematicOverscrollAnimatedPx by animateFloatAsState(
        targetValue = cinematicOverscrollPx,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "accordSeekEdgeOverscroll",
    )

    val cinematicSeekEmphasized = cinematic && (seekPointerDown || isScrubbing)
    val cinematicTimeScale by animateFloatAsState(
        targetValue =
            if (cinematicSeekEmphasized) CinematicSeekTimeScaleFactor else 1f,
        animationSpec = AccordSeekEmphasizeSpringFloat,
        label = "accordSeekTimeScale",
    )

    val interactorRef = rememberUpdatedState(interactor)

    // Read the dismiss swipe lock provided by MagneticSwipeToDismiss
    val swipeLock = LocalDismissSwipeLock.current

    // Smooth frame-interpolated progress when playing, snaps when paused or scrubbing
    LaunchedEffect(positionMs, durationMs, isPlaying) {
        if (isScrubbing) return@LaunchedEffect

        displayFraction = serverFraction

        if (!isPlaying || durationMs <= 0L) return@LaunchedEffect

        val startWallMs = System.currentTimeMillis()
        val startProgressMs = positionMs
        while (true) {
            delay(16L) // ~60 fps
            if (isScrubbing) break
            val elapsed = System.currentTimeMillis() - startWallMs
            val interpolated = ((startProgressMs + elapsed).toFloat() / durationMs).coerceIn(0f, 1f)
            displayFraction = interpolated
            if (interpolated >= 1f) break
        }
    }

    val displayMs = (displayFraction * durationMs).toLong()
    val whiteArgb = android.graphics.Color.WHITE
    val accentArgb = if (cinematic) whiteArgb else accent.toArgb()
    val trackAlphaArgb =
        if (cinematic) {
            com.android.internal.graphics.ColorUtils.setAlphaComponent(whiteArgb, 90)
        } else {
            accent.copy(alpha = AlphaSubtle).toArgb()
        }
    val secondaryProgressArgb =
        if (cinematic) {
            com.android.internal.graphics.ColorUtils.setAlphaComponent(whiteArgb, 60)
        } else {
            com.android.internal.graphics.ColorUtils.setAlphaComponent(accent.toArgb(), 60)
        }

    val labelColor = if (cinematic) Color.White.copy(alpha = 0.55f) else SubtleGray
    val timeStyle =
        if (cinematic) {
            TextStyle(
                color = labelColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            )
        } else {
            MaterialTheme.typography.labelSmall.copy(color = labelColor)
        }

    fun Modifier.mediaSeekBarGestures(): Modifier =
        this.pointerInput(swipeLock) {
                awaitEachGesture {
                    awaitPointerEvent() // DOWN
                    swipeLock.value = true
                    try {
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                    } finally {
                        swipeLock.value = false
                    }
                }
            }
            .pointerInput("tap") {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    displayFraction = fraction
                    interactorRef.value.seekTo((fraction * durationMs).toLong())
                }
            }
            .pointerInput("drag") {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isScrubbing = true
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val rawFraction = offset.x / width
                        displayFraction = rawFraction.coerceIn(0f, 1f)
                        cinematicOverscrollPx =
                            if (cinematic) {
                                val overshootFraction =
                                    when {
                                        rawFraction < 0f -> rawFraction
                                        rawFraction > 1f -> rawFraction - 1f
                                        else -> 0f
                                    }
                                // Match Accord edge-pull feel: damp motion at 0%/100%.
                                (overshootFraction * width * 0.18f).coerceIn(-28f, 28f)
                            } else {
                                0f
                            }
                    },
                    onDragEnd = {
                        interactorRef.value.seekTo((displayFraction * durationMs).toLong())
                        isScrubbing = false
                        cinematicOverscrollPx = 0f
                    },
                    onDragCancel = {
                        isScrubbing = false
                        cinematicOverscrollPx = 0f
                    },
                    onHorizontalDrag = { change, _ ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val rawFraction = change.position.x / width
                        displayFraction = rawFraction.coerceIn(0f, 1f)
                        cinematicOverscrollPx =
                            if (cinematic) {
                                val overshootFraction =
                                    when {
                                        rawFraction < 0f -> rawFraction
                                        rawFraction > 1f -> rawFraction - 1f
                                        else -> 0f
                                    }
                                (overshootFraction * width * 0.18f).coerceIn(-28f, 28f)
                            } else {
                                0f
                            }
                        change.consume()
                    },
                )
            }

    val timeRow: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = if (cinematic) 4.dp else 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatElapsedTime(displayMs),
                color = labelColor,
                style = timeStyle,
            )
            Text(
                text = formatElapsedTime(durationMs),
                color = labelColor,
                style = timeStyle,
            )
        }
    }

    fun Modifier.seekAreaBase(): Modifier =
        this.fillMaxWidth()
            .height(if (cinematic) CinematicSeekGestureHeightDp else SeekBarHeight)

    val seekBox: @Composable () -> Unit = {
        Box(
            modifier = Modifier.seekAreaBase().mediaSeekBarGestures(),
            contentAlignment = Alignment.Center,
        ) {
            MediaSquiggleSeekBarView(
                displayFraction = displayFraction,
                isPlaying = isPlaying,
                isScrubbing = isScrubbing,
                accentArgb = accentArgb,
                trackAlphaArgb = trackAlphaArgb,
                secondaryProgressArgb = secondaryProgressArgb,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(SpaceXs)) {
        if (cinematic) {
            Row(
                modifier =
                    Modifier.fillMaxWidth().graphicsLayer {
                        translationX = cinematicOverscrollAnimatedPx
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    modifier =
                        Modifier.width(CinematicTimeSlotWidth).seekAreaBase(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = formatElapsedTime(displayMs),
                        color = labelColor,
                        style = timeStyle,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        modifier =
                            Modifier.graphicsLayer {
                                transformOrigin = TransformOrigin(0f, 0.5f)
                                scaleX = cinematicTimeScale
                                scaleY = cinematicTimeScale
                            },
                    )
                }
                Box(
                    modifier =
                        Modifier.weight(1f, fill = true)
                            .seekAreaBase()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    seekPointerDown = true
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.changes.all { !it.pressed }) break
                                        }
                                    } finally {
                                        seekPointerDown = false
                                    }
                                }
                            }
                            .mediaSeekBarGestures(),
                    contentAlignment = Alignment.Center,
                ) {
                    AccordCinematicLinearSeekVisual(
                        displayFraction = displayFraction,
                        isPlaying = isPlaying,
                        isScrubbing = isScrubbing,
                        seekPointerDown = seekPointerDown,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(
                    modifier =
                        Modifier.width(CinematicTimeSlotWidth).seekAreaBase(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        text = formatElapsedTime(durationMs),
                        color = labelColor,
                        style = timeStyle,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier =
                            Modifier.graphicsLayer {
                                transformOrigin = TransformOrigin(1f, 0.5f)
                                scaleX = cinematicTimeScale
                                scaleY = cinematicTimeScale
                            },
                    )
                }
            }
        } else {
            timeRow()
            seekBox()
        }
    }
}

/**
 * Creates a pill-shaped thumb drawable for the seekbar.
 */
private fun createSeekBarThumb(context: android.content.Context, tintColor: Int): GradientDrawable {
    val wPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 4f, context.resources.displayMetrics
    ).toInt().coerceAtLeast(1)
    val hPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
    ).toInt().coerceAtLeast(1)
    val radiusPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
    )
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setSize(wPx, hPx)
        cornerRadius = radiusPx
        setColor(tintColor)
    }
}

@Composable
private fun MediaCustomActionButton(
    event: IslandEvent.Media,
    interactor: IslandActions,
    accent: Color,
    tonalBg: Color,
) {
    if (event.customActions.isNotEmpty()) {
        val ca = event.customActions.first()
        Surface(
            onClick = { ca.perform(interactor) },
            shape = CircleShape,
            color = tonalBg,
            modifier = Modifier.size(ControlButtonSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CustomActionIcon(ca, tint = accent, modifier = Modifier.size(ControlIconSize))
            }
        }
    } else {
        Surface(
            onClick = { },
            shape = CircleShape,
            color = tonalBg.copy(alpha = AlphaSubtle),
            modifier = Modifier.size(ControlButtonSize),
            enabled = false,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    painter = painterResource(R.drawable.ax_accord_ic_nowplaying_shuffle),
                    contentDescription = stringResource(R.string.ax_dynamic_bar_shuffle),
                    tint = accent.copy(alpha = AlphaDisabled),
                    modifier = Modifier.size(ControlIconSize),
                )
            }
        }
    }
}

@Composable
private fun MediaEndActionButton(
    event: IslandEvent.Media,
    interactor: IslandActions,
    accent: Color,
    tonalBg: Color,
) {
    if (event.customActions.size > 1) {
        val ca = event.customActions[1]
        Surface(
            onClick = { ca.perform(interactor) },
            shape = CircleShape,
            color = tonalBg,
            modifier = Modifier.size(ControlButtonSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CustomActionIcon(ca, tint = accent, modifier = Modifier.size(ControlIconSize))
            }
        }
    } else {
        Surface(
            onClick = {
                interactor.openMediaOutputSwitcher()
                interactor.collapseIsland()
            },
            shape = CircleShape,
            color = tonalBg,
            modifier = Modifier.size(ControlButtonSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.VolumeUp, null,
                    tint = accent,
                    modifier = Modifier.size(ControlIconSize),
                )
            }
        }
    }
}

@Composable
internal fun RowScope.CompactMediaRow(
    event: IslandEvent.Media,
    interactor: IslandActions,
) {
    event.albumArt?.let {
        Image(
            bitmap = it.toScaledBitmap(SizeCompactIcon),
            null,
            modifier = Modifier.size(SizeCompactIcon).clip(ShapeCompact),
            contentScale = ContentScale.Crop,
        )
    } ?: run {
        val accent = rememberMediaColors(event).accent
        Box(
            modifier = Modifier.size(SizeCompactIcon)
                .clip(ShapeCompact)
                .background(accent.copy(alpha = AlphaIconBg)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MusicNote, null, tint = accent, modifier = Modifier.size(20.dp))
        }
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(
            event.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_music) },
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (event.artist.isNotEmpty())
            Text(
                event.artist,
                color = SubtleGray,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
    }
    Spacer(Modifier.width(SpaceMd))
    Surface(
        onClick = { interactor.togglePlayPause() },
        shape = CircleShape,
        color = ActionBg,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                null,
                tint = OnActionText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun IslandEvent.MediaCustomAction.perform(interactor: IslandActions) {
    onClick?.invoke() ?: interactor.sendCustomAction(action)
}
