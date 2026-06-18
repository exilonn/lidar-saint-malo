package com.exilon.tides.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Drives a tighter widget re-render cadence than Android's widget-provider auto-update allows
 * (capped at 30 min, and this app keeps that at 0/disabled anyway). The tide height, "now" marker
 * and rising/falling arrow are computed at *render* time from the cached extremes ([TideWidget]
 * already does this via [com.exilon.tides.data.model.TideMath]), so they only go stale because
 * nothing asks Glance to re-render — this alarm does exactly that, and nothing else: no network,
 * no Room write, just `updateAll`. AlarmManager (not WorkManager) because WorkManager's periodic
 * minimum is 15 minutes; each tick reschedules the next one itself since alarms are one-shot.
 */
object WidgetTickScheduler {

    private const val TICK_INTERVAL_MS = 10 * 60 * 1000L
    private const val REQUEST_CODE = 7821

    /** Call on app start and after boot — schedules the first tick (each tick reschedules itself). */
    fun ensureScheduled(context: Context) = scheduleNext(context)

    fun scheduleNext(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + TICK_INTERVAL_MS
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetTickReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
