package com.exilon.tides.map

import com.exilon.tides.data.Substrate
import org.maplibre.android.style.expressions.Expression

/**
 * Maps the [Substrate] classes to fill colours and builds the MapLibre data-driven fill-color
 * expression used by the estran layer. Substrate colours are deliberately fixed, natural tones
 * (sand looks like sand in any theme); only the *water* colour of the crossfade is theme-driven
 * (see [TideMapController], which uses the palette's map water colour).
 */
object EstranStyle {

    // ARGB. Sandy yellow / grey gravel / near-black rock / brown mud.
    const val SAND = 0xFFE3C77B.toInt()
    const val GRAVEL = 0xFF9AA0A6.toInt()
    const val ROCK = 0xFF3A3D42.toInt()
    const val MUD = 0xFF8A6A4A.toInt()

    /** `fill-color` keyed by each feature's `substrate` property; defaults to sand. */
    fun substrateFillColor(): Expression = Expression.match(
        Expression.get("substrate"),
        Expression.literal(Substrate.SAND.name), Expression.color(SAND),
        Expression.literal(Substrate.GRAVEL.name), Expression.color(GRAVEL),
        Expression.literal(Substrate.ROCK.name), Expression.color(ROCK),
        Expression.literal(Substrate.MUD.name), Expression.color(MUD),
        Expression.color(SAND), // default
    )
}
