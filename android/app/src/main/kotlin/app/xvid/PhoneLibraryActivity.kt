package app.xvid

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.xvid.core.PhoneLibraryVideo
import app.xvid.core.VideoTypes
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The phone library: the videos in Movies/XVid, newest first, with
 * thumbnails. Tapping one plays the file on the phone, with no network.
 * The list is read again whenever the screen comes back or the folder changes.
 */
class PhoneLibraryActivity : Activity() {
    private val browser get() = (application as XVidApp).phoneLibrary
    private val mainThread = Handler(Looper.getMainLooper())
    private lateinit var listing: ExecutorService
    private lateinit var thumbnailLoader: ExecutorService
    private val thumbnailCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val adapter = VideoAdapter()
    private lateinit var status: TextView
    private lateinit var grid: GridView

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
        thumbnailLoader = Executors.newSingleThreadExecutor()
        val padding = dp(16)
        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(16), 0, 0)
        }
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(112)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(8)
            verticalSpacing = dp(8)
            setPadding(0, dp(16), 0, 0)
            clipToPadding = false
            adapter = this@PhoneLibraryActivity.adapter
            setOnItemClickListener { _, _, position, _ -> play(this@PhoneLibraryActivity.adapter.getItem(position)) }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding * 2, padding, 0)
                addView(TextView(context).apply {
                    setText(R.string.library_title)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                })
                addView(status)
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
        thumbnailLoader.shutdownNow()
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
                    status.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
                    status.setText(R.string.library_empty)
                }.onFailure { e ->
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.library_unreadable, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun play(video: PhoneLibraryVideo) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(video.id), VideoTypes.mimeTypeOf(video.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.library_no_player, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadThumbnail(video: PhoneLibraryVideo, into: ImageView) {
        thumbnailCache.get(video.id)?.let {
            into.setImageBitmap(it)
            return
        }
        into.setImageDrawable(null)
        thumbnailLoader.execute {
            val bitmap = browser.thumbnail(video)?.let { BitmapFactory.decodeFile(it.path) } ?: return@execute
            runOnUiThread {
                thumbnailCache.put(video.id, bitmap)
                if (into.tag == video.id) into.setImageBitmap(bitmap)
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
            val cell = convertView as? LinearLayout ?: newCell()
            val video = videos[position]
            val image = cell.getChildAt(0) as ImageView
            image.tag = video.id
            image.contentDescription = video.name
            (cell.getChildAt(1) as TextView).text = video.name
            loadThumbnail(video, image)
            return cell
        }

        private fun newCell() = LinearLayout(this@PhoneLibraryActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(SquareImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.LTGRAY)
            })
            addView(TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, PhoneLibraryActivity::class.java))
        }
    }
}

/** A thumbnail tile as tall as it is wide. */
private class SquareImageView(context: Context) : ImageView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
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
