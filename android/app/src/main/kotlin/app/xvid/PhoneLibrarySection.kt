package app.xvid

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Phone library section of the main screen: how many videos the phone
 * library holds, counted again each time the screen is shown. Tapping it
 * opens [PhoneLibraryActivity].
 */
class PhoneLibrarySection(context: Context) : LinearLayout(context) {
    private val summary = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(0, dp(4), 0, 0)
    }

    init {
        orientation = VERTICAL
        setPadding(0, dp(24), 0, dp(8))
        isClickable = true
        isFocusable = true
        TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            setBackgroundResource(it.resourceId)
        }
        addView(TextView(context).apply {
            setText(R.string.library_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        addView(summary)
        setOnClickListener { PhoneLibraryActivity.open(context) }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) count()
    }

    private fun count() {
        val browser = (context.applicationContext as XVidApp).phoneLibrary
        Thread {
            val text = runCatching { browser.videos().size }.fold(
                onSuccess = { resources.getQuantityString(R.plurals.library_summary, it, it) },
                onFailure = { context.getString(R.string.library_open) },
            )
            post { summary.text = text }
        }.start()
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
