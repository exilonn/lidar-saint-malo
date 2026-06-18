package com.exilon.tides.ui.screen.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

/**
 * Frosted-glass effect plumbing. The hero scene (sky + water) is recorded once into a shared
 * [GraphicsLayer] via [frostSource]; each [FrostedCard] then redraws the slice of that layer
 * sitting behind it with a blur RenderEffect, producing a true backdrop blur on Android 12+.
 * Below API 31 (no RenderEffect) cards fall back to a more opaque tint — still legible, just not
 * blurred.
 */
class FrostState(val layer: GraphicsLayer) {
    /** Where the captured scene's top-left sits in root coordinates (for aligning the blur crop). */
    var sceneOriginInRoot by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberFrostState(): FrostState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { FrostState(layer) }
}

private val SupportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Marks the composable (the hero scene) whose pixels become the frosted backdrop. A no-op below
 * API 31, where there's no RenderEffect to blur with (cards fall back to a plain tint).
 */
fun Modifier.frostSource(state: FrostState): Modifier =
    if (!SupportsBlur) {
        this
    } else {
        this
            .onGloballyPositioned { state.sceneOriginInRoot = it.positionInRoot() }
            .drawWithContent {
                state.layer.record { this@drawWithContent.drawContent() }
                drawContent()
            }
    }

/**
 * A translucent Material 3 panel that floats over the hero scene. Pass [state] (from
 * [rememberFrostState], with the scene tagged by [frostSource]) to get a real backdrop blur on
 * Android 12+; omit it for a plain semi-opaque card.
 */
@Composable
fun FrostedCard(
    modifier: Modifier = Modifier,
    state: FrostState? = null,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable () -> Unit,
) {
    val blur = SupportsBlur && state != null
    var originInRoot by remember { mutableStateOf(Offset.Zero) }
    val scheme = MaterialTheme.colorScheme
    val tint = if (blur) scheme.surface.copy(alpha = 0.32f) else scheme.surface.copy(alpha = 0.62f)
    // Subtle glass edge highlight: brighter along the top, fading down.
    val edge = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f)),
    )

    Box(
        modifier
            .clip(shape)
            .then(
                if (blur) {
                    Modifier
                        .onGloballyPositioned { originInRoot = it.positionInRoot() }
                        .drawBehind {
                            val layer = state!!.layer
                            layer.renderEffect = BlurEffect(28f, 28f, TileMode.Clamp)
                            val dx = state.sceneOriginInRoot.x - originInRoot.x
                            val dy = state.sceneOriginInRoot.y - originInRoot.y
                            translate(left = dx, top = dy) { drawLayer(layer) }
                        }
                } else {
                    Modifier
                },
            )
            .background(tint)
            .border(width = 1.dp, brush = edge, shape = shape),
    ) {
        // A Box (unlike Surface) doesn't set a content colour, so text would fall back to black.
        // Provide the themed on-surface colour so children read correctly over the glass.
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            content()
        }
    }
}
