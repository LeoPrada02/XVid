package app.xvid.core

import java.io.File
import java.security.MessageDigest

/**
 * The phone library section: the videos in the phone library folder, newest
 * first, with thumbnails the app makes itself.
 *
 * Every listing reads the folder itself, so there's no separate record that
 * could go stale: a video deleted from the gallery (or anywhere else) is gone
 * the next time the library is listed. Thumbnails are only a cache, keyed by
 * the video, and those of videos no longer in the folder are removed.
 *
 * Blocking; callers run it off the main thread.
 */
class PhoneLibraryBrowser(
    private val folder: PhoneLibraryFolder,
    private val thumbnails: ThumbnailMaker,
    /** Private cache folder for the thumbnails. */
    private val thumbnailDir: File,
) {
    /** The phone library's videos, newest first. Throws when the folder can't be read. */
    @Synchronized
    fun videos(): List<PhoneLibraryVideo> {
        val videos = folder.videos()
            .filter { VideoTypes.isVideo(File(it.name)) && folder.exists(it) }
            .sortedWith(compareByDescending<PhoneLibraryVideo> { it.addedAt }.thenBy { it.name })
        removeThumbnailsExcept(videos.map { thumbnailName(it) }.toSet())
        return videos
    }

    /** [video]'s thumbnail, made the first time it's asked for; null when it can't be made. */
    @Synchronized
    fun thumbnail(video: PhoneLibraryVideo): File? {
        val file = File(thumbnailDir, thumbnailName(video))
        if (file.isFile) return file
        thumbnailDir.mkdirs()
        val part = File(thumbnailDir, "${file.name}.part")
        return try {
            thumbnails.make(video, part)
            if (part.length() > 0 && part.renameTo(file)) file else null
        } catch (e: Exception) {
            null
        } finally {
            part.delete()
        }
    }

    private fun removeThumbnailsExcept(keep: Set<String>) {
        thumbnailDir.listFiles().orEmpty()
            .filter { it.name !in keep }
            .forEach { it.delete() }
    }

    private companion object {
        /** Changes when the video is replaced by another with the same name. */
        fun thumbnailName(video: PhoneLibraryVideo): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${video.id}\n${video.addedAt}\n${video.sizeBytes}".toByteArray())
            return digest.take(16).joinToString("") { "%02x".format(it) } + ".jpg"
        }
    }
}
