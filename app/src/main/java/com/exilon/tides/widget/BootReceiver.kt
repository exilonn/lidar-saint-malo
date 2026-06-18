package com.exilon.tides.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** AlarmManager alarms don't survive reboot — re-arm the widget tick so it resumes ticking. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WidgetTickScheduler.ensureScheduled(context)
        }
    }
}
