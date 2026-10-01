package app.xvid.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Stands in for yt-dlp. Each call writes [videosPerPost] files into the output
 * folder (or throws [failWith] as an [EngineError], or [crashWith] as it is)
 * and remembers the request it got.
 */
class FakeEngine(
    var videosPerPost: Int = 1,
    var failWith: String? = null,
    var crashWith: Exception? = null,
    var interruptWith: Error? = null,
    var progressSteps: List<Float> = listOf(0f, 50f, 100f),
) : DownloadEngine {
    val requests = mutableListOf<EngineRequest>()

    override fun download(request: EngineRequest, onProgress: (Float) -> Unit): List<File> {
        requests += request
        progressSteps.forEach(onProgress)
        failWith?.let { throw EngineError(it) }
        crashWith?.let { throw it }
        interruptWith?.let { throw it }
        request.outputDir.mkdirs()
        return (1..videosPerPost).map { n ->
            File(request.outputDir, "video$n.mp4").apply { writeText("video $n of ${request.url}") }
        }
    }
}

/** The phone library folder, kept in memory: name -> file contents. A video is only there once fully written. */
class FakePhoneLibrary(var failWith: String? = null) : PhoneLibrary {
    val videos = linkedMapOf<String, String>()

    override fun write(name: String, content: (OutputStream) -> Unit): PhoneVideo {
        failWith?.let { throw IllegalStateException(it) }
        val bytes = ByteArrayOutputStream().also(content)
        videos[name] = bytes.toString(Charsets.UTF_8)
        return PhoneVideo(id = "video:$name", name = name)
    }
}

/** A video on the phone, in memory, to upload. Its "thumbnail" is a frame of [content], unless [noFrame]. */
class FakePhoneVideoFile(
    override val name: String,
    private val content: String,
    private val noFrame: Boolean = false,
    private val unreadable: Boolean = false,
) : PhoneVideoFile {
    override val sizeBytes: Long get() = content.length.toLong()

    override fun open(): InputStream =
        if (unreadable) throw java.io.FileNotFoundException("permission revoked") else content.byteInputStream()

    override fun writeThumbnail(target: File): Double? {
        if (noFrame) throw IllegalStateException("no frame in $name")
        target.writeText("frame of $name")
        return 12.0
    }
}

/** Grabs a "frame" of a PC library video: writes its address to the target, or throws [failWith]. */
class FakeFrameGrabber(var failWith: String? = null, var lengthSeconds: Double? = 42.0) : PcFrameGrabber {
    val grabbed = mutableListOf<String>()

    override fun grab(stream: PcStream, target: File): Double? {
        failWith?.let { throw IllegalStateException(it) }
        grabbed += stream.url
        target.writeText("frame of ${stream.url}")
        return lengthSeconds
    }
}

/** The PCs found on the local network, as a fixed answer (or a failure). */
class FakeDiscovery(var found: List<FoundPc> = emptyList(), var failWith: String? = null) : PcDiscovery {
    override fun find(): List<FoundPc> {
        failWith?.let { throw IllegalStateException(it) }
        return found
    }
}

class FakeNetwork(var online: Boolean = true) : NetworkState {
    override fun isOnline() = online
}

/** GitHub Releases, as a fixed answer (or a failure). */
class FakeReleaseFeed(var latest: Release? = null, var failWith: String? = null) : ReleaseFeed {
    var calls = 0

    override fun latest(): Release? {
        calls++
        failWith?.let { throw java.io.IOException(it) }
        return latest
    }
}

class FakeStorage : Storage {
    val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class FakeClock(var millis: Long = 1_000_000_000_000) : Clock {
    override fun now() = millis

    fun advanceHours(hours: Long) {
        millis += hours * 60 * 60 * 1000
    }
}
