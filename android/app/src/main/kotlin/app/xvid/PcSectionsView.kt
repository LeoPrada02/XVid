package app.xvid

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.xvid.core.KnownPcs
import app.xvid.core.PairingResult
import app.xvid.core.PcState
import app.xvid.core.PcStatus
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * The PCs part of the main screen: one section per known PC, shown as reachable or not, and
 * "Pair with a PC", which scans the QR code in step 2 of the PC's Add a phone dialog.
 *
 * The scanner is Google's code scanner (Play services): it needs no camera permission, since the
 * camera runs in Play services and only the scanned text comes back.
 */
class PcSectionsView(context: Context) : LinearLayout(context) {
    private val activity = context as Activity
    private val pcs: KnownPcs = (activity.application as XVidApp).knownPcs
    private val sections = LinearLayout(activity).apply { orientation = VERTICAL }
    private val message = text(15f).apply { visibility = GONE }
    private val pairButton = Button(activity).apply {
        setText(R.string.pcs_pair)
        setOnClickListener { scan() }
    }

    init {
        orientation = VERTICAL
        setPadding(0, dp(32), 0, 0)
        addView(text(22f).apply { setText(R.string.pcs_title) })
        addView(sections)
        addView(message)
        addView(pairButton)
        show(pcs.list().map { PcStatus(it, PcState.NOT_REACHABLE) }, checking = true)
    }

    /** Checks again whenever the screen comes back, e.g. after joining the home Wi-Fi. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) refresh()
    }

    private fun refresh() = inBackground({ pcs.refresh() }) { show(it, checking = false) }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { barcode -> pair(barcode.rawValue.orEmpty()) }
            .addOnFailureListener { say(activity.getString(R.string.pcs_scanner_failed, it.localizedMessage.orEmpty())) }
    }

    private fun pair(qr: String) {
        pairButton.isEnabled = false
        say(activity.getString(R.string.pcs_pairing))
        inBackground({ pcs.pair(qr) }) { result ->
            pairButton.isEnabled = true
            say(when (result) {
                is PairingResult.Paired -> activity.resources.getQuantityString(R.plurals.pcs_paired, result.pcs.size, result.pcs.size)
                PairingResult.NotAPairingCode -> activity.getString(R.string.pcs_not_a_code)
                PairingResult.NotReachable -> activity.getString(R.string.pcs_pair_not_reachable)
                PairingResult.FingerprintMismatch -> activity.getString(R.string.pcs_fingerprint_mismatch)
                is PairingResult.CodeRefused -> result.reason
            })
            if (result is PairingResult.Paired) refresh()
        }
    }

    private fun show(statuses: List<PcStatus>, checking: Boolean) {
        sections.removeAllViews()
        if (statuses.isEmpty()) {
            sections.addView(text(16f).apply { setText(R.string.pcs_none) })
            return
        }
        for (status in statuses) {
            sections.addView(text(18f).apply {
                text = if (status.pc.home) activity.getString(R.string.pcs_home, status.pc.name) else status.pc.name
                setPadding(0, dp(16), 0, 0)
            })
            sections.addView(text(15f).apply {
                setText(when {
                    checking -> R.string.pcs_checking
                    status.state == PcState.REACHABLE -> R.string.pcs_reachable
                    status.state == PcState.PAIR_AGAIN -> R.string.pcs_pair_again
                    else -> R.string.pcs_not_reachable
                })
                alpha = if (status.state == PcState.REACHABLE && !checking) 1f else 0.6f
            })
        }
    }

    private fun say(text: String) {
        message.text = text
        message.visibility = VISIBLE
    }

    /** Runs [work] off the main thread (it does network I/O), then [done] on it if the screen is still there. */
    private fun <T> inBackground(work: () -> T, done: (T) -> Unit) {
        Thread {
            val result = work()
            activity.runOnUiThread { if (!activity.isDestroyed) done(result) }
        }.start()
    }

    private fun text(sizeSp: Float) = TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp) }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
