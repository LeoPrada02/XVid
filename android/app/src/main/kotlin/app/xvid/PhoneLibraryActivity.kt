package app.xvid

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import app.xvid.core.PhoneLibraryVideo
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The phone library: the videos in Movies/XVid, newest first, as tiles
 * like the web app's library (see [VideoTiles]). Tapping one plays the file on the phone, with no network.
 * The list is read again whenever the screen comes back or the folder changes.
 */
class PhoneLibraryActivity : Activity() {
    private val browser get() = (application as XVidApp).phoneLibrary
    private val mainThread = Handler(Looper.getMainLooper())
    private lateinit var listing: ExecutorService
    private val adapter = VideoAdapter()
    private lateinit var status: TextView

    private val reload = Runnable { load() }
    private val folderChanges = object : ContentObserver(mainThread) {
        override fun onChange(selfChange: Boolean) {
            mainThread.removeCallbacks(reload)
            mainThread.postDelayed(reload, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listing = Executors.newSingleThreadExecutor()
        status = text(TextStyle.SMALL)
        val grid = ZoomableGrid(this, "phoneLibrary", adapter)
        setContentView(
            column {
                setBackgroundColor(color(R.color.bg))
                setPadding(dp(16), dp(24), dp(16), 0)
                addView(row {
                    addView(text(TextStyle.TITLE, R.string.library_title), fill())
                    addView(button(R.string.upload_from_gallery, ButtonStyle.SECONDARY, small = true) { UploadActivity.pickFromGallery(this@PhoneLibraryActivity) })
                })
                addView(text(TextStyle.SMALL, R.string.library_hold_to_upload), spaced(4))
                addView(status, spaced(4))
                addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
        val missing = PhoneLibraryPermissions.missing(this)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, folderChanges)
        load()
    }

    override fun onPause() {
        contentResolver.unregisterContentObserver(folderChanges)
        mainThread.removeCallbacks(reload)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        load()
    }

    override fun onDestroy() {
        listing.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        if (adapter.count == 0) status.setText(R.string.library_loading)
        listing.execute {
            val result = runCatching { browser.videos() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { videos ->
                    adapter.show(videos)
                    status.text = if (videos.isEmpty()) {
                        getString(R.string.library_empty)
                    } else {
                        resources.getQuantityString(R.plurals.library_count, videos.size, videos.size)
                    }
                }.onFailure { e ->
                    status.text = getString(R.string.library_unreadable, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private inner class VideoAdapter : BaseAdapter() {
        private var videos: List<PhoneLibraryVideo> = emptyList()

        fun show(videos: List<PhoneLibraryVideo>) {
            this.videos = videos
            notifyDataSetChanged()
        }

        override fun getCount() = videos.size

        override fun getItem(position: Int) = videos[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tile = convertView as? LinearLayout ?: VideoTiles.create(this@PhoneLibraryActivity)
            VideoTiles.bind(tile, videos[position])
            VideoTiles.fit(tile, (parent as? GridView)?.numColumns ?: 2)
            return tile
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, PhoneLibraryActivity::class.java))
        }
    }
}

/**
 * Reading Movies/XVid. The app always sees the videos it saved itself; this
 * lets it also see ones saved before a reinstall.
 */
object PhoneLibraryPermissions {
    /** What to ask for; empty once any of them is granted (Android 14 lets the user grant only some videos). */
    fun missing(context: Context): List<String> {
        val wanted = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.READ_MEDIA_VIDEO)
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val granted = wanted.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return if (granted) emptyList() else wanted
    }
}
