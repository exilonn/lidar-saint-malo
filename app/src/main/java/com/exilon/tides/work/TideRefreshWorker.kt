package com.exilon.tides.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import com.exilon.tides.TideApp
import com.exilon.tides.data.RefreshResult
import com.exilon.tides.widget.TideWidget

/**
 * Background refresh: fetches the latest forecast into Room, then nudges the widget to re-read it.
 * This is the only background network path; the widget itself stays offline.
 */
class TideRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = TideApp.from(applicationContext).serviceLocator.repository
        return when (val result = repository.refresh()) {
            RefreshResult.Success -> {
                TideWidget().updateAll(applicationContext)
                Result.success()
            }
            // No permission yet: nothing to retry until the user grants it in-app.
            RefreshResult.PermissionDenied -> Result.success()
            // No coastal station near the user: nothing to retry in the background.
            is RefreshResult.NoStationNearby -> Result.success()
            // Backing off after a 429: don't retry now, the in-memory cooldown will lift itself.
            RefreshResult.RateLimited -> Result.success()
            // Transient: back off and try again, but give up after a few attempts.
            RefreshResult.LocationUnavailable ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            is RefreshResult.Error ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
