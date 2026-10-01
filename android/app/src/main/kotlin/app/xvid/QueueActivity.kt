package app.xvid

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.xvid.core.QueuedLink

/**
 * The queue: To PC links waiting for their PC. Each can be removed, or sent to a different PC
 * (right away if that one is reachable). Opening it also tries to send the queue.
 */
class QueueActivity : Activity() {
    private val app get() = application as XVidApp
    private lateinit var status: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = text(TextStyle.SMALL)
        list = column()
        setContentView(screen {
            addView(text(TextStyle.TITLE, R.string.queue_title))
            addView(text(TextStyle.SMALL, R.string.queue_help), spaced(4))
            addView(status, spaced(8))
            addView(list)
        })
    }

    override fun onResume() {
        super.onResume()
        show(app.toPc.queue())
        inBackground({ app.toPc.sendQueue() }) { show(app.toPc.queue()) }
    }

    private fun show(links: List<QueuedLink>) {
        status.text = if (links.isEmpty()) {
            getString(R.string.queue_empty)
        } else {
            resources.getQuantityString(R.plurals.queue_count, links.size, links.size)
        }
        list.removeAllViews()
        for (link in links) {
            val pcName = app.knownPcs.find(link.pcId)?.name ?: getString(R.string.queue_unknown_pc)
            list.addView(card {
                addView(text(TextStyle.BODY, link.url.removePrefix("https://")))
                addView(text(TextStyle.SMALL, getString(
                    R.string.queue_for,
                    pcName,
                    DateUtils.getRelativeTimeSpanString(link.queuedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                )), spaced(4))
                addView(row {
                    addView(button(R.string.queue_move, ButtonStyle.SECONDARY, small = true) { chooseOtherPc(link) }, fill())
                    addView(button(R.string.queue_remove, ButtonStyle.DANGER, small = true) { remove(link) }, fill().apply { marginStart = dp(8) })
                }, spaced(10))
            }, spaced())
        }
    }

    private fun remove(link: QueuedLink) {
        app.toPc.remove(link.id)
        QueueSending.update(this)
        show(app.toPc.queue())
    }

    private fun chooseOtherPc(link: QueuedLink) {
        val pcs = app.knownPcs.list().filter { it.id != link.pcId }
        if (pcs.isEmpty()) {
            Toast.makeText(this, R.string.pcs_none, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.queue_choose_pc)
            .setItems(pcs.map { it.name }.toTypedArray()) { _, which ->
                val pc = pcs[which]
                inBackground({ app.toPc.reassign(link.id, pc) }) { outcome ->
                    // Null: already sent or removed meanwhile.
                    outcome?.let { Toast.makeText(this, ShareActivity.describe(this, it), Toast.LENGTH_LONG).show() }
                    QueueSending.update(this)
                    show(app.toPc.queue())
                }
            }
            .show()
    }

    /** Runs [work] off the main thread (it does network I/O), then [done] on it if the screen is still there. */
    private fun <T> inBackground(work: () -> T, done: (T) -> Unit) {
        Thread {
            val result = work()
            runOnUiThread { if (!isDestroyed) done(result) }
        }.start()
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, QueueActivity::class.java))
    }
}

/** The queue on the main screen: how many links wait, and the way to the queue screen. Hidden when empty. */
class QueueSection(context: Context) : LinearLayout(context) {
    private val summary = context.text(TextStyle.SMALL)

    init {
        orientation = VERTICAL
        addView(context.sectionHead(R.string.queue_title, context.button(R.string.queue_see, ButtonStyle.LINK) { QueueActivity.open(context) }))
        addView(summary)
    }

    /** Shown again whenever the main screen comes back; then the queue is tried too, as the app opens. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) return
        val toPc = (context.applicationContext as XVidApp).toPc
        val waiting = toPc.queue().size
        show(waiting)
        if (waiting == 0) return
        Thread {
            toPc.sendQueue()
            val left = toPc.queue().size
            post { show(left) }
        }.start()
    }

    private fun show(count: Int) {
        this.visibility = if (count == 0) GONE else VISIBLE
        summary.text = resources.getQuantityString(R.plurals.queue_count, count, count)
    }
}
