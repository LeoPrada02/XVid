package app.xvid

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.xvid.core.Pc

// The building blocks of every screen, styled like the PC web app (static/style.css):
// dark background, rounded bordered cards, blue bold buttons, muted secondary text.

internal fun Context.color(id: Int): Int = getColor(id)

/** A rounded rectangle filled with [fill], with an optional 1dp [stroke]. */
internal fun Context.rounded(fill: Int, stroke: Int? = null, radiusDp: Int = 12): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        stroke?.let { setStroke(dp(1), it) }
    }

/** A screen: scrolls, on the web app's background, with its side margins. */
internal fun Context.screen(content: LinearLayout.() -> Unit): ScrollView = ScrollView(this).apply {
    setBackgroundColor(color(R.color.bg))
    isFillViewport = true
    addView(column {
        setPadding(dp(16), dp(24), dp(16), dp(24))
        content()
    })
}

internal fun Context.column(content: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; content() }

internal fun Context.row(content: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    content()
}

/** A card: the surface colour, a border and rounded corners, like `.card` and `.job`. */
internal fun Context.card(paddingDp: Int = 12, content: LinearLayout.() -> Unit = {}): LinearLayout = column {
    background = rounded(color(R.color.surface), color(R.color.border))
    setPadding(dp(paddingDp), dp(paddingDp - 2), dp(paddingDp), dp(paddingDp))
    content()
}

internal enum class TextStyle(val sizeSp: Float, val colorId: Int, val bold: Boolean = false) {
    TITLE(22f, R.color.text, bold = true),
    HEADING(16f, R.color.text, bold = true),
    BODY(15f, R.color.text),
    MUTED(14f, R.color.muted),
    SMALL(13f, R.color.muted),
    ERROR(13f, R.color.danger),
}

internal fun Context.text(style: TextStyle, value: CharSequence? = null): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sizeSp)
    setTextColor(color(style.colorId))
    if (style.bold) typeface = Typeface.DEFAULT_BOLD
    setLineSpacing(0f, 1.15f)
    value?.let { text = it }
}

internal fun Context.text(style: TextStyle, resId: Int): TextView = text(style, getString(resId))

internal enum class ButtonStyle { PRIMARY, SECONDARY, DANGER, LINK }

/** A button like the web app's: bold, rounded, blue (or [ButtonStyle.SECONDARY] and the others). */
internal fun Context.button(label: String, style: ButtonStyle = ButtonStyle.PRIMARY, small: Boolean = false, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 14f else 15f)
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val (fill, stroke, textColor) = when (style) {
            ButtonStyle.PRIMARY -> Triple(color(R.color.accent), null, color(R.color.accent_text))
            ButtonStyle.SECONDARY -> Triple(color(R.color.surface), color(R.color.border), color(R.color.text))
            ButtonStyle.DANGER -> Triple(android.graphics.Color.TRANSPARENT, color(R.color.danger), color(R.color.danger))
            ButtonStyle.LINK -> Triple(android.graphics.Color.TRANSPARENT, null, color(R.color.accent))
        }
        setTextColor(textColor)
        val shape = rounded(fill, stroke)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, rounded(0xFFFFFFFF.toInt()))
        when {
            style == ButtonStyle.LINK -> setPadding(dp(4), dp(4), dp(4), dp(4))
            small -> setPadding(dp(12), dp(6), dp(12), dp(6))
            else -> setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        minHeight = if (style == ButtonStyle.LINK) 0 else dp(if (small) 32 else 42)
        setOnClickListener { onClick() }
    }

internal fun Context.button(labelId: Int, style: ButtonStyle = ButtonStyle.PRIMARY, small: Boolean = false, onClick: () -> Unit) =
    button(getString(labelId), style, small, onClick)

