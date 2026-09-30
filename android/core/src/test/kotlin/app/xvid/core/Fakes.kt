package app.xvid.core

import java.io.File

/**
 * Stands in for yt-dlp. Each call writes [videosPerPost] files into the output
 * folder (or throws [failWith] as an [EngineError], or [crashWith] as it is)
 * and remembers the request it got.
 */
class FakeEngine(
    var videosPerPost: Int = 1,
    var failWith: String? = null,
    var crashWith: Exception? = null,
    var progressSteps: List<Float> = listOf(0f, 50f, 100f),
) : DownloadEngine {
    val requests = mutableListOf<EngineRequest>()

    override fun download(request: EngineRequest, onProgress: (Float) -> Unit): List<File> {
        requests += request
        progressSteps.forEach(onProgress)
        failWith?.let { throw EngineError(it) }
        crashWith?.let { throw it }
        request.outputDir.mkdirs()
        return (1..videosPerPost).map { n ->
            File(request.outputDir, "video$n.mp4").apply { writeText("video $n of ${request.url}") }
        }
    }
}

/** The phone library folder, kept in memory: name -> file contents. */
class FakePhoneLibrary(var failWith: String? = null) : PhoneLibrary {
    val videos = linkedMapOf<String, String>()

    override fun add(file: File, name: String): PhoneVideo {
        failWith?.let { throw IllegalStateException(it) }
        videos[name] = file.readText()
        return PhoneVideo(id = "video:$name", name = name)
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
