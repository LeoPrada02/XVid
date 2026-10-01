package app.xvid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import app.xvid.core.Pc
import app.xvid.core.PhoneDownloadOutcome
import app.xvid.core.SentLink
import app.xvid.core.Update

/** The notifications a phone download or Save to phone shows: progress, then Saved, failed or needs an X login. */
object Notifications {
    private const val CHANNEL_PROGRESS = "downloads"
    private const val CHANNEL_RESULTS = "results"
    private const val CHANNEL_UPDATES = "updates"
    private const val CHANNEL_QUEUE = "queue"
    const val QUEUE_SENT_ID = 4

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_QUEUE, context.getString(R.string.channel_queue), NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** [percent] null means the progress isn't known yet. [title] says what's going on. */
    fun progress(context: Context, percent: Int?, @StringRes title: Int = R.string.notif_downloading): Notification =
        Notification.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(title))
            .setContentText(percent?.let { "$it%" } ?: context.getString(R.string.notif_starting))
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    /** [failedTitle] heads a failure: a phone download's, or Save to phone's. */
    fun result(context: Context, outcome: PhoneDownloadOutcome, @StringRes failedTitle: Int = R.string.notif_failed): Notification {
        val builder = Notification.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
        when (outcome) {
            is PhoneDownloadOutcome.Saved -> {
                val first = outcome.videos.first()
                val text = if (outcome.videos.size == 1) {
                    context.getString(R.string.notif_saved_one, first.name)
                } else {
                    context.resources.getQuantityString(R.plurals.notif_saved_many, outcome.videos.size, outcome.videos.size)
                }
                builder.setContentTitle(context.getString(R.string.notif_saved))
                    .setContentText(text)
                    .setContentIntent(playIntent(context, Uri.parse(first.id)))
            }
            is PhoneDownloadOutcome.Failed ->
                builder.setContentTitle(context.getString(failedTitle))
                    .setContentText(outcome.reason)
                    .setStyle(Notification.BigTextStyle().bigText(outcome.reason))
            // Tapping opens the login page; the download retries by itself after logging in.
            is PhoneDownloadOutcome.NeedsLogin ->
                builder.setContentTitle(context.getString(R.string.notif_failed))
                    .setContentText(context.getString(R.string.notif_needs_login))
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            0,
                            XLoginActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
        }
        return builder.build()
    }

    /** Upload finished: [title] is in [pc]'s PC library. */
    fun uploaded(context: Context, pc: Pc, title: String): Notification =
        Notification.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_uploaded, pc.name))
            .setContentText(title)
            .setAutoCancel(true)
            .build()

    /** Queued links went to their PCs in the background. */
    fun queueSent(context: Context, sent: List<SentLink>): Notification =
        Notification.Builder(context, CHANNEL_QUEUE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.resources.getQuantityString(R.plurals.queue_sent, sent.size, sent.size))
            .setContentText(context.getString(R.string.queue_sent_to, sent.map { it.pc.name }.distinct().joinToString(", ")))
            .setAutoCancel(true)
            .build()

    /** A newer version is on GitHub Releases; tapping opens the release page. */
    fun update(context: Context, update: Update): Notification =
        Notification.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_available, update.version))
            .setContentText(context.getString(R.string.update_tap))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(Intent.ACTION_VIEW, Uri.parse(update.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)
            .build()

    private fun playIntent(context: Context, video: Uri): PendingIntent {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(video, "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            video.hashCode(),
            view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