/** A thin progress bar in the accent colour, like `.bar`. [percent] null: not known yet. */
internal fun Context.progressBar(percent: Int? = null): ProgressBar =
    ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = percent == null
        percent?.let { progress = it }
        progressTintList = ColorStateList.valueOf(color(R.color.accent))
        indeterminateTintList = ColorStateList.valueOf(color(R.color.accent))
        progressBackgroundTintList = ColorStateList.valueOf(color(R.color.border))
    }

/**
 * A section heading with an optional action on the right, like `.section-head`. With a [foldKey],
 * tapping the title folds [folds] away (see [foldable]).
 */
internal fun Context.sectionHead(titleId: Int, action: View? = null, foldKey: String? = null, vararg folds: View): LinearLayout = row {
    setPadding(0, dp(20), 0, dp(8))
    val title = text(TextStyle.HEADING, titleId)
    addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    action?.let { addView(it) }
    foldKey?.let { key -> foldable(title, key) { open -> folds.forEach { it.visibility = if (open) View.VISIBLE else View.GONE } } }
}

/**
 * Makes tapping [head] fold or unfold part of a screen, which [show] does; an arrow before the head's
 * text shows which. Open at first, and remembered across restarts under [key].
 */
internal fun Context.foldable(head: TextView, key: String, show: (open: Boolean) -> Unit) {
    val folds = getSharedPreferences("folds", Context.MODE_PRIVATE)
    val title = head.text
    fun apply(open: Boolean) {
        head.text = "${if (open) "\u25BE" else "\u25B8"}  $title"
        show(open)
    }
    apply(!folds.getBoolean(key, false))
    head.setOnClickListener {
        val open = folds.getBoolean(key, false) // it was folded, so it opens
        folds.edit().putBoolean(key, !open).apply()
        apply(open)
    }
}

/**
 * Pulling [content] down from its top reloads it: [reload] gets a function to call once it's done,
 * which hides the spinner. [scrollsUp] says whether [content] can still scroll up (when it isn't the
 * scrolling view itself), so pulling only reloads from the very top.
 */
internal fun Context.pullToReload(content: View, scrollsUp: (() -> Boolean)? = null, reload: (done: () -> Unit) -> Unit): SwipeRefreshLayout =
    SwipeRefreshLayout(this).apply {
        setColorSchemeColors(color(R.color.accent))
        setProgressBackgroundColorSchemeColor(color(R.color.surface))
        addView(content)
        scrollsUp?.let { check -> setOnChildScrollUpCallback { _, _ -> check() } }
        setOnRefreshListener { reload { isRefreshing = false } }
    }

/** A small sheet over the app below (see Theme.XVid.Sheet), like a dialog: [content] on a rounded card. */
internal fun Activity.sheet(content: LinearLayout.() -> Unit) {
    setContentView(column {
        background = rounded(color(R.color.surface), color(R.color.border), radiusDp = 16)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        content()
    })
    window.setLayout(resources.displayMetrics.widthPixels * 9 / 10, ViewGroup.LayoutParams.WRAP_CONTENT)
}

/** A choice of PC, one per line, with [selected] chosen; read the choice with [chosenPc]. */
internal fun Context.pcChoices(pcs: List<Pc>, selected: Pc?): RadioGroup = RadioGroup(this).apply {
    for (pc in pcs) {
        addView(RadioButton(context).apply {
            id = View.generateViewId()
            tag = pc
            text = if (pc.home) getString(R.string.pc_choice_home, pc.name) else pc.name
            setTextColor(color(R.color.text))
            textSize = 15f
            buttonTintList = ColorStateList.valueOf(color(R.color.accent))
            isChecked = pc == selected
        })
    }
}

internal fun RadioGroup.chosenPc(): Pc? = findViewById<RadioButton>(checkedRadioButtonId)?.tag as? Pc

/** Layout params with a top margin, for stacking cards with the web app's 8dp gaps. */
internal fun Context.spaced(topDp: Int = 8, width: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
    LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(topDp) }

/** Fills the rest of a row, for the one view in it that should stretch. */
internal fun fill(weight: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
