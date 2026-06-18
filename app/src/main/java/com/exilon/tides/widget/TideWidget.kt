package com.exilon.tides.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.exilon.tides.TideApp
import com.exilon.tides.data.toDomain
import com.exilon.tides.data.model.TideExtreme
import com.exilon.tides.data.model.TideMath
import com.exilon.tides.data.model.TideType
import com.exilon.tides.ui.LocaleManager
import com.exilon.tides.ui.TideFormat
import com.exilon.tides.ui.UserPrefs
import com.exilon.tides.ui.theme.Palettes
import com.exilon.tides.ui.theme.ThemeId
import java.util.Locale

/**
 * Home-screen widget. Reads the **cached Room data only** — never the network. The mini curve,
 * height/arrow and background are pre-rendered to Bitmaps in [WidgetGraphics] (Glance can't draw
 * Canvas) using the **active palette**, so the widget and app share one design language. Responsive
 * sizing shows more (following tide, then the curve) as the widget grows.
 */
class TideWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(140.dp, 110.dp), // small  → height + arrow + next tide
            DpSize(220.dp, 150.dp), // medium → + following tide
            DpSize(250.dp, 220.dp), // large  → + mini curve
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = TideApp.from(context).serviceLocator.dao
        val appState = dao.appStateSnapshot()
        val palette = Palettes.byId(ThemeId.from(appState?.themeId))
        val station = dao.selectedStationSnapshot()
        val extremes = dao.selectedExtremesSnapshot().map { it.toDomain() }
        val now = System.currentTimeMillis()
        val useMetric = UserPrefs.useMetric(context)
        val use24h = UserPrefs.use24h(context)

        // Apply the app's selected language so widget strings render in the same locale.
        val localCtx: Context = LocaleManager.persistedTag(context)?.let { tag ->
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            context.createConfigurationContext(config)
        } ?: context

        val bg = WidgetGraphics.bgBitmap(palette.widgetBgTop.toArgb(), palette.widgetBgBottom.toArgb())

        val currentHeight = if (station != null && extremes.isNotEmpty()) {
            TideMath.heightAt(extremes, now)
        } else {
            null
        }

        val model: WidgetModel?
        val headerBitmap: Bitmap?
        val curveBitmap: Bitmap?
        val accent: Color
        if (station != null && currentHeight != null) {
            val isRising = TideMath.isRising(extremes, now) ?: false
            accent = palette.tideAccent(isRising)
            val upcoming = listOfNotNull(
                TideMath.nextExtreme(extremes, now, TideType.HIGH),
                TideMath.nextExtreme(extremes, now, TideType.LOW),
            ).sortedBy { it.timeMillis } // soonest first
            model = WidgetModel(station.placeName, currentHeight, isRising, upcoming)
            headerBitmap = WidgetGraphics.headerBitmap(
                context, TideFormat.height(currentHeight, useMetric = useMetric), isRising,
                accent.toArgb(), palette.widgetText.toArgb(),
            )
            curveBitmap = WidgetGraphics.curveBitmap(
                extremes, now, accent.toArgb(), palette.widgetText.toArgb(),
            )
        } else {
            model = null
            headerBitmap = null
            curveBitmap = null
            accent = palette.rising
        }

        provideContent {
            TideWidgetContent(
                model = model,
                nowMillis = now,
                bgBitmap = bg,
                headerBitmap = headerBitmap,
                curveBitmap = curveBitmap,
                textColor = palette.widgetText,
                secondaryColor = palette.widgetSecondary,
                accentColor = accent,
                use24h = use24h,
                useMetric = useMetric,
                localCtx = localCtx,
            )
        }
    }
}

/** Minimal projection the widget needs; computed once in provideGlance from Room. */
data class WidgetModel(
    val placeName: String,
    val currentHeightMeters: Double,
    val isRising: Boolean,
    val upcoming: List<TideExtreme>, // next high & low, soonest first
)
