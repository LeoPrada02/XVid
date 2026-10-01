package app.xvid

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import app.xvid.core.RecentDownload

/**
 * The Downloads section of the main screen, like the web app's: one card per recent phone
 * download with how it's going or how it ended, so nothing depends on notifications. When
 * XVid isn't allowed to notify, it says so and offers to turn notifications on. Shown again
 * every few seconds while visible.
 */
class RecentDownloadsSection(context: Context) : LinearLayout(context) {
    private val app = context.applicationContext as XVidApp
    private val notificationsOff = context.card(14) {
        addView(context.text(TextStyle.MUTED, R.string.recent_notifications_off))
        addView(context.button(R.string.recent_notifications_turn_on, ButtonStyle.SECONDARY, small = true) {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }, context.spaced(10, LayoutParams.WRAP_CONTENT))
    }
    private val clear = context.button(R.string.recent_clear, ButtonStyle.LINK) {
        app.recentDownloads.clearFinished()
        show()
    }
    private val rows = context.column()
    private var shown: List<RecentDownload>? = null
    private val refresh = object : Runnable {
        override fun run() {
            show()
            postDelayed(this, REFRESH_MS)
        }
    }

    init {
        orientation = VERTICAL
        addView(context.sectionHead(R.string.recent_title, clear))
        addView(notificationsOff, context.spaced(0))
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
        val downloads = app.recentDownloads.list()
        clear.visibility = if (downloads.any { it.state == RecentDownload.State.SAVED || it.state == RecentDownload.State.FAILED }) VISIBLE else GONE
        if (downloads == shown) return // rebuilt only when something changed, so progress bars keep moving
        shown = downloads
        rows.removeAllViews()
        if (downloads.isEmpty()) {
            rows.addView(context.card(14) { addView(context.text(TextStyle.MUTED, R.string.main_help)) }, context.spaced())
            return
        }
        for (download in downloads) rows.addView(card(download), context.spaced())
    }

    private fun card(download: RecentDownload) = context.card {
        val failed = download.state == RecentDownload.State.FAILED || download.state == RecentDownload.State.NEEDS_LOGIN
        addView(context.row {
            addView(context.text(TextStyle.BODY, download.url.removePrefix("https://")).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                textSize = 14f
            }, fill())
            addView(context.text(if (failed) TextStyle.ERROR else TextStyle.SMALL, context.getString(statusOf(download.state))).apply {
                setPadding(context.dp(8), 0, 0, 0)
            })
        })
        when (download.state) {
            RecentDownload.State.DOWNLOADING -> addView(context.progressBar(), context.spaced(6))
            RecentDownload.State.SAVED -> addView(context.text(TextStyle.SMALL, download.detail), context.spaced(4))
            RecentDownload.State.FAILED -> addView(context.text(TextStyle.ERROR, download.detail), context.spaced(6))
            RecentDownload.State.WAITING -> addView(context.text(TextStyle.SMALL, download.detail), context.spaced(4))
            RecentDownload.State.NEEDS_LOGIN -> {
                addView(context.text(TextStyle.ERROR, R.string.notif_needs_login), context.spaced(6))
                addView(context.button(R.string.settings_x_log_in, small = true) {
                    context.startActivity(XLoginActivity.intent(context))
                }, context.spaced(8, LayoutParams.WRAP_CONTENT))
            }
        }
        if (download.state == RecentDownload.State.SAVED) {
            isClickable = true
            setOnClickListener { PhoneLibraryActivity.open(context) }
        }
    }

    private fun statusOf(state: RecentDownload.State) = when (state) {
        RecentDownload.State.DOWNLOADING -> R.string.recent_downloading
        RecentDownload.State.SAVED -> R.string.recent_saved
        RecentDownload.State.FAILED -> R.string.recent_failed
        RecentDownload.State.NEEDS_LOGIN -> R.string.recent_needs_login
        RecentDownload.State.WAITING -> R.string.recent_waiting
    }

    private companion object {
        const val REFRESH_MS = 2000L
    }
}
