package app.xvid

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.xvid.core.PhoneLibraryVideo

/**
 * The Phone library section of the main screen, like a library in the web app: the
 * newest videos as tiles, read again each time the screen is shown. "See all" opens
 * [PhoneLibraryActivity].
 */
class PhoneLibrarySection(context: Context) : LinearLayout(context) {
    private val summary = context.text(TextStyle.SMALL)
    private val grid = context.column()

    init {
        orientation = VERTICAL
        addView(context.sectionHead(R.string.library_title, context.button(R.string.library_see_all, ButtonStyle.LINK) {
            PhoneLibraryActivity.open(context)
        }))
        addView(summary)
        addView(grid)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) load()
    }

    private fun load() {
        val browser = (context.applicationContext as XVidApp).phoneLibrary
        Thread {
            val result = runCatching { browser.videos() }
            post {
                result.onSuccess { show(it) }.onFailure { summary.setText(R.string.library_open) }
            }
        }.start()
    }

    private fun show(videos: List<PhoneLibraryVideo>) {
        summary.text = if (videos.isEmpty()) {
            context.getString(R.string.library_empty)
        } else {
            resources.getQuantityString(R.plurals.library_count, videos.size, videos.size)
        }
        grid.removeAllViews()
        // Two tiles a row, like the web app's grid on a phone.
        for (pair in videos.take(PREVIEW).chunked(2)) {
            grid.addView(context.row {
                gravity = Gravity.TOP
                pair.forEachIndexed { i, video ->
                    addView(VideoTiles.create(context).also { VideoTiles.bind(it, video) }, fill().apply {
                        if (i == 1) marginStart = context.dp(12)
                    })
                }
                if (pair.size == 1) addView(View(context), fill().apply { marginStart = context.dp(12) })
            }, context.spaced(12))
        }
    }

    private companion object {
        const val PREVIEW = 4
    }
}
