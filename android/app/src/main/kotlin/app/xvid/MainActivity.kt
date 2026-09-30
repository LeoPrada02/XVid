package app.xvid

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Explains how to use Share → XVid and asks for the permissions phone
 * downloads need (notifications; storage on Android 8 and 9).
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = dp(24)
        setContentView(
            // Scrolls: the phone library, one section per PC and the settings can outgrow a small screen.
            ScrollView(this).apply {
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(padding, padding * 3, padding, padding)
                        addView(TextView(context).apply {
                            setText(R.string.main_title)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                        })
                        addView(TextView(context).apply {
                            setText(R.string.main_help)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            setPadding(0, dp(16), 0, 0)
                        })
                        addView(RecentDownloadsSection(context))
                        addView(PhoneLibrarySection(context))
                        addView(PcSectionsView(this@MainActivity))
                        addView(TextView(context).apply {
                            setText(R.string.settings_open)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            setPadding(0, dp(24), 0, 0)
                            setOnClickListener { startActivity(Intent(context, SettingsActivity::class.java)) }
                        })
                        addView(TextView(context).apply {
                            text = getString(R.string.main_version, BuildConfig.VERSION_NAME)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            setPadding(0, dp(24), 0, 0)
                        })
                        addView(updateLink)
                    },
                )
            },
        )
        requestMissingPermissions()
        showNewerRelease()
    }

    private val updateLink by lazy {
        TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(8), 0, 0)
            visibility = TextView.GONE
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
                updateLink.visibility = TextView.VISIBLE
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
