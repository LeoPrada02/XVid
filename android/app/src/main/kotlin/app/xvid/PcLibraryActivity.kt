package app.xvid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import app.xvid.core.Pc
import app.xvid.core.PcVideo
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One PC's PC library, newest first, as tiles like the phone library (see [VideoTiles]).
 * Tapping a video streams it from the PC ([PcVideoActivity]). Read again whenever the screen
 * comes back, e.g. after deleting a video.
 */
class PcLibraryActivity : Activity() {
    private val app get() = application as XVidApp
    private lateinit var pc: Pc
    private lateinit var listing: ExecutorService
    private val adapter = VideoAdapter()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pc = intent.pc(app) ?: return finish()
        listing = Executors.newSingleThreadExecutor()
        status = text(TextStyle.SMALL)
        val grid = ZoomableGrid(this, "pcLibrary", adapter)
        setContentView(pullToReload(
            column {
                setBackgroundColor(color(R.color.bg))
                setPadding(dp(16), dp(24), dp(16), 0)
                addView(text(TextStyle.TITLE, getString(R.string.pc_library_title, pc.name)))
                addView(status, spaced(4))
                addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            },
            scrollsUp = { grid.canScrollVertically(-1) },
        ) { done -> load(done) })
    }

    override fun onResume() {
        super.onResume()
        if (::listing.isInitialized) load()
    }

    override fun onDestroy() {
        if (::listing.isInitialized) listing.shutdownNow()
        super.onDestroy()
    }

    /** Lists the library again, then calls [done]. */
    private fun load(done: () -> Unit = {}) {
        if (adapter.count == 0) status.setText(R.string.library_loading)
        listing.execute {
            val result = runCatching { app.pcLibraries.videos(pc) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                done()
                result.onSuccess { videos ->
                    adapter.show(videos)
                    status.text = PcLibraryPreview.summary(this, videos)
                }.onFailure { status.text = PcLibraryPreview.failure(this, it) }
            }
        }
    }

    private inner class VideoAdapter : BaseAdapter() {
        private var videos: List<PcVideo> = emptyList()

        fun show(videos: List<PcVideo>) {
            this.videos = videos
            notifyDataSetChanged()
        }

        override fun getCount() = videos.size

        override fun getItem(position: Int) = videos[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tile = convertView as? LinearLayout ?: VideoTiles.create(this@PcLibraryActivity)
            VideoTiles.bind(tile, pc, videos[position])
            VideoTiles.fit(tile, (parent as? GridView)?.numColumns ?: 2)
            return tile
        }
    }

    companion object {
        fun open(context: Context, pc: Pc) {
            context.startActivity(Intent(context, PcLibraryActivity::class.java).putPc(pc))
        }
    }
}

