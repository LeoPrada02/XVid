package app.xvid.core

import java.io.File

// The ports the phone library section depends on (see Ports.kt for the others).

/**
 * Reads the phone library folder (Movies/XVid) as it is right now. On the
 * phone this is the media store's view of the folder.
 */
interface PhoneLibraryFolder {
    /** Every file the folder's index lists, in any order. Throws when the folder can't be read. */
    fun videos(): List<PhoneLibraryVideo>

    /**
     * Whether [video]'s file is really still there: an index such as the media
     * store can keep listing a file that was deleted outside the app.
     */
    fun exists(video: PhoneLibraryVideo): Boolean
}

/**
 * A video in the phone library folder. [id] is whatever the platform uses to
 * open it, [addedAt] is in milliseconds since the epoch.
 */
data class PhoneLibraryVideo(val id: String, val name: String, val addedAt: Long, val sizeBytes: Long)

/** Makes a thumbnail picture of a video. */
interface ThumbnailMaker {
    /** Writes a small JPEG of a frame of [video] to [target]. Throws when it can't. */
    fun make(video: PhoneLibraryVideo, target: File)
}
