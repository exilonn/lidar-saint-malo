package com.exilon.tides

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.exilon.tides.di.ServiceLocator
import com.exilon.tides.widget.TideWidget
import com.exilon.tides.widget.WidgetTickScheduler
import com.exilon.tides.work.RefreshScheduler
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TideApp : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        serviceLocator = ServiceLocator(this)
        // Keep the ~3x/day background refresh registered (no-op if already scheduled).
        RefreshScheduler.ensurePeriodicRefresh(this)
        // Tighter, network-free widget re-render so the render-time tide height/marker/arrow
        // (computed live from cached extremes) don't go stale between data refreshes.
        WidgetTickScheduler.ensureScheduled(this)
        registerScreenOnWidgetRefresh()
    }

    /**
     * Manifest-declared receivers can't listen for ACTION_SCREEN_ON (it's not a boot-time intent),
     * so this is registered dynamically while the process is alive — a best-effort top-up on top
     * of the alarm-driven tick, since the screen turning on is exactly when a stale value is most
     * visible on the home screen.
     */
    private fun registerScreenOnWidgetRefresh() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                CoroutineScope(Dispatchers.Default).launch { TideWidget().updateAll(this@TideApp) }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    companion object {
        fun from(context: Context): TideApp = context.applicationContext as TideApp
    }
}
