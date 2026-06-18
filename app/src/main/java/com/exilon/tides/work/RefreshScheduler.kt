package com.exilon.tides.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules background refreshes. The period is 8h (~3x/day) — deliberately infrequent because
 * tide predictions are deterministic and the TideCheck free tier is only 50 requests/day.
 */
object RefreshScheduler {

    private const val PERIODIC_WORK = "tide-periodic-refresh"
    private const val ONESHOT_WORK = "tide-oneshot-refresh"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Registers the periodic refresh once; keeps any existing schedule on subsequent calls. */
    fun ensurePeriodicRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<TideRefreshWorker>(8, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** One-off refresh (e.g. after a boot or an explicit "refresh now" path). */
    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<TideRefreshWorker>()
            .setConstraints(networkConstraint)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONESHOT_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
