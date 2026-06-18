package com.exilon.tides.ui.screen.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A static, north-up arrow pointing in the geographic direction the wind blows FROM (the standard
 * meteorological convention), rotated by [directionDeg] — never the device's own orientation/
 * magnetometer, so it stays correct without any sensor.
 */
@Composable
fun WindArrow(
    directionDeg: Double?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        imageVector = Icons.Filled.Navigation,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(size)
            .rotate(directionDeg?.toFloat() ?: 0f),
    )
}
