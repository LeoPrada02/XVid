package app.xvid

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import app.xvid.core.MaximumQuality

/** Settings: the Maximum quality for phone downloads (Best / 720p / 480p), and the X login. */
class SettingsActivity : Activity() {
    private val loginStatus by lazy { text(TextStyle.MUTED) }
    private val loginButtonSlot by lazy { column() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val setting = (application as XVidApp).maximumQuality
        setContentView(
            screen {
                addView(text(TextStyle.TITLE, R.string.settings_title))
                addView(card(16) {
                    addView(text(TextStyle.HEADING, R.string.settings_quality))
                    addView(text(TextStyle.MUTED, R.string.settings_quality_help), spaced(4))
                    addView(RadioGroup(context).apply {
                        val current = setting.current()
                        MaximumQuality.entries.forEach { quality ->
                            addView(RadioButton(context).apply {
                                id = View.generateViewId()
                                setText(labelOf(quality))
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                                setTextColor(color(R.color.text))
                                buttonTintList = ColorStateList.valueOf(color(R.color.accent))
                                isChecked = quality == current
                                setOnCheckedChangeListener { _, checked -> if (checked) setting.choose(quality) }
                            })
                        }
                    }, spaced(8))
                }, spaced(16))
                addView(card(16) {
                    addView(text(TextStyle.HEADING, R.string.settings_x_login))
                    addView(loginStatus, spaced(4))
                    addView(loginButtonSlot, spaced(12))
                }, spaced(12))
            },
        )
    }

    /** Shown again when coming back from the login page. */
    override fun onResume() {
        super.onResume()
        showXLogin()
    }

    private fun showXLogin() {
        val loggedIn = (application as XVidApp).xLogin.isLoggedIn()
        loginStatus.setText(if (loggedIn) R.string.settings_x_logged_in else R.string.settings_x_logged_out)
        loginButtonSlot.removeAllViews()
        val wrap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        loginButtonSlot.addView(
            if (loggedIn) {
                button(R.string.settings_x_log_out, ButtonStyle.DANGER) {
                    XLoginActivity.logOut(this)
                    showXLogin()
                }
            } else {
                button(R.string.settings_x_log_in) { startActivity(XLoginActivity.intent(this)) }
            },
            wrap,
        )
    }

    private fun labelOf(quality: MaximumQuality) = when (quality) {
        MaximumQuality.BEST -> R.string.quality_best
        MaximumQuality.P720 -> R.string.quality_720p
        MaximumQuality.P480 -> R.string.quality_480p
    }
}
