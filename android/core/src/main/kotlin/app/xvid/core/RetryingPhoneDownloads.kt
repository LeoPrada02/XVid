package app.xvid.core

/**
 * Phone downloads that cope with the ways they fail (see [FailureKind]):
 * - no connection: fails now, and the post waits to be retried by [retryWaiting]
 *   when the connection is back (up to [MAX_CONNECTION_RETRIES] times, counting
 *   retries the app was stopped in the middle of);
 * - something a newer yt-dlp might fix: updates yt-dlp and retries once;
 * - needs login or permanent: fails with the reason, never retried.
 *
 * Posts waiting for the connection are kept in [storage], so they survive the
 * app being closed. Blocking; callers run it off the main thread.
 */
class RetryingPhoneDownloads(
    private val downloads: PhoneDownloads,
    private val network: NetworkState,
    private val updates: YtDlpUpdates,
    private val storage: Storage,
) {
    /** Downloads the post linked in [sharedText]. A failure's reason says whether it will be retried. */
    fun download(sharedText: String, onProgress: (Int?) -> Unit = {}): PhoneDownloadOutcome {
        val result = attempt(sharedText, onProgress)
        if (result.waitForConnection) {
            val url = XPostLink.find(sharedText)?.url ?: return result.outcome
            synchronized(this) { save(load().filterNot { it.url == url } + Waiting(url, retries = 0)) }
        }
        return result.outcome
    }

    /** Whether some post is waiting for the connection. */
    @Synchronized
    fun hasWaiting(): Boolean = load().isNotEmpty()

    /**
     * Retries the posts waiting for the connection, if the phone is online.
     * [onResult] gets each one that finished, saved or failed for good; posts
     * that still can't connect keep waiting quietly. Returns whether some post
     * is still waiting.
     */
    fun retryWaiting(
        onProgress: (Int?) -> Unit = {},
        onResult: (PhoneDownloadOutcome) -> Unit,
    ): Boolean {
        if (!network.isOnline()) return hasWaiting()
        for (waiting in synchronized(this) { load() }) {
            if (waiting.retries >= MAX_CONNECTION_RETRIES) {
                replace(waiting, null)
                onResult(PhoneDownloadOutcome.Failed(NO_CONNECTION))
                continue
            }
            // Counted before trying, so a retry Android stops half way (the app killed, the
            // background time used up) still counts, and a post that never finishes gives up.
            replace(waiting, waiting.copy(retries = waiting.retries + 1))
            val result = attempt(waiting.url, onProgress)
            // Only tries that failed while the phone said it was online count towards giving up.
            val retries = if (result.online) waiting.retries + 1 else waiting.retries
            val retryAgain = result.waitForConnection && retries < MAX_CONNECTION_RETRIES
            replace(waiting, if (retryAgain) waiting.copy(retries = retries) else null)
            when {
                retryAgain -> Unit
                result.waitForConnection -> onResult(PhoneDownloadOutcome.Failed(NO_CONNECTION))
                else -> onResult(result.outcome)
            }
        }
        return hasWaiting()
    }

    /** Replaces [waiting] in the stored list with [replacement], or removes it. */
    @Synchronized
    private fun replace(waiting: Waiting, replacement: Waiting?) {
        val others = load().filterNot { it.url == waiting.url }
        save(if (replacement != null) others + replacement else others)
    }

    /** [online]: whether the phone had a connection when the last download of this attempt ended. */
    private class Attempt(val outcome: PhoneDownloadOutcome, val online: Boolean, val waitForConnection: Boolean = false)

    private fun attempt(text: String, onProgress: (Int?) -> Unit): Attempt {
        val first = downloadOnce(text, onProgress)
        val firstFailure = first.outcome as? PhoneDownloadOutcome.Failed ?: return first
        if (Failures.classify(firstFailure.reason, first.online) != FailureKind.OTHER) {
            return failed(firstFailure, first.online)
        }

        updates.updateAfterFailure()
        val second = downloadOnce(text, onProgress)
        val secondFailure = second.outcome as? PhoneDownloadOutcome.Failed ?: return second
        return failed(secondFailure, second.online)
    }

    /** One download, noting the connection right when it ended: its failure is sorted by that. */
    private fun downloadOnce(text: String, onProgress: (Int?) -> Unit): Attempt {
        val outcome = updates.using { downloads.download(text, onProgress) }
        return Attempt(outcome, network.isOnline())
    }

    private fun failed(failure: PhoneDownloadOutcome.Failed, online: Boolean): Attempt =
        when (Failures.classify(failure.reason, online)) {
            FailureKind.NO_CONNECTION ->
                Attempt(PhoneDownloadOutcome.Failed(WAITING_FOR_CONNECTION), online, waitForConnection = true)
            // Keeps yt-dlp's reason too: logging in to X comes in a later version.
            FailureKind.NEEDS_LOGIN -> Attempt(PhoneDownloadOutcome.Failed("$NEEDS_LOGIN: ${failure.reason}"), online)
            FailureKind.PERMANENT, FailureKind.OTHER -> Attempt(failure, online)
        }

    /** A post waiting for the connection, and how many times it was retried while online. */
    private data class Waiting(val url: String, val retries: Int)

    // One "retries url" line per post. Post URLs have no spaces or line breaks.
    private fun load(): List<Waiting> = storage.get(WAITING).orEmpty().lines().mapNotNull { line ->
        val (retries, url) = line.split(' ', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        Waiting(url, retries.toIntOrNull() ?: return@mapNotNull null)
    }

    private fun save(waiting: List<Waiting>) {
        storage.put(WAITING, waiting.joinToString("\n") { "${it.retries} ${it.url}" }.ifEmpty { null })
    }

    companion object {
        /** How many times a post is retried while the phone says it's online but it still can't connect. */
        const val MAX_CONNECTION_RETRIES = 5

        private const val WAITING = "downloads.waitingForConnection"
        private const val NO_CONNECTION = "No internet connection"
        private const val WAITING_FOR_CONNECTION = "$NO_CONNECTION. XVid will try again when the connection is back"
        private const val NEEDS_LOGIN = "This post needs an X login"
    }
}
