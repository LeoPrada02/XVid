package app.xvid

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.xvid.core.RecentDownload

/**
 * The Downloads section of the main screen: the latest phone downloads and how each one is
 * going or ended, so nothing depends on notifications. When XVid isn't allowed to notify,
 * it says so and offers to turn notifications on. Shown again every few seconds while visible.
 */
class RecentDownloadsSection(context: Context) : LinearLayout(context) {
    private val app = context.applicationContext as XVidApp
    private val notificationsOff = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(text(14f).apply { setText(R.string.recent_notifications_off) })
        addView(Button(context).apply {
            setText(R.string.recent_notifications_turn_on)
            setOnClickListener {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        })
    }
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val refresh = object : Runnable {
        override fun run() {
            show()
            postDelayed(this, REFRESH_MS)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(0, dp(24), 0, 0)
        addView(text(20f).apply { setText(R.string.recent_title) })
        addView(notificationsOff)
        addView(rows)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(refresh)
        if (visibility == View.VISIBLE) post(refresh)
    }

    private fun show() {
        val notificationsOn = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        notificationsOff.visibility = if (notificationsOn) GONE else VISIBLE
        rows.removeAllViews()
        val downloads = app.recentDownloads.list()
        if (downloads.isEmpty()) {
            rows.addView(text(15f).apply { setText(R.string.recent_none) })
            return
        }
        for (download in downloads) {
            rows.addView(text(15f).apply {
                text = context.getString(
                    R.string.recent_post,
                    download.url.substringAfterLast('/'),
                    DateUtils.getRelativeTimeSpanString(download.time),
                )
                setPadding(0, dp(12), 0, 0)
            })
            rows.addView(text(14f).apply {
                text = when (download.state) {
                    RecentDownload.State.DOWNLOADING -> context.getString(R.string.recent_downloading)
                    RecentDownload.State.SAVED -> context.getString(R.string.recent_saved, download.detail)
                    RecentDownload.State.FAILED -> context.getString(R.string.recent_failed, download.detail)
                    RecentDownload.State.NEEDS_LOGIN -> context.getString(R.string.notif_needs_login)
                }
                alpha = 0.75f
            })
            if (download.state == RecentDownload.State.NEEDS_LOGIN) {
                rows.addView(Button(context).apply {
                    setText(R.string.settings_x_log_in)
                    setOnClickListener { context.startActivity(XLoginActivity.intent(context)) }
                })
            }
        }
    }

    private fun text(sizeSp: Float) = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp) }

    private companion object {
        const val REFRESH_MS = 2000L
    }
}
