package app.xvid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.xvid.core.PhoneDownloadOutcome

/** The notifications a phone download shows: progress, then Saved or failed. */
object Notifications {
    private const val CHANNEL_PROGRESS = "downloads"
    private const val CHANNEL_RESULTS = "results"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** [percent] null means the progress isn't known yet. */
    fun progress(context: Context, percent: Int?): Notification =
        Notification.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_downloading))
            .setContentText(percent?.let { "$it%" } ?: context.getString(R.string.notif_starting))
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    fun result(context: Context, outcome: PhoneDownloadOutcome): Notification {
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
                builder.setContentTitle(context.getString(R.string.notif_failed))
                    .setContentText(outcome.reason)
                    .setStyle(Notification.BigTextStyle().bigText(outcome.reason))
        }
        return builder.build()
    }

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
