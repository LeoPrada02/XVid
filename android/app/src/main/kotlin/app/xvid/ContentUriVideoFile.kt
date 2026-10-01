package app.xvid

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.xvid.core.PhoneVideoFile
import app.xvid.core.VideoTypes
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** A video on the phone by its content:// URI: one in the phone library, or one picked from the gallery. */
class ContentUriVideoFile(private val context: Context, private val uri: Uri) : PhoneVideoFile {
    override val name: String
    override val sizeBytes: Long

    init {
        var name: String? = null
        var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                name = it.getString(0)
                size = if (it.isNull(1)) 0 else it.getLong(1)
            }
        }
        // PCs only keep video files they know by their extension: add the one its type says, if it lacks it.
        val known = name ?: "video"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(uri))
        this.name = if (!VideoTypes.isVideo(File(known)) && extension in VideoTypes.extensions) "$known.$extension" else known
        sizeBytes = size
    }

    override fun open(): InputStream = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Couldn't read $name")

    override fun writeThumbnail(target: File): Double? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            Frames.write(retriever, target, name)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 }
        } finally {
            retriever.release()
        }
    }
}
