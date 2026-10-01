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
    // What yt-dlp says when X changes its guest API. It mentions authentication, but it's
    // yt-dlp's own access that broke: a newer yt-dlp is the fix, not an X login.
    private val brokenAccess = listOf(
        "guest token", "could not authenticate you", "bad authentication data", "querying api", "failed to query api",
    )

    // What yt-dlp says when X hides a sensitive post's video from someone logged out.
    private val hiddenWhenLoggedOut = listOf("no video could be found")

    // What X answers when it refuses the login yt-dlp sent: an expired X login, if a newer yt-dlp doesn't help.
    private val rejectedLogin = listOf("could not authenticate you", "bad authentication data")

    // Only wording about the post itself needing an account.
    private val needsLogin = listOf(
        "requires authentication", "only be available when logged in", "not authorized to see",
        "not authorized to view", "protected", "--cookies", "nsfw", "age-restricted",
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
     * [reason] is the failure's readable reason; [online] whether the phone had a
     * connection when the download failed. Broken access wins over login wording,
     * and login and permanent problems win over network wording.
     * HTTP errors other than "gone" (403, 429, 5xx, ...) aren't sorted as no connection:
     * X answering oddly is what a newer yt-dlp may fix.
     */
    fun classify(reason: String, online: Boolean): FailureKind {
        if (!online) return FailureKind.NO_CONNECTION
        val text = reason.lowercase()
        return when {
            brokenAccess.any { it in text } -> FailureKind.OTHER
            needsLogin.any { it in text } -> FailureKind.NEEDS_LOGIN
            permanent.any { it in text } -> FailureKind.PERMANENT
            noConnection.any { it in text } -> FailureKind.NO_CONNECTION
            else -> FailureKind.OTHER
        }
    }

    /**
     * Whether [reason] may only mean the post's video is hidden from someone logged out: X shows
     * sensitive posts to logged-out visitors without their video, so it looks like there's none.
     */
    fun hiddenWhenLoggedOut(reason: String): Boolean = reason.lowercase().let { text -> hiddenWhenLoggedOut.any { it in text } }

    /** Whether [reason] is X refusing the authentication it got, which an expired X login causes. */
    fun rejectsLogin(reason: String): Boolean = reason.lowercase().let { text -> rejectedLogin.any { it in text } }
}
