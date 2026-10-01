package app.xvid

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.BaseAdapter
import android.widget.GridView

/**
 * A library screen's grid of video tiles. Pinching changes how many videos a row shows, from 1 to 4:
 * spreading the fingers shows fewer and bigger, pinching more and smaller. Remembered across restarts
 * under [key]; at first, as many tiles at least 150dp wide as fit. With 3 or more a row, tiles show
 * less text (see [VideoTiles.fit]).
 */
internal class ZoomableGrid(context: Context, private val key: String, adapter: BaseAdapter) : GridView(context) {
    private val saved = context.getSharedPreferences("grids", Context.MODE_PRIVATE)
    private var pinching = false
    private var zoom = 1f

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            zoom = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom *= detector.scaleFactor
            when {
                zoom > ZOOM_STEP -> showColumns(numColumns - 1)
                zoom < 1 / ZOOM_STEP -> showColumns(numColumns + 1)
                else -> return true
            }
            zoom = 1f
            return true
        }
    })

    init {
        val widthDp = resources.displayMetrics.run { widthPixels / density } - 32 // the screen's side padding
        numColumns = saved.getInt(key, ((widthDp + 12) / (150 + 12)).toInt()).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        stretchMode = STRETCH_COLUMN_WIDTH
        horizontalSpacing = context.dp(12)
        verticalSpacing = context.dp(12)
        setPadding(0, context.dp(12), 0, context.dp(24))
        clipToPadding = false
        selector = ColorDrawable(Color.TRANSPARENT)
        this.adapter = adapter
    }

    /** Two fingers on the grid pinch it: the tile under the first finger isn't tapped, and the grid doesn't scroll. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        if (event.pointerCount > 1 && !pinching) {
            pinching = true
            parent?.requestDisallowInterceptTouchEvent(true) // nor a pull to reload
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (!pinching) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) pinching = false
        return true
    }

    private fun showColumns(columns: Int) {
        val wanted = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        if (wanted == numColumns) return
        val first = firstVisiblePosition
        numColumns = wanted
        saved.edit().putInt(key, wanted).apply()
        invalidateViews() // the tiles show more or less text now
        setSelection(first)
    }

    private companion object {
        const val MIN_COLUMNS = 1
        const val MAX_COLUMNS = 4

        /** How far the fingers must spread (or pinch) for one column less (or more). */
        const val ZOOM_STEP = 1.3f
    }
}
