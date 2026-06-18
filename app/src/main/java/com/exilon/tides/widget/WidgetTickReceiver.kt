package com.exilon.tides.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires every [WidgetTickScheduler] interval to re-render the widget so render-time-computed
 * values (tide height, "now" marker, rising/falling arrow) advance with the clock. Purely a local
 * recompute from already-cached Room data — never touches the network. Re-schedules itself since
 * AlarmManager alarms are one-shot.
 */
class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                TideWidget().updateAll(appContext)
            } finally {
                WidgetTickScheduler.scheduleNext(appContext)
                pending.finish()
            }
        }
    }
}
