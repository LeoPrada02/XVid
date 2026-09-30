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
    private val app = activity.application as XVidApp
    private val pcs: KnownPcs = app.knownPcs
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
        if (visibility == View.VISIBLE) {
            showPairing()
            refresh()
        }
    }

    // Android may recreate the main screen while the QR code scanner is open, so the pairing's
    // progress and result are kept in the app and shown by whichever screen is there now.
    private val onPairingChanged = {
        showPairing()
        if (!app.pairing.inProgress) refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        app.pairing.listener = onPairingChanged
    }

    override fun onDetachedFromWindow() {
        if (app.pairing.listener === onPairingChanged) app.pairing.listener = null
        super.onDetachedFromWindow()
    }

    private fun showPairing() {
        pairButton.isEnabled = !app.pairing.inProgress
        app.pairing.message?.let(::say)
    }

    private fun refresh() = inBackground({ pcs.refresh() }) { show(it, checking = false) }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { barcode -> pair(barcode.rawValue.orEmpty()) }
            .addOnFailureListener {
                app.pairing.update(inProgress = false, message = app.getString(R.string.pcs_scanner_failed, it.localizedMessage.orEmpty()))
            }
    }

    private fun pair(qr: String) {
        val res = app.resources
        app.pairing.update(inProgress = true, message = res.getString(R.string.pcs_pairing))
        Thread {
            val result = pcs.pair(qr)
            app.pairing.update(inProgress = false, message = when (result) {
                is PairingResult.Paired -> res.getQuantityString(R.plurals.pcs_paired, result.pcs.size, result.pcs.size)
                PairingResult.NotAPairingCode -> res.getString(R.string.pcs_not_a_code)
                PairingResult.NotReachable -> res.getString(R.string.pcs_pair_not_reachable)
                PairingResult.FingerprintMismatch -> res.getString(R.string.pcs_fingerprint_mismatch)
                is PairingResult.CodeRefused -> result.reason
            })
        }.start()
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
}
