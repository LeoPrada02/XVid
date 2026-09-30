package app.xvid.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FailuresTest {
    private fun kindOf(reason: String, online: Boolean = true) = Failures.classify(reason, online)

    @Test
    fun `any failure while the phone is offline is no connection`() {
        assertEquals(FailureKind.NO_CONNECTION, kindOf("No internet connection", online = false))
        assertEquals(FailureKind.NO_CONNECTION, kindOf("Something odd happened", online = false))
    }

    @Test
    fun `network errors are no connection even when the phone thinks it's online`() {
        listOf(
            "No internet connection",
            "Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>",
            "Unable to download JSON metadata: <urlopen error [Errno -3] Temporary failure in name resolution>",
            "Failed to resolve 'x.com'",
            "Read timed out. (read timeout=20.0)",
            "[Errno 101] Network is unreachable",
            "Connection reset by peer",
        ).forEach { assertEquals(FailureKind.NO_CONNECTION, kindOf(it), it) }
    }

    @Test
    fun `posts that need an X login are needs login`() {
        listOf(
            "NSFW tweet requires authentication. Use --cookies, --cookies-from-browser, --username and --password",
            "Requested tweet may only be available when logged in",
            "Sorry, you are not authorized to see this status",
            "You are not authorized to view this protected tweet",
        ).forEach { assertEquals(FailureKind.NEEDS_LOGIN, kindOf(it), it) }
    }

    @Test
    fun `X breaking yt-dlp's own access isn't a login problem`() {
        // What yt-dlp says when X changes its guest API: a newer yt-dlp is the fix, not an X login.
        listOf(
            "Error(s) while querying API: Could not authenticate you",
            "Bad guest token",
            "Unable to obtain guest token",
            "Failed to query API: HTTP Error 401: Unauthorized",
            "Error(s) while querying API: Bad Authentication data",
        ).forEach { assertEquals(FailureKind.OTHER, kindOf(it), it) }
    }

    @Test
    fun `deleted posts, posts without video and save problems are permanent`() {
        listOf(
            "No video could be found in this tweet",
            "No video in this post",
            "That isn't a link to an X post",
            "Tweet unavailable",
            "This post has been deleted",
            "Unable to download webpage: HTTP Error 404: Not Found",
            "User has been suspended",
            "Couldn't save the video: Storage is full",
        ).forEach { assertEquals(FailureKind.PERMANENT, kindOf(it), it) }
    }

    @Test
    fun `anything else might be fixed by a newer yt-dlp`() {
        listOf(
            "Unable to extract guest token",
            "Unable to download JSON metadata: HTTP Error 403: Forbidden",
            "The download failed: KeyError('legacy')",
            "The download failed",
            "Unable to download webpage: HTTP Error 503: Service Unavailable",
        ).forEach { assertEquals(FailureKind.OTHER, kindOf(it), it) }
    }
}
