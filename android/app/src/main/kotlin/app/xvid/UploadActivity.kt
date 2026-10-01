package app.xvid

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.xvid.core.Pc

/**
 * Upload: a small sheet that sends a video from the phone to a reachable PC library. It first looks
 * for reachable PCs: with none, it says so and nothing can be uploaded. Then the video (a phone
 * library one, or picked from the gallery here) goes to the PC chosen among the reachable ones, or
 * the one given (from that PC's section). The upload itself runs in [DownloadService], with a
 * progress notification.
 */
class UploadActivity : Activity() {
    private val app get() = application as XVidApp
    private var video: Uri? = null
    private var reachable: List<Pc>? = null
    private lateinit var status: TextView
    private lateinit var choices: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        video = savedInstanceState?.getParcelableCompat(STATE_VIDEO) ?: intent.data
        status = text(TextStyle.SMALL)
        choices = column()
        sheet {
            addView(text(TextStyle.HEADING, R.string.upload_title))
            addView(status, spaced(4))
            addView(choices)
        }
        // Recreated while the picker was open: the picked video comes in onActivityResult.
        if (savedInstanceState == null || video != null) lookForPcs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(STATE_VIDEO, video)
    }

    /** The reachable PCs (only the given one counts, if one was), then the next step. */
    private fun lookForPcs() {
        status.setText(R.string.upload_looking)
        val given = intent.pc(app)
        Thread {
            val found = app.knownPcs.reachable().filter { given == null || it.id == given.id }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                reachable = found
                when {
                    found.isEmpty() -> showNoneReachable()
                    video == null -> pickVideo()
                    else -> choosePc()
                }
            }
        }.start()
    }

    private fun showNoneReachable() {
        status.setText(if (app.knownPcs.list().isEmpty()) R.string.upload_none_paired else R.string.upload_none_reachable)
        choices.removeAllViews()
        choices.addView(button(R.string.upload_close, ButtonStyle.SECONDARY) { finish() }, spaced(14))
    }

    /** The gallery's own picker: any video on the phone, with no storage permission needed. */
    private fun pickVideo() {
        status.setText(R.string.upload_picking)
        val pick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*")
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(pick, PICK)
        } catch (e: ActivityNotFoundException) {
            status.setText(R.string.upload_no_picker)
        }
    }

    @Deprecated("Activity results, the way of the plain Activity class")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK) return
        video = data?.data?.takeIf { resultCode == RESULT_OK } ?: return finish()
        if (reachable == null) lookForPcs() else choosePc()
    }

    private fun choosePc() {
        val video = video ?: return finish()
        val pcs = reachable.orEmpty()
        intent.pc(app)?.let { given -> pcs.firstOrNull { it.id == given.id }?.let { return upload(video, it) } }
        status.setText(R.string.upload_choose)
        val choice = pcChoices(pcs, selected = pcs.firstOrNull { it.home } ?: pcs.first())
        choices.removeAllViews()
        choices.addView(choice, spaced(4))
        choices.addView(button(R.string.upload_send) { choice.chosenPc()?.let { upload(video, it) } }, spaced(8))
    }

    private fun upload(video: Uri, pc: Pc) {
        DownloadService.upload(this, pc, video)
        Toast.makeText(applicationContext, getString(R.string.upload_started, pc.name), Toast.LENGTH_SHORT).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun Bundle.getParcelableCompat(key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelable(key, Uri::class.java) else getParcelable(key)

    companion object {
        private const val PICK = 1
        private const val STATE_VIDEO = "video"

        /** Uploads [video], a phone library video, to a PC chosen in the sheet. */
        fun open(context: Context, video: Uri) {
            context.startActivity(Intent(context, UploadActivity::class.java).setData(video))
        }

        /** Picks a video from the gallery, then uploads it to [pc] (or to a PC chosen in the sheet). */
        fun pickFromGallery(context: Context, pc: Pc? = null) {
            context.startActivity(Intent(context, UploadActivity::class.java).apply { pc?.let { putPc(it) } })
        }
    }
}
