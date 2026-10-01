package app.xvid.core

import java.io.File
import java.io.InputStream

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

/** A video on the phone to Upload: one in the phone library, or anywhere in the gallery. */
interface PhoneVideoFile {
    /** Its file name, which the PC library keeps (made unique there). */
    val name: String

    /** Its size, or 0 when unknown. */
    val sizeBytes: Long

    /** Its contents, from the start. Throws when it can't be read. */
    fun open(): InputStream

    /** Writes a small JPEG of a frame of it to [target]; returns its length in seconds when known. Throws when it can't. */
    fun writeThumbnail(target: File): Double?
}

/** Makes a thumbnail picture of a video in a PC library, reading only the parts of it it needs. */
interface PcFrameGrabber {
    /**
     * Writes a small JPEG of a frame of the video [stream] plays to [target], and returns the
     * video's length in seconds when it's known. Throws when it can't.
     */
    fun grab(stream: PcStream, target: File): Double?
}

/** Makes a thumbnail picture of a video. */
interface ThumbnailMaker {
    /** Writes a small JPEG of a frame of [video] to [target]. Throws when it can't. */
    fun make(video: PhoneLibraryVideo, target: File)
}
