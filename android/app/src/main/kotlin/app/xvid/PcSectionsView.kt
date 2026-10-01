package app.xvid

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import app.xvid.core.KnownPcs
import app.xvid.core.PairingResult
import app.xvid.core.PcState
import app.xvid.core.PcStatus
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * The PCs part of the main screen: one section per known PC, shown as reachable or not (with
 * its PC library when it is, see [PcLibraryPreview]; tapping the PC's name folds that away), and
 * "Pair with a PC", which scans the QR code in step 2 of the PC's Add a phone dialog.
 *
 * The scanner is Google's code scanner (Play services): it needs no camera permission, since the
 * camera runs in Play services and only the scanned text comes back.
 */
class PcSectionsView(context: Context) : LinearLayout(context) {
    private val activity = context as Activity
    private val app = activity.application as XVidApp
    private val pcs: KnownPcs = app.knownPcs
    private val sections = activity.column()
    private val message = activity.text(TextStyle.MUTED).apply { visibility = GONE }
    private val pairButton = activity.button(R.string.pcs_pair, ButtonStyle.LINK) { scan() }

    init {
        orientation = VERTICAL
        addView(activity.sectionHead(R.string.pcs_title, pairButton))
        addView(message, activity.spaced(0))
        addView(sections)
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
        pairButton.alpha = if (app.pairing.inProgress) 0.4f else 1f
        app.pairing.message?.let(::say)
    }

    private fun refresh() = reload {}

    /** Checks the PCs again and shows them, then calls [done]. */
    fun reload(done: () -> Unit) = inBackground({ pcs.refresh() }) {
        show(it, checking = false)
        done()
    }

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
            sections.addView(activity.card(14) { addView(activity.text(TextStyle.MUTED, R.string.pcs_none)) }, activity.spaced())
            return
        }
        for (status in statuses) {
            val reachable = !checking && status.state == PcState.REACHABLE
            sections.addView(activity.card {
                val name = activity.text(TextStyle.HEADING).apply {
                    text = if (status.pc.home) activity.getString(R.string.pcs_home, status.pc.name) else status.pc.name
                    textSize = 15f
                }
                // See all stays by the name, so it's there with the library folded too.
                addView(activity.row {
                    addView(name, fill())
                    if (reachable) addView(activity.button(R.string.library_see_all, ButtonStyle.LINK) { PcLibraryActivity.open(activity, status.pc) })
                })
                addView(activity.text(TextStyle.SMALL).apply {
                    setText(when {
                        checking -> R.string.pcs_checking
                        status.state == PcState.REACHABLE -> R.string.pcs_reachable
                        status.state == PcState.PAIR_AGAIN -> R.string.pcs_pair_again
                        else -> R.string.pcs_not_reachable
                    })
                    if (!checking && status.state == PcState.REACHABLE) setTextColor(activity.color(R.color.ok))
                    if (!checking && status.state == PcState.PAIR_AGAIN) setTextColor(activity.color(R.color.danger))
                }, activity.spaced(4))
                if (reachable) {
                    val library = activity.column()
                    addView(library, activity.spaced(8))
                    activity.foldable(name, "pc.${status.pc.id}") { open ->
                        // Loaded only when open, so a folded PC's library isn't fetched.
                        if (open && library.childCount == 0) library.addView(PcLibraryPreview(activity, status.pc))
                        library.visibility = if (open) VISIBLE else GONE
                    }
                }
            }, activity.spaced())
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
}
