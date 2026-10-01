package app.xvid

import android.content.Context
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
            resources.getQuantityString(R.plurals.library_count, videos.size, videos.size) + "\n" +
                context.getString(R.string.library_hold_to_upload)
        }
        grid.removeAllViews()
        VideoTiles.addInPairs(grid, videos.take(PREVIEW).map { video -> VideoTiles.create(context).also { VideoTiles.bind(it, video) } })
    }

    private companion object {
        const val PREVIEW = 4
    }
}
