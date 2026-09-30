package app.xvid.core

/**
 * Phone downloads that cope with the ways they fail (see [FailureKind]):
 * - no connection: fails now, and the post waits to be retried by [retryWaiting]
 *   when the connection is back (up to [MAX_CONNECTION_RETRIES] times, counting
 *   retries the app was stopped in the middle of);
 * - something a newer yt-dlp might fix: updates yt-dlp and retries once;
 * - needs login, or an X login that X no longer accepts (expired) even with the
 *   newest yt-dlp: asks for an X login, and the post waits to be retried by
 *   [retryAfterLogin] once the phone is logged in;
 * - permanent: fails with the reason, never retried.
 *
 * Posts waiting for the connection or a login are kept in [storage], so they
 * survive the app being closed. Blocking; callers run it off the main thread.
 */
class RetryingPhoneDownloads(
    private val downloads: PhoneDownloads,
    private val network: NetworkState,
    private val updates: YtDlpUpdates,
    private val storage: Storage,
    /** Whether the phone has an X login (see [XLogin.isLoggedIn]). */
    private val loggedIn: () -> Boolean = { false },
    /** Where each download's progress and result is kept for the main screen. */
    private val recent: RecentDownloads? = null,
) {
    /** Downloads the post linked in [sharedText]. A failure's reason says whether it will be retried. */
    fun download(sharedText: String, onProgress: (Int?) -> Unit = {}): PhoneDownloadOutcome {
        XPostLink.find(sharedText)?.let { recent?.started(it.url) }
        val result = attempt(sharedText, onProgress)
        val url = XPostLink.find(sharedText)?.url ?: return result.outcome
        recent?.ended(url, result.outcome)
        when (result.waitFor) {
            WaitFor.CONNECTION -> synchronized(this) { save(load().filterNot { it.url == url } + Waiting(url, retries = 0)) }
            WaitFor.LOGIN -> waitForLogin(url)
            // Shared again and saved: it no longer waits for a login.
            WaitFor.NOTHING -> if (result.outcome is PhoneDownloadOutcome.Saved) stopWaitingForLogin(url)
        }
        return result.outcome
    }

    /** Whether some post is waiting for an X login. */
    @Synchronized
    fun hasWaitingForLogin(): Boolean = loadWaitingForLogin().isNotEmpty()

    /**
     * Retries the posts that needed an X login, once the phone has logged in. Each is
     * retried once: [onResult] gets how it ended, and one that still needs a login
     * waits for the next login. One that can't connect waits for the connection.
     */
    fun retryAfterLogin(onProgress: (Int?) -> Unit = {}, onResult: (PhoneDownloadOutcome) -> Unit) {
        for (url in synchronized(this) { loadWaitingForLogin() }) {
            // Taken off the list one at a time, so posts not reached yet if the app is stopped
            // still wait, and a post another call already took isn't downloaded twice.
            if (stopWaitingForLogin(url)) onResult(download(url, onProgress))
        }
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
                PhoneDownloadOutcome.Failed(NO_CONNECTION).let { recent?.ended(waiting.url, it); onResult(it) }
                continue
            }
            // Counted before trying, so a retry Android stops half way (the app killed, the
            // background time used up) still counts, and a post that never finishes gives up.
            replace(waiting, waiting.copy(retries = waiting.retries + 1))
            recent?.started(waiting.url)
            val result = attempt(waiting.url, onProgress)
            // Only tries that failed while the phone said it was online count towards giving up.
            val retries = if (result.online) waiting.retries + 1 else waiting.retries
            val retryAgain = result.waitFor == WaitFor.CONNECTION && retries < MAX_CONNECTION_RETRIES
            replace(waiting, if (retryAgain) waiting.copy(retries = retries) else null)
            if (result.waitFor == WaitFor.LOGIN) waitForLogin(waiting.url)
            val outcome = when {
                retryAgain -> result.outcome // still waiting: shown in the app, not notified
                result.waitFor == WaitFor.CONNECTION -> PhoneDownloadOutcome.Failed(NO_CONNECTION)
                else -> result.outcome
            }
            recent?.ended(waiting.url, outcome)
            if (!retryAgain) onResult(outcome)
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
    private class Attempt(val outcome: PhoneDownloadOutcome, val online: Boolean, val waitFor: WaitFor = WaitFor.NOTHING)

    /** What a failed post waits for before it's retried. */
    private enum class WaitFor { NOTHING, CONNECTION, LOGIN }

    private fun attempt(text: String, onProgress: (Int?) -> Unit): Attempt {
        val first = downloadOnce(text, onProgress)
        val firstFailure = first.outcome as? PhoneDownloadOutcome.Failed ?: return first
        if (Failures.classify(firstFailure.reason, first.online) != FailureKind.OTHER) {
            return failed(firstFailure, first.online)
        }

        updates.updateAfterFailure()
        val second = downloadOnce(text, onProgress)
        val secondFailure = second.outcome as? PhoneDownloadOutcome.Failed ?: return second
        // Refused even by the newest yt-dlp while sending an X login: the login has expired.
        if (second.online && loggedIn() && Failures.rejectsLogin(secondFailure.reason)) {
            return Attempt(PhoneDownloadOutcome.NeedsLogin(secondFailure.reason), second.online, WaitFor.LOGIN)
        }
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
                Attempt(PhoneDownloadOutcome.Failed(WAITING_FOR_CONNECTION), online, WaitFor.CONNECTION)
            FailureKind.NEEDS_LOGIN -> Attempt(PhoneDownloadOutcome.NeedsLogin(failure.reason), online, WaitFor.LOGIN)
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

    // One post URL per line.
    private fun loadWaitingForLogin(): List<String> =
        storage.get(WAITING_FOR_LOGIN).orEmpty().lines().filter { it.isNotEmpty() }

    private fun saveWaitingForLogin(urls: List<String>) {
        storage.put(WAITING_FOR_LOGIN, urls.joinToString("\n").ifEmpty { null })
    }

    @Synchronized
    private fun waitForLogin(url: String) {
        saveWaitingForLogin(loadWaitingForLogin().filterNot { it == url } + url)
    }

    /** Takes [url] off the posts waiting for a login. Returns whether it was waiting. */
    @Synchronized
    private fun stopWaitingForLogin(url: String): Boolean {
        val urls = loadWaitingForLogin()
        if (url !in urls) return false
        saveWaitingForLogin(urls - url)
        return true
    }

    companion object {
        /** How many times a post is retried while the phone says it's online but it still can't connect. */
        const val MAX_CONNECTION_RETRIES = 5

        private const val WAITING = "downloads.waitingForConnection"
        private const val NO_CONNECTION = "No internet connection"
        private const val WAITING_FOR_CONNECTION = "$NO_CONNECTION. XVid will try again when the connection is back"
        private const val WAITING_FOR_LOGIN = "downloads.waitingForLogin"
    }
}
