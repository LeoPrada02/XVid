package app.xvid.core

import java.io.File

// The small interfaces the core module depends on. The Android app provides
// the real implementations; tests provide fakes.

/** Runs yt-dlp (with ffmpeg) to download a post's videos. */
interface DownloadEngine {
    /**
     * Downloads every video of the post at [EngineRequest.url] into
     * [EngineRequest.outputDir] and returns the files it wrote.
     * [onProgress] gets a percentage, or a negative number when unknown.
     * Throws [EngineError] when the download fails.
     */
    fun download(request: EngineRequest, onProgress: (Float) -> Unit): List<File>
}

/**
 * What to download, in which yt-dlp format, and where to put the files.
 * [cookies] is the phone's X login as a Netscape cookies file, or null when logged out;
 * [userAgent] the browser identity that login was made with, for yt-dlp to send too.
 */
data class EngineRequest(
    val url: String,
    val format: String,
    val outputDir: File,
    val cookies: String? = null,
    val userAgent: String? = null,
)

/** A failed engine run. The message is yt-dlp's error output. */
class EngineError(message: String) : Exception(message)

/** The phone library folder (Movies/XVid), visible in the gallery. */
interface PhoneLibrary {
    /** Copies [file] into the phone library as [name]. */
    fun add(file: File, name: String): PhoneVideo
}

/** A video in the phone library. [id] is whatever the platform uses to open it. */
data class PhoneVideo(val id: String, val name: String)

/** Whether the phone currently has a connection. */
interface NetworkState {
    fun isOnline(): Boolean
}

/** Small persistent key-value storage private to the phone app. */
interface Storage {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Wall-clock time, in milliseconds since the epoch. */
interface Clock {
    fun now(): Long
}

/** The phone app's GitHub Releases. */
interface ReleaseFeed {
    /** The latest published release, or null if there is none. Throws when it can't be checked. */
    fun latest(): Release?
}

/** A published release: its version tag (e.g. v1.2.0) and the page to get it from. */
data class Release(val tag: String, val url: String)
