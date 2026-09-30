package app.xvid.core

/** What kind of failure a failed phone download was, which decides whether and how it's retried. */
enum class FailureKind {
    /** The phone had no working connection: retry when the connection is back. */
    NO_CONNECTION,

    /** The post needs an X login: retrying without one can't help. */
    NEEDS_LOGIN,

    /** Retrying can't help: deleted post, no video in it, the phone library can't take it, ... */
    PERMANENT,

    /** Anything else, which could be X having changed and broken yt-dlp: update yt-dlp and retry once. */
    OTHER,
}

/** Sorts the reason a phone download failed into a [FailureKind]. */
object Failures {
    private val needsLogin = listOf(
        "requires authentication", "authenticat", "not authorized", "protected", "log in", "login", "sign in",
        "--cookies", "nsfw", "age-restricted",
    )

    private val permanent = listOf(
        "isn't a link to an x post", "no video in this post", "no video could be found",
        "tweet unavailable", "tweet is unavailable", "post unavailable", "post is unavailable", "video unavailable",
        "deleted", "does not exist", "doesn't exist", "http error 404", "http error 410", "suspended",
        "couldn't save the video", "unsupported url",
    )

    private val noConnection = listOf(
        "no internet connection", "urlopen error",
        "failed to resolve", "name resolution", "no address associated", "getaddrinfo", "network is unreachable",
        "unable to connect", "connection refused", "connection reset", "connection aborted", "remote end closed",
        "timed out", "timeout",
    )

    /**
     * [reason] is the failure's readable reason; [online] whether the phone has a
     * connection now. Login and permanent problems win over network wording.
     * HTTP errors other than "gone" (403, 429, 5xx, ...) aren't sorted as no connection:
     * X answering oddly is what a newer yt-dlp may fix.
     */
    fun classify(reason: String, online: Boolean): FailureKind {
        if (!online) return FailureKind.NO_CONNECTION
        val text = reason.lowercase()
        return when {
            needsLogin.any { it in text } -> FailureKind.NEEDS_LOGIN
            permanent.any { it in text } -> FailureKind.PERMANENT
            noConnection.any { it in text } -> FailureKind.NO_CONNECTION
            else -> FailureKind.OTHER
        }
    }
}
