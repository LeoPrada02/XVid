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
import androidx.annotation.StringRes
import app.xvid.core.Pc
import app.xvid.core.PcException
import app.xvid.core.PcVideo
import app.xvid.core.PhoneDownloadOutcome
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs phone downloads and Save to phone one after another in the foreground, with a
 * progress notification, then posts a Saved or failed notification for each. After an
 * X login it also retries the downloads that needed one.
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
        val work: (() -> Unit)? = when {
            intent?.action == ACTION_SAVE_FROM_PC -> { -> saveFromPc(intent) }
            text != null || intent?.action == ACTION_RETRY_AFTER_LOGIN -> { -> run(text) }
            else -> null
        }
        if (work == null) {
            if (pending == 0) stopWhenIdle()
            return START_NOT_STICKY
        }
        pending++
        executor.execute {
            try {
                work()
            } finally {
                mainThread.post { if (--pending == 0) stopWhenIdle() }
            }
        }
        return START_NOT_STICKY
    }

    /** Downloads the post in [text], or with none retries the downloads that needed an X login. */
    private fun run(text: String?) {
        val app = application as XVidApp
        val downloads = app.retryingDownloads
        val onProgress = progress(R.string.notif_downloading)
        if (text != null) showResult(downloads.download(text, onProgress)) else downloads.retryAfterLogin(onProgress, ::showResult)
        if (downloads.hasWaiting()) BackgroundWork.retryWhenOnline(this)
        app.updateCheck?.releaseToNotify()?.let {
            notifications.notify(UPDATE_ID, Notifications.update(this, it))
        }
    }

    /** Save to phone: copies the PC library video in [intent] into the phone library. */
    private fun saveFromPc(intent: Intent) {
        val app = application as XVidApp
        val onProgress = progress(R.string.notif_saving)
        val pc = intent.pc(app)
        val video = intent.pcVideo()
        val outcome = if (pc == null || video == null) {
            PhoneDownloadOutcome.Failed(getString(R.string.pc_gone))
        } else {
            try {
                PhoneDownloadOutcome.Saved(listOf(app.pcLibraries.saveToPhone(pc, video, onProgress)))
            } catch (e: PcException) {
                PhoneDownloadOutcome.Failed(e.message.orEmpty())
            }
        }
        showResult(outcome, failedTitle = R.string.notif_save_failed)
    }

    private fun showResult(outcome: PhoneDownloadOutcome) = showResult(outcome, R.string.notif_failed)

    private fun showResult(outcome: PhoneDownloadOutcome, @StringRes failedTitle: Int) {
        notifications.notify(nextResultId.getAndIncrement(), Notifications.result(this, outcome, failedTitle))
    }

    /** Shows the progress notification with [title], then updates it at most every half second. */
    private fun progress(@StringRes title: Int): (Int?) -> Unit {
        var lastPercent: Int? = -1
        var lastUpdate = 0L
        showProgress(null, title)
        return { percent ->
            val now = SystemClock.elapsedRealtime()
            if (percent != lastPercent && now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                lastPercent = percent
                lastUpdate = now
                showProgress(percent, title)
            }
        }
    }

    private fun showProgress(percent: Int?, @StringRes title: Int) {
        val notification = Notifications.progress(this, percent, title)
        progressNotification = notification
        notifications.notify(PROGRESS_ID, notification)
    }

    /** Keeps the current progress notification when a new download is added. */
    private fun goForeground() {
        val notification = progressNotification ?: Notifications.progress(this, null, R.string.notif_downloading)
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
        private const val ACTION_RETRY_AFTER_LOGIN = "app.xvid.RETRY_AFTER_LOGIN"
        private const val ACTION_SAVE_FROM_PC = "app.xvid.SAVE_FROM_PC"
        private const val PROGRESS_ID = 1
        private const val UPDATE_ID = 2
        private const val PROGRESS_INTERVAL_MS = 500L
        private val nextResultId = AtomicInteger(1000)

        /** Starts a phone download of the X post in [sharedText]. */
        fun start(context: Context, sharedText: String) {
            context.startForegroundService(Intent(context, DownloadService::class.java).putExtra(EXTRA_TEXT, sharedText))
        }

        /** Save to phone: copies [video] from [pc]'s PC library into the phone library. */
        fun saveFromPc(context: Context, pc: Pc, video: PcVideo) {
            context.startForegroundService(
                Intent(context, DownloadService::class.java).setAction(ACTION_SAVE_FROM_PC).putPc(pc).putPcVideo(video),
            )
        }

        /** Retries the phone downloads that needed an X login, if any. Called right after logging in. */
        fun retryAfterLogin(context: Context) {
            if (!(context.applicationContext as XVidApp).retryingDownloads.hasWaitingForLogin()) return
            context.startForegroundService(
                Intent(context, DownloadService::class.java).setAction(ACTION_RETRY_AFTER_LOGIN),
            )
        }
    }
}
