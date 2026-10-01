package app.xvid

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.xvid.core.Pc
import app.xvid.core.PcVideo
import app.xvid.core.PhoneLibraryVideo
import app.xvid.core.VideoTypes
import java.io.File
import java.util.concurrent.Executors

/**
 * Phone library and PC library videos as tiles like the web app's library grid: a 16:9
 * thumbnail, the name on up to two lines and when it was saved. Thumbnails load in the
 * background and are cached for the whole app, so the main screen and the library screens share them.
 */
object VideoTiles {
    private val loader = Executors.newSingleThreadExecutor { Thread(it, "thumbnails").apply { isDaemon = true } }
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun create(context: Context): LinearLayout = context.column {
        background = context.rounded(context.color(R.color.surface), context.color(R.color.border))
        clipToOutline = true
        isClickable = true
        addView(WideImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(context.color(R.color.border))
        })
        addView(context.column {
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(10))
            addView(context.text(TextStyle.BODY).apply {
                textSize = 14f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(context.text(TextStyle.SMALL).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, context.spaced(4))
        })
    }

    /** A phone library video: tapping plays it on the phone. */
    fun bind(tile: LinearLayout, video: PhoneLibraryVideo) {
        val browser = (tile.context.applicationContext as XVidApp).phoneLibrary
        bind(tile, video.name, video.addedAt, video.sizeBytes, key = video.id, thumbnail = { browser.thumbnail(video) }) {
            play(tile.context, video)
        }
    }

    /** A video in [pc]'s PC library: tapping streams it from the PC. */
    fun bind(tile: LinearLayout, pc: Pc, video: PcVideo) {
        val libraries = (tile.context.applicationContext as XVidApp).pcLibraries
        val key = "${pc.id}/${video.name}/${video.addedAt}"
        bind(tile, video.title, video.addedAt, video.sizeBytes, key, thumbnail = { libraries.thumbnail(pc, video) }) {
            PcVideoActivity.open(tile.context, pc, video)
        }
    }

    private fun bind(
        tile: LinearLayout,
        title: String,
        addedAt: Long,
        sizeBytes: Long,
        key: String,
        thumbnail: () -> File?,
        onClick: () -> Unit,
    ) {
        val context = tile.context
        val image = tile.getChildAt(0) as ImageView
        val body = tile.getChildAt(1) as LinearLayout
        (body.getChildAt(0) as TextView).text = title
        (body.getChildAt(1) as TextView).text = meta(context, addedAt, sizeBytes)
        image.contentDescription = title
        tile.setOnClickListener { onClick() }
        image.tag = key
        cache.get(key)?.let {
            image.setImageBitmap(it)
            return
        }
        image.setImageDrawable(null)
        loader.execute {
            val bitmap = runCatching { thumbnail()?.let { BitmapFactory.decodeFile(it.path) } }.getOrNull()
                ?: return@execute
            cache.put(key, bitmap)
            image.post { if (image.tag == key) image.setImageBitmap(bitmap) }
        }
    }

    /** When a video was saved and its size, like "5 min. ago · 12 MB". */
    fun meta(context: Context, addedAt: Long, sizeBytes: Long): String = context.getString(
        R.string.library_tile_meta,
        DateUtils.getRelativeTimeSpanString(addedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE),
        Formatter.formatShortFileSize(context, sizeBytes),
    )

    /** Adds [tiles] to [grid] two a row, like the web app's grid on a phone. */
    fun addInPairs(grid: LinearLayout, tiles: List<View>) {
        val context = grid.context
        for (pair in tiles.chunked(2)) {
            grid.addView(context.row {
                gravity = Gravity.TOP
                pair.forEachIndexed { i, tile ->
                    addView(tile, fill().apply { if (i == 1) marginStart = context.dp(12) })
                }
                if (pair.size == 1) addView(View(context), fill().apply { marginStart = context.dp(12) })
            }, context.spaced(12))
        }
    }

    /** Plays [video] on the phone, with no network. */
    fun play(context: Context, video: PhoneLibraryVideo) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(video.id), VideoTypes.mimeTypeOf(video.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.library_no_player, Toast.LENGTH_LONG).show()
        }
    }
}

/** A thumbnail 16:9 as wide as its tile, like `.thumb`. */
private class WideImageView(context: Context) : ImageView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width * 9 / 16)
    }
}
