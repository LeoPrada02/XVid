package app.xvid

import android.content.Context
import app.xvid.core.DownloadEngine
import app.xvid.core.EngineError
import app.xvid.core.EngineRequest
import app.xvid.core.VideoTypes
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** The download engine: yt-dlp and ffmpeg bundled by youtubedl-android. */
class YoutubeDlEngine(private val context: Context) : DownloadEngine {
    @Volatile
    private var ready = false

    override fun download(request: EngineRequest, onProgress: (Float) -> Unit): List<File> {
        ensureReady()
        request.outputDir.mkdirs()
        val ytRequest = YoutubeDLRequest(request.url).apply {
            addOption("-f", request.format)
            // Merge the best video and audio streams into one mp4 with ffmpeg.
            addOption("--merge-output-format", "mp4")
            // Posts with several videos come back as a playlist: get all of them.
            addOption("--yes-playlist")
            addOption("--no-mtime")
            addOption("-P", request.outputDir.absolutePath)
            addOption("-o", "%(autonumber)s.%(ext)s")
        }
        try {
            YoutubeDL.getInstance().execute(ytRequest, null) { progress, _, _ -> onProgress(progress) }
        } catch (e: Exception) {
            throw EngineError(e.message ?: e.javaClass.simpleName)
        }
        return request.outputDir.listFiles().orEmpty()
            .filter { it.isFile && VideoTypes.isVideo(it) }
    }

    @Synchronized
    private fun ensureReady() {
        if (ready) return
        try {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
        } catch (e: Exception) {
            throw EngineError("Couldn't start the downloader: ${e.message}")
        }
        ready = true
    }
}
