package app.xvid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Share → XVid. Hands the shared text to [DownloadService] and closes at once,
 * so the user is straight back in X while the phone download runs.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            DownloadService.start(this, text)
            Toast.makeText(applicationContext, R.string.toast_downloading, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
