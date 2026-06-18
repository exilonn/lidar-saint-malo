package com.exilon.tides.ui.screen.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom rising/falling arrows — a bold, geometric, rounded-stroke chevron-on-shaft (Unbounded
 * look), NOT a font glyph. Stroked black so an [androidx.compose.material3.Icon] `tint` recolours
 * the whole arrow to the rising/falling accent.
 */
private fun tideArrow(name: String, up: Boolean): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.75f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            if (up) {
                moveTo(12f, 19.5f)
                lineTo(12f, 5.5f)
                moveTo(5.75f, 11.75f)
                lineTo(12f, 5.5f)
                lineTo(18.25f, 11.75f)
            } else {
                moveTo(12f, 4.5f)
                lineTo(12f, 18.5f)
                moveTo(5.75f, 12.25f)
                lineTo(12f, 18.5f)
                lineTo(18.25f, 12.25f)
            }
        }
    }.build()

val TideArrowUp: ImageVector = tideArrow("TideArrowUp", up = true)
val TideArrowDown: ImageVector = tideArrow("TideArrowDown", up = false)
