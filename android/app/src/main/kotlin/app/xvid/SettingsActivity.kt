package app.xvid

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import app.xvid.core.MaximumQuality

/** Settings: the Maximum quality for phone downloads (Best / 720p / 480p). */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val setting = (application as XVidApp).maximumQuality
        val padding = dp(24)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding * 3, padding, padding)
                addView(TextView(context).apply {
                    setText(R.string.settings_title)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                })
                addView(TextView(context).apply {
                    setText(R.string.settings_quality)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setPadding(0, dp(24), 0, 0)
                })
                addView(TextView(context).apply {
                    setText(R.string.settings_quality_help)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(0, dp(4), 0, dp(8))
                })
                addView(RadioGroup(context).apply {
                    val current = setting.current()
                    MaximumQuality.entries.forEach { quality ->
                        addView(RadioButton(context).apply {
                            id = View.generateViewId()
                            setText(labelOf(quality))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            isChecked = quality == current
                            setOnCheckedChangeListener { _, checked -> if (checked) setting.choose(quality) }
                        })
                    }
                })
            },
        )
    }

    private fun labelOf(quality: MaximumQuality) = when (quality) {
        MaximumQuality.BEST -> R.string.quality_best
        MaximumQuality.P720 -> R.string.quality_720p
        MaximumQuality.P480 -> R.string.quality_480p
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
