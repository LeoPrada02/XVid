package app.xvid

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.xvid.core.XPostLink

/**
 * The main screen, laid out like the PC web app: a box to paste an X link, the phone
 * downloads, the phone library and the PCs. Also asks for the permissions phone
 * downloads need (notifications; storage on Android 8 and 9).
 */
class MainActivity : Activity() {
    private lateinit var link: EditText
    private lateinit var linkError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = EditText(this).apply {
            hint = getString(R.string.main_paste_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.muted))
            background = rounded(color(R.color.surface), color(R.color.border))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnEditorActionListener { _, action, _ ->
                (action == EditorInfo.IME_ACTION_GO).also { if (it) download() }
            }
        }
        linkError = text(TextStyle.ERROR).apply { visibility = View.GONE }
        setContentView(
            screen {
                // Takes the focus when the screen opens, so the keyboard stays down until the link box is tapped.
                isFocusableInTouchMode = true
                requestFocus()
                addView(row {
                    addView(text(TextStyle.TITLE, R.string.main_title), fill())
                    addView(button(R.string.settings_open, ButtonStyle.SECONDARY, small = true) {
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    })
                })
                addView(link, spaced(12))
                addView(row {
                    addView(button(R.string.main_paste, ButtonStyle.SECONDARY) { paste() }, fill())
                    addView(button(R.string.main_download) { download() }, fill().apply { marginStart = dp(8) })
                }, spaced(8))
                addView(linkError, spaced(6))
                addView(RecentDownloadsSection(this@MainActivity))
                addView(PhoneLibrarySection(this@MainActivity))
                addView(PcSectionsView(this@MainActivity))
                addView(text(TextStyle.SMALL, getString(R.string.main_version, BuildConfig.VERSION_NAME)), spaced(28))
                addView(updateLink, spaced(4))
            },
        )
        requestMissingPermissions()
        showNewerRelease()
    }

    /** Downloads the post in the link box to the phone, like sharing it to XVid. */
    private fun download() {
        val text = link.text.toString()
        if (XPostLink.find(text) == null) {
            linkError.setText(if (text.isBlank()) R.string.main_link_empty else R.string.main_link_not_x)
            linkError.visibility = View.VISIBLE
            return
        }
        linkError.visibility = View.GONE
        link.text.clear()
        DownloadService.start(this, text)
        Toast.makeText(this, R.string.toast_downloading, Toast.LENGTH_SHORT).show()
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        link.setText(text.trim())
        link.setSelection(link.text.length)
    }

    private val updateLink by lazy {
        button("", ButtonStyle.LINK) {}.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }
    }

    /** Checks GitHub Releases in the background and, if there's a newer version, links to it. */
    private fun showNewerRelease() {
        val check = (application as XVidApp).updateCheck ?: return
        Thread {
            val update = check.newerRelease() ?: return@Thread
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                updateLink.text = getString(R.string.main_update, update.version)
                updateLink.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.url))) }
                updateLink.visibility = View.VISIBLE
            }
        }.start()
    }

    private fun requestMissingPermissions() {
        val wanted = Permissions.missing(this)
        if (wanted.isNotEmpty()) {
            requestPermissions(wanted.toTypedArray(), 1)
            Permissions.markAsked(this)
        }
    }
}
