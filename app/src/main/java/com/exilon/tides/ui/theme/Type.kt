@file:OptIn(ExperimentalTextApi::class)

package com.exilon.tides.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.exilon.tides.R

// Both are single variable-font files (res/font); each weight is pulled out via FontVariation so a
// bold numeral and a regular body line render from the same .ttf. minSdk 26 → variations apply.
private fun variable(resId: Int, weight: Int) = Font(
    resId = resId,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Display font — big tide numerals, the rising/falling label, section/day headings. */
val SpaceGrotesk = FontFamily(
    variable(R.font.space_grotesk, 400),
    variable(R.font.space_grotesk, 500),
    variable(R.font.space_grotesk, 600),
    variable(R.font.space_grotesk, 700),
)

/** Body font — everything else. */
val Manrope = FontFamily(
    variable(R.font.manrope, 400),
    variable(R.font.manrope, 500),
    variable(R.font.manrope, 600),
    variable(R.font.manrope, 700),
)

// Keep the Material 3 metrics; only swap the families: display/headline/title = Space Grotesk
// (numerals, state label, headings), body/label = Manrope.
val TideTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = SpaceGrotesk),
        displayMedium = displayMedium.copy(fontFamily = SpaceGrotesk),
        displaySmall = displaySmall.copy(fontFamily = SpaceGrotesk),
        headlineLarge = headlineLarge.copy(fontFamily = SpaceGrotesk),
        headlineMedium = headlineMedium.copy(fontFamily = SpaceGrotesk),
        headlineSmall = headlineSmall.copy(fontFamily = SpaceGrotesk),
        titleLarge = titleLarge.copy(fontFamily = SpaceGrotesk),
        titleMedium = titleMedium.copy(fontFamily = SpaceGrotesk),
        titleSmall = titleSmall.copy(fontFamily = SpaceGrotesk),
        bodyLarge = bodyLarge.copy(fontFamily = Manrope),
        bodyMedium = bodyMedium.copy(fontFamily = Manrope),
        bodySmall = bodySmall.copy(fontFamily = Manrope),
        labelLarge = labelLarge.copy(fontFamily = Manrope),
        labelMedium = labelMedium.copy(fontFamily = Manrope),
        labelSmall = labelSmall.copy(fontFamily = Manrope),
    )
}
