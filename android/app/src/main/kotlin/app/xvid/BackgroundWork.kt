package app.xvid

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Scheduled background work: retrying phone downloads once online, and the weekly yt-dlp check. */
object BackgroundWork {
    private const val RETRY_DOWNLOADS = "retry-downloads"
    private const val YTDLP_CHECK = "ytdlp-weekly-check"

    /** Retries the phone downloads waiting for the connection as soon as the phone has one. */
    fun retryWhenOnline(context: Context) {
        val request = OneTimeWorkRequest.Builder(RetryDownloadsWorker::class.java)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY_DOWNLOADS, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * A quiet check for a newer yt-dlp, on Wi-Fi (or another unmetered network) only.
     * The core's rule makes sure it never runs more than once a week.
     */
    fun scheduleWeeklyYtDlpCheck(context: Context) {
        val request = PeriodicWorkRequest.Builder(YtDlpCheckWorker::class.java, 7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(YTDLP_CHECK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Retries the phone downloads that failed for lack of a connection, and notifies how each one ended. */
class RetryDownloadsWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val notifications = applicationContext.getSystemService(NotificationManager::class.java)
        var lastPercent: Int? = -1
        var lastUpdate = 0L
        val stillWaiting = try {
            (applicationContext as XVidApp).retryingDownloads.retryWaiting(
                onProgress = { percent ->
                    val now = SystemClock.elapsedRealtime()
                    if (percent != lastPercent && now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                        lastPercent = percent
                        lastUpdate = now
                        notifications.notify(PROGRESS_ID, Notifications.progress(applicationContext, percent))
                    }
                },
                onResult = { outcome ->
                    notifications.notify(nextResultId.getAndIncrement(), Notifications.result(applicationContext, outcome))
                },
            )
        } finally {
            notifications.cancel(PROGRESS_ID)
        }
        return if (stillWaiting) Result.retry() else Result.success()
    }

    private companion object {
        const val PROGRESS_ID = 3
        const val PROGRESS_INTERVAL_MS = 500L
        val nextResultId = AtomicInteger(100_000)
    }
}

/** The weekly background check for a newer yt-dlp. */
class YtDlpCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        (applicationContext as XVidApp).ytDlpUpdates.weeklyCheck()
        return Result.success()
    }
}
