package app.xvid

import android.content.Context
import android.widget.LinearLayout
import app.xvid.core.Pc
import app.xvid.core.PcLibraryException
import app.xvid.core.PcVideo
import java.util.concurrent.Executors

/**
 * A reachable PC's PC library in its section of the main screen, like [PhoneLibrarySection]: the
 * newest videos as tiles, and "See all", which opens [PcLibraryActivity].
 */
class PcLibraryPreview(context: Context, private val pc: Pc) : LinearLayout(context) {
    private val summary = context.text(TextStyle.SMALL, context.getString(R.string.library_loading))
    private val grid = context.column()

    init {
        orientation = VERTICAL
        addView(context.row {
            addView(summary, fill())
            addView(context.button(R.string.library_see_all, ButtonStyle.LINK) { PcLibraryActivity.open(context, pc) })
        })
        addView(grid)
        load()
    }

    private fun load() {
        val libraries = (context.applicationContext as XVidApp).pcLibraries
        listing.execute {
            val result = runCatching { libraries.videos(pc) }
            post {
                if (!isAttachedToWindow) return@post // the PCs were shown again meanwhile
                result.onSuccess { videos ->
                    summary.text = summary(context, videos)
                    grid.removeAllViews()
                    VideoTiles.addInPairs(grid, videos.take(PREVIEW).map { VideoTiles.create(context).also { tile -> VideoTiles.bind(tile, pc, it) } })
                }.onFailure { summary.text = failure(context, it) }
            }
        }
    }

    companion object {
        private const val PREVIEW = 4

        /** One at a time for the whole app, so refreshing the PCs doesn't pile up listings. */
        private val listing = Executors.newSingleThreadExecutor { Thread(it, "pc-libraries").apply { isDaemon = true } }

        fun summary(context: Context, videos: List<PcVideo>): String =
            if (videos.isEmpty()) {
                context.getString(R.string.pc_library_empty)
            } else {
                context.resources.getQuantityString(R.plurals.pc_library_count, videos.size, videos.size)
            }

        fun failure(context: Context, error: Throwable): String =
            (error as? PcLibraryException)?.message ?: context.getString(R.string.pc_library_failed)
    }
}
