package app.xvid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import app.xvid.core.Pc
import app.xvid.core.ToPcOutcome

/**
 * Share → XVid: a small sheet over the app the post was shared from. **Download to phone** (the
 * default) hands the text to [DownloadService]; **To PC** sends the link to the chosen PC (the one
 * chosen last is picked), or queues it when that PC isn't reachable. Either way it then closes, so
 * the user is straight back in X. With no PC paired there's nothing to choose: it downloads to the
 * phone at once. The main screen's To PC button opens it too, with only the To PC part.
 *
 * If XVid never asked for the permissions a phone download needs, it opens XVid to ask, once.
 */
class ShareActivity : Activity() {
    private val app get() = application as XVidApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) return finish()
        val toPcOnly = intent.getBooleanExtra(EXTRA_TO_PC_ONLY, false)
        val pcs = app.knownPcs.list()
        if (pcs.isEmpty() && !toPcOnly) return downloadToPhone(text)

        val status = text(TextStyle.SMALL).apply { visibility = View.GONE }
        val choice = RadioGroup(this)
        val last = app.toPc.lastChoice()
        for (pc in pcs) {
            choice.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = pc
                this.text = if (pc.home) getString(R.string.to_pc_home, pc.name) else pc.name
                setTextColor(color(R.color.text))
                textSize = 15f
                buttonTintList = ColorStateList.valueOf(color(R.color.accent))
                isChecked = pc == last
            })
        }
        val buttons = mutableListOf<TextView>()
        setContentView(column {
            background = rounded(color(R.color.surface), color(R.color.border), radiusDp = 16)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(text(TextStyle.HEADING, R.string.to_pc_title))
            addView(text(TextStyle.SMALL, text.trim()).apply { maxLines = 2 }, spaced(4))
            if (!toPcOnly) {
                addView(button(R.string.to_pc_phone) { downloadToPhone(text) }.also { buttons += it }, spaced(16))
            }
            addView(text(TextStyle.MUTED, if (toPcOnly) R.string.to_pc_only else R.string.to_pc_or), spaced(16))
            if (pcs.isEmpty()) {
                addView(text(TextStyle.SMALL, R.string.pcs_none), spaced(8))
            } else {
                addView(choice, spaced(4))
                val style = if (toPcOnly) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY
                addView(button(R.string.to_pc_send, style) {
                    val pc = choice.findViewById<RadioButton>(choice.checkedRadioButtonId)?.tag as? Pc ?: return@button
                    buttons.forEach { it.isEnabled = false; it.alpha = 0.4f }
                    status.setText(R.string.to_pc_sending)
                    status.visibility = View.VISIBLE
                    sendToPc(text, pc)
                }.also { buttons += it }, spaced(8))
            }
            addView(status, spaced(8))
        })
        window.setLayout(resources.displayMetrics.widthPixels * 9 / 10, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun downloadToPhone(text: String) {
        DownloadService.start(this, text)
        Toast.makeText(applicationContext, R.string.toast_downloading, Toast.LENGTH_SHORT).show()
        // Without notifications (or storage on Android 8 and 9) the download can't be
        // seen or saved, and this sheet can't ask: open XVid to ask.
        if (!Permissions.asked(this) && Permissions.missing(this).isNotEmpty()) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    /** Sends while the sheet says "Sending…" (a few seconds at most), then says how it went and closes. */
    private fun sendToPc(text: String, pc: Pc) {
        Thread {
            val outcome = app.toPc.send(text, pc)
            if (outcome is ToPcOutcome.Queued) QueueSending.update(app)
            runOnUiThread {
                Toast.makeText(applicationContext, describe(app, outcome), Toast.LENGTH_LONG).show()
                if (!isDestroyed) finish()
            }
        }.start()
    }

    companion object {
        private const val EXTRA_TO_PC_ONLY = "toPcOnly"

        /** How sending To PC went, in a sentence. */
        fun describe(context: Context, outcome: ToPcOutcome): String = when (outcome) {
            is ToPcOutcome.Sent -> context.getString(R.string.to_pc_sent, outcome.pc.name)
            is ToPcOutcome.Queued -> context.getString(R.string.to_pc_queued, outcome.pc.name)
            is ToPcOutcome.Refused -> context.getString(R.string.to_pc_refused, outcome.pc.name, outcome.reason)
            ToPcOutcome.NotALink -> context.getString(R.string.to_pc_not_a_link)
        }

        /** The To PC part only, for a link pasted on the main screen. */
        fun toPc(context: Context, text: String) {
            context.startActivity(
                Intent(context, ShareActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_TEXT, text)
                    .putExtra(EXTRA_TO_PC_ONLY, true),
            )
        }
    }
}
