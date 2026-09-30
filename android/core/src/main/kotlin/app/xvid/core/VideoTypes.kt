package app.xvid.core

import java.io.File

/** The video file types the phone app handles, by file extension. */
object VideoTypes {
    private val mimeTypes = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
    )

    fun isVideo(file: File): Boolean = file.extension.lowercase() in mimeTypes

    /** The mime type for a video file name; mp4 when the extension isn't known. */
    fun mimeTypeOf(name: String): String = mimeTypes[name.substringAfterLast('.', "").lowercase()] ?: "video/mp4"
}
