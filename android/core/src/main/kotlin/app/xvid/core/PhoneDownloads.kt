package app.xvid.core

import java.io.File

/**
 * Phone downloads: an X post shared to the phone app is downloaded on the
 * phone and every video in it is saved to the phone library.
 *
 * Blocking; callers run it off the main thread.
 */
class PhoneDownloads(
    private val engine: DownloadEngine,
    private val library: PhoneLibrary,
    private val network: NetworkState,
    /** Scratch folder for the engine's files before they reach the phone library. */
    private val workDir: File,
    /** Read at the start of each phone download, so a new choice applies to the next one. */
    private val maximumQuality: () -> MaximumQuality = { MaximumQuality.BEST },
    /** The X login's cookies (see [XLogin.cookies]), read at the start of each phone download. */
    private val xLogin: () -> String? = { null },
) {
    /**
     * Downloads the post linked in [sharedText]. [onProgress] gets a
     * percentage while downloading, or null when it isn't known.
     */
    fun download(sharedText: String, onProgress: (Int?) -> Unit = {}): PhoneDownloadOutcome {
        val link = XPostLink.find(sharedText)
            ?: return PhoneDownloadOutcome.Failed("That isn't a link to an X post")
        if (!network.isOnline()) return PhoneDownloadOutcome.Failed("No internet connection")

        val jobDir = File(workDir, "download-${link.statusId}-${System.nanoTime()}")
        try {
            val files = try {
                engine.download(EngineRequest(link.url, maximumQuality().format, jobDir, xLogin())) { percent ->
                    onProgress(if (percent < 0f) null else percent.toInt().coerceIn(0, 100))
                }
            } catch (e: EngineError) {
                return PhoneDownloadOutcome.Failed(reasonFrom(e.message))
            }
            if (files.isEmpty()) return PhoneDownloadOutcome.Failed("No video in this post")

            val videos = try {
                files.sortedBy { it.name }.mapIndexed { index, file ->
                    val number = if (files.size > 1) "_${index + 1}" else ""
                    library.add(file, "XVid_${link.statusId}$number.${file.extension.ifEmpty { "mp4" }}")
                }
            } catch (e: Exception) {
                return PhoneDownloadOutcome.Failed("Couldn't save the video: ${describe(e)}")
            }
            return PhoneDownloadOutcome.Saved(videos)
        } catch (e: Exception) {
            return PhoneDownloadOutcome.Failed("The download failed: ${describe(e)}")
        } finally {
            jobDir.deleteRecursively()
        }
    }

    private companion object {
        fun describe(e: Exception) = e.message ?: e.javaClass.simpleName

        private val extractorPrefix = Regex("""^\[[^\]]+]\s*(?:[^:\s]+:\s*)?""")

        /** Turns yt-dlp's error output into one readable line. */
        fun reasonFrom(message: String?): String {
            val lines = message.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
            val error = lines.lastOrNull { it.startsWith("ERROR:") } ?: lines.lastOrNull()
                ?: return "The download failed"
            return error.removePrefix("ERROR:").trim().replace(extractorPrefix, "").ifEmpty { "The download failed" }
        }
    }
}

sealed interface PhoneDownloadOutcome {
    /** Every video of the post is in the phone library. */
    data class Saved(val videos: List<PhoneVideo>) : PhoneDownloadOutcome

    /** Nothing was saved; [reason] is shown to the user. */
    data class Failed(val reason: String) : PhoneDownloadOutcome

    /**
     * Nothing was saved because the post needs an X login (or the login expired);
     * [reason] is yt-dlp's. The post is retried after logging in.
     */
    data class NeedsLogin(val reason: String) : PhoneDownloadOutcome
}
