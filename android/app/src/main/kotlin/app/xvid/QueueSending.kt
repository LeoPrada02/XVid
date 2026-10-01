package app.xvid

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

/**
 * Sending the queue in the background, without opening the app: whenever the phone joins a Wi-Fi
 * network (Android wakes [WifiJoinedReceiver]), and every so often on Wi-Fi in case that was missed
 * (a restart or an app update makes Android forget the Wi-Fi watch). Only while links are waiting.
 */
object QueueSending {
    private const val NOW = "send-queue"
    private const val LATER = "send-queue-later"
    private const val PERIODIC = "send-queue-periodic"

    /** Whether this run of the app has asked Android to watch for Wi-Fi (Android keeps it until a restart). */
    @Volatile
    private var watching = false

    /** Watches for Wi-Fi while the queue has links, and stops once it's empty. */
    @Synchronized
    fun update(context: Context) {
        val app = context.applicationContext as XVidApp
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val workManager = WorkManager.getInstance(context)
        if (app.toPc.queue().isEmpty()) {
            runCatching { connectivity.unregisterNetworkCallback(wifiJoined(context)) } // throws when not watching
            watching = false
            workManager.cancelUniqueWork(PERIODIC)
            return
        }
        if (!watching) {
            // Only once per run: each registration makes Android report the current Wi-Fi straight away.
            runCatching { connectivity.unregisterNetworkCallback(wifiJoined(context)) } // left from an earlier run
            val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            connectivity.registerNetworkCallback(wifi, wifiJoined(context))
            watching = true
        }
        val periodic = PeriodicWorkRequest.Builder(SendQueueWorker::class.java, 15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    /**
     * Sends the queue as soon as there's a connection, and once more a couple of minutes later: a PC
     * may need a moment after the Wi-Fi is up (e.g. a laptop waking). Already waiting: nothing changes.
     */
    fun sendSoon(context: Context) {
        val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val workManager = WorkManager.getInstance(context)
        val now = OneTimeWorkRequest.Builder(SendQueueWorker::class.java).setConstraints(connected).build()
        workManager.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, now)
        val later = OneTimeWorkRequest.Builder(SendQueueWorker::class.java)
            .setConstraints(connected)
            .setInitialDelay(2, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(LATER, ExistingWorkPolicy.KEEP, later)
    }

    private fun wifiJoined(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WifiJoinedReceiver::class.java),
        // Mutable: Android adds the network to it.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}

/** The phone joined a Wi-Fi network: maybe the home one, so try the queue. */
class WifiJoinedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = QueueSending.sendSoon(context)
}

/** Sends the queued links whose PC is reachable, and says which ones went. */
class SendQueueWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext as XVidApp
        val sent = app.toPc.sendQueue()
        if (sent.isNotEmpty()) {
            applicationContext.getSystemService(NotificationManager::class.java)
                .notify(Notifications.QUEUE_SENT_ID, Notifications.queueSent(applicationContext, sent))
        }
        if (app.toPc.queue().isEmpty()) QueueSending.update(applicationContext)
        return Result.success()
    }
}
