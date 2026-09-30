package app.xvid

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs phone downloads one after another in the foreground, with a progress
 * notification, then posts a Saved or failed notification for each.
 */
class DownloadService : Service() {
    private lateinit var executor: ExecutorService
    private lateinit var notifications: NotificationManager
    private val mainThread = Handler(Looper.getMainLooper())

    // Only touched on the main thread.
    private var pending = 0
    private var lastStartId = 0

    @Volatile
    private var progressNotification: Notification? = null

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
        notifications = getSystemService(NotificationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        lastStartId = startId
        val text = intent?.getStringExtra(EXTRA_TEXT)
        if (text == null) {
            if (pending == 0) stopWhenIdle()
            return START_NOT_STICKY
        }
        pending++
        executor.execute { run(text) }
        return START_NOT_STICKY
    }

    private fun run(text: String) {
        var lastPercent: Int? = -1
        var lastUpdate = 0L
        showProgress(null)
        val outcome = (application as XVidApp).retryingDownloads.download(text) { percent ->
            val now = SystemClock.elapsedRealtime()
            if (percent != lastPercent && now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                lastPercent = percent
                lastUpdate = now
                showProgress(percent)
            }
        }
        notifications.notify(nextResultId.getAndIncrement(), Notifications.result(this, outcome))
        if ((application as XVidApp).retryingDownloads.hasWaiting()) BackgroundWork.retryWhenOnline(this)
        (application as XVidApp).updateCheck?.releaseToNotify()?.let {
            notifications.notify(UPDATE_ID, Notifications.update(this, it))
        }
        mainThread.post { if (--pending == 0) stopWhenIdle() }
    }

    private fun showProgress(percent: Int?) {
        val notification = Notifications.progress(this, percent)
        progressNotification = notification
        notifications.notify(PROGRESS_ID, notification)
    }

    /** Keeps the current progress notification when a new download is added. */
    private fun goForeground() {
        val notification = progressNotification ?: Notifications.progress(this, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_ID, notification)
        }
    }

    private fun stopWhenIdle() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val EXTRA_TEXT = "text"
        private const val PROGRESS_ID = 1
        private const val UPDATE_ID = 2
        private const val PROGRESS_INTERVAL_MS = 500L
        private val nextResultId = AtomicInteger(1000)

        /** Starts a phone download of the X post in [sharedText]. */
        fun start(context: Context, sharedText: String) {
            context.startForegroundService(Intent(context, DownloadService::class.java).putExtra(EXTRA_TEXT, sharedText))
        }
    }
}
