package com.exilon.tides.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.exilon.tides.R
import com.exilon.tides.data.model.TideExtreme
import com.exilon.tides.data.model.TideType
import com.exilon.tides.ui.MainActivity
import com.exilon.tides.ui.TideFormat

/**
 * Glance UI. Tapping anywhere opens the app. Height/arrow, curve and background are pre-rendered
 * Bitmaps ([WidgetGraphics]); everything else is themed Glance text. Responsive: small = height +
 * arrow + next tide; medium = + following tide; large = + mini curve.
 */
@Composable
fun TideWidgetContent(
    model: WidgetModel?,
    nowMillis: Long,
    bgBitmap: Bitmap?,
    headerBitmap: Bitmap?,
    curveBitmap: Bitmap?,
    textColor: Color,
    secondaryColor: Color,
    accentColor: Color,
    use24h: Boolean = true,
    useMetric: Boolean = true,
    localCtx: Context = LocalContext.current,
) {
    val context = localCtx
    val text = ColorProvider(textColor)
    val secondary = ColorProvider(secondaryColor)
    val accent = ColorProvider(accentColor)

    var container = GlanceModifier
        .fillMaxSize()
        .cornerRadius(20.dp)
        .padding(16.dp)
        .clickable(actionStartActivity<MainActivity>())
    if (bgBitmap != null) {
        container = container.background(ImageProvider(bgBitmap), contentScale = ContentScale.FillBounds)
    }

    if (model == null) {
        Column(
            modifier = container,
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(context.getString(R.string.tap_to_setup), style = TextStyle(color = text, fontSize = 15.sp))
        }
        return
    }

    val size = LocalSize.current
    val showSecond = size.height >= 132.dp
    val showCurve = size.height >= 190.dp

    Column(modifier = container) {
        if (showSecond) {
            Text(model.placeName, style = TextStyle(color = text, fontSize = 13.sp), maxLines = 1)
            Spacer(GlanceModifier.height(4.dp))
        }
        if (headerBitmap != null) {
            Image(
                provider = ImageProvider(headerBitmap),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxWidth().height(50.dp),
                contentScale = ContentScale.Fit,
            )
        }
        if (showSecond) {
            Text(
                text = context.getString(if (model.isRising) R.string.rising else R.string.falling),
                style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
        }
        if (showCurve && curveBitmap != null) {
            Spacer(GlanceModifier.height(8.dp))
            Image(
                provider = ImageProvider(curveBitmap),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxWidth().height(56.dp),
                contentScale = ContentScale.FillBounds,
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            val shown = if (showSecond) model.upcoming.take(2) else model.upcoming.take(1)
            shown.forEach { extreme ->
                NextColumn(extreme, nowMillis, text, secondary, use24h, context, GlanceModifier.defaultWeight())
            }
        }
    }
}

@Composable
private fun NextColumn(
    extreme: TideExtreme,
    nowMillis: Long,
    text: ColorProvider,
    secondary: ColorProvider,
    use24h: Boolean,
    ctx: Context,
    modifier: GlanceModifier,
) {
    val context = ctx
    val label = context.getString(if (extreme.type == TideType.HIGH) R.string.high else R.string.low)
    Column(modifier = modifier) {
        Text(label, style = TextStyle(color = secondary, fontSize = 12.sp))
        Text(
            TideFormat.time(extreme.timeMillis, use24h = use24h),
            style = TextStyle(color = text, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )
        Text(
            context.getString(R.string.in_time, TideFormat.countdown(context, nowMillis, extreme.timeMillis)),
            style = TextStyle(color = secondary, fontSize = 11.sp),
        )
    }
}
