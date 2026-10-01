package app.xvid

import android.content.Context
import android.util.TypedValue
import android.view.View

/** [value] density-independent pixels, in pixels: the size the screens are laid out in. */
internal fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

internal fun View.dp(value: Int): Int = context.dp(value)
