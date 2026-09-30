package app.xvid

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.xvid.core.PhoneLibraryFolder
import app.xvid.core.PhoneLibraryVideo
import app.xvid.core.ThumbnailMaker
import java.io.File
import java.io.FileNotFoundException

/**
 * Reads Movies/XVid through the media store, fresh on every call, so the phone
 * library shows exactly what the gallery shows. [PhoneLibraryVideo.id] is the
 * video's content:// URI.
 */
class MediaStorePhoneLibraryFolder(private val context: Context) : PhoneLibraryFolder {
    @Suppress("DEPRECATION") // DATA is the only way to find the folder on Android 8 and 9.
    override fun videos(): List<PhoneLibraryVideo> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val scoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val legacyFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
        val (selection, args) = if (scoped) {
            "${MediaStore.Video.Media.RELATIVE_PATH} = ?" to arrayOf("${Environment.DIRECTORY_MOVIES}/$FOLDER/")
        } else {
            "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf("${legacyFolder.absolutePath}/%")
        }
        val columns = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATA,
        )
        val cursor = context.contentResolver.query(collection, columns, selection, args, null)
            ?: error("The media store didn't answer")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val path = it.getString(4)
                    // LIKE also matches subfolders; the phone library is the folder itself.
                    if (!scoped && (path == null || File(path).parentFile != legacyFolder)) continue
                    val name = it.getString(1) ?: path?.let { p -> File(p).name } ?: continue
                    add(
                        PhoneLibraryVideo(
                            id = ContentUris.withAppendedId(collection, it.getLong(0)).toString(),
                            name = name,
                            addedAt = it.getLong(2) * 1000,
                            sizeBytes = it.getLong(3),
                        ),
                    )
                }
            }
        }
    }

    override fun exists(video: PhoneLibraryVideo): Boolean = try {
        context.contentResolver.openFileDescriptor(Uri.parse(video.id), "r")?.close()
        true
    } catch (e: FileNotFoundException) {
        false
    } catch (e: Exception) {
        true // Can't tell: better to show it than to hide a video that's there.
    }

    private companion object {
        const val FOLDER = "XVid"
    }
}

/** Makes thumbnails from a frame of the video itself. */
class FrameThumbnailMaker(private val context: Context) : ThumbnailMaker {
    override fun make(video: PhoneLibraryVideo, target: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(video.id))
            val frame = frameAt(retriever, FRAME_AT_US) ?: frameAt(retriever, 0) ?: error("No frame in ${video.name}")
            val scaled = scaleDown(frame)
            target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
        } finally {
            retriever.release()
        }
    }

    private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE, SIZE)
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }

    private fun scaleDown(frame: Bitmap): Bitmap {
        val longest = maxOf(frame.width, frame.height)
        if (longest <= SIZE) return frame
        val scale = SIZE.toFloat() / longest
        return Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
    }

    private companion object {
        const val SIZE = 320
        const val FRAME_AT_US = 1_000_000L
    }
}
