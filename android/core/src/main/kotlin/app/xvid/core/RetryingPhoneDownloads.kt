package app.xvid.core

/**
 * Phone downloads that cope with the ways they fail (see [FailureKind]):
 * - no connection: fails now, and the post waits to be retried by [retryWaiting]
 *   when the connection is back (up to [MAX_CONNECTION_RETRIES] times);
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
            val result = attempt(waiting.url, onProgress)
            // Only tries that failed while the phone said it was online count towards giving up.
            val retries = if (network.isOnline()) waiting.retries + 1 else waiting.retries
            val retryAgain = result.waitForConnection && retries < MAX_CONNECTION_RETRIES
            synchronized(this) {
                val others = load().filterNot { it.url == waiting.url }
                save(if (retryAgain) others + waiting.copy(retries = retries) else others)
            }
            when {
                retryAgain -> Unit
                result.waitForConnection -> onResult(PhoneDownloadOutcome.Failed(NO_CONNECTION))
                else -> onResult(result.outcome)
            }
        }
        return hasWaiting()
    }

    private class Attempt(val outcome: PhoneDownloadOutcome, val waitForConnection: Boolean = false)

    private fun attempt(text: String, onProgress: (Int?) -> Unit): Attempt {
        val first = updates.using { downloads.download(text, onProgress) }
        val firstFailure = first as? PhoneDownloadOutcome.Failed ?: return Attempt(first)
        if (kindOf(firstFailure) != FailureKind.OTHER) return failed(firstFailure)

        updates.updateAfterFailure()
        val second = updates.using { downloads.download(text, onProgress) }
        val secondFailure = second as? PhoneDownloadOutcome.Failed ?: return Attempt(second)
        return failed(secondFailure)
    }

    private fun failed(failure: PhoneDownloadOutcome.Failed): Attempt = when (kindOf(failure)) {
        FailureKind.NO_CONNECTION -> Attempt(PhoneDownloadOutcome.Failed(WAITING_FOR_CONNECTION), waitForConnection = true)
        FailureKind.NEEDS_LOGIN -> Attempt(PhoneDownloadOutcome.Failed(NEEDS_LOGIN))
        FailureKind.PERMANENT, FailureKind.OTHER -> Attempt(failure)
    }

    private fun kindOf(failure: PhoneDownloadOutcome.Failed) = Failures.classify(failure.reason, network.isOnline())

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
