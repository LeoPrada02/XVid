package app.xvid.core

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RetryingPhoneDownloadsTest {
    @TempDir
    lateinit var workDir: File

    private val engine = FakeEngine()
    private val library = FakePhoneLibrary()
    private val network = FakeNetwork()
    private val storage = FakeStorage()
    private val clock = FakeClock()
    private val updater = FakeUpdater()
    private lateinit var downloads: RetryingPhoneDownloads

    private val results = mutableListOf<PhoneDownloadOutcome>()

    @BeforeTest
    fun setUp() {
        downloads = newDownloads()
    }

    /** A fresh instance on the same storage, as after the app was restarted. */
    private fun newDownloads() = RetryingPhoneDownloads(
        PhoneDownloads(engine, library, network, workDir),
        network,
        YtDlpUpdates(updater, storage, clock),
        storage,
    )

    private fun retryWaiting(): Boolean = downloads.retryWaiting { results += it }

    // No connection

    @Test
    fun `with no connection the download fails and waits for the connection`() {
        network.online = false

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertEquals(
            PhoneDownloadOutcome.Failed("No internet connection. XVid will try again when the connection is back"),
            outcome,
        )
        assertTrue(downloads.hasWaiting())
        assertEquals(0, updater.calls)
    }

    @Test
    fun `a waiting download is retried when the connection returns`() {
        network.online = false
        downloads.download("Look https://x.com/someone/status/1?s=20")

        network.online = true
        val stillWaiting = newDownloads().let { downloads = it; retryWaiting() }

        assertFalse(stillWaiting)
        assertFalse(downloads.hasWaiting())
        val saved = assertIs<PhoneDownloadOutcome.Saved>(results.single())
        assertEquals(listOf("XVid_1.mp4"), saved.videos.map { it.name })
        assertEquals("https://x.com/someone/status/1", engine.requests.single().url)
    }

    @Test
    fun `a network error while online also waits for the connection`() {
        engine.failWith = "ERROR: [twitter] 1: Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>"

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertEquals(
            PhoneDownloadOutcome.Failed("No internet connection. XVid will try again when the connection is back"),
            outcome,
        )
        assertTrue(downloads.hasWaiting())
        assertEquals(0, updater.calls)
    }

    @Test
    fun `while still offline nothing is retried or reported`() {
        network.online = false
        downloads.download("https://x.com/someone/status/1")

        assertTrue(retryWaiting())

        assertTrue(results.isEmpty())
        assertTrue(engine.requests.isEmpty())
        assertTrue(downloads.hasWaiting())
    }

    @Test
    fun `the same post shared twice while offline is downloaded once`() {
        network.online = false
        downloads.download("https://x.com/someone/status/1")
        downloads.download("https://twitter.com/someone/status/1/video/1")

        network.online = true
        retryWaiting()

        assertEquals(1, engine.requests.size)
        assertEquals(1, results.size)
    }

    @Test
    fun `a waiting download that fails for another reason is reported and stops waiting`() {
        network.online = false
        downloads.download("https://x.com/someone/status/1")

        network.online = true
        engine.failWith = "ERROR: [twitter] 1: Requested tweet is unavailable"
        val stillWaiting = retryWaiting()

        assertFalse(stillWaiting)
        assertEquals(listOf<PhoneDownloadOutcome>(PhoneDownloadOutcome.Failed("Requested tweet is unavailable")), results)
    }

    @Test
    fun `a download that keeps failing with network errors gives up instead of retrying forever`() {
        engine.failWith = "ERROR: Read timed out."
        downloads.download("https://x.com/someone/status/1")

        var rounds = 0
        while (retryWaiting()) {
            rounds++
            check(rounds < 100) { "retries never stop" }
        }

        assertEquals(RetryingPhoneDownloads.MAX_CONNECTION_RETRIES, engine.requests.size - 1)
        assertEquals(listOf<PhoneDownloadOutcome>(PhoneDownloadOutcome.Failed("No internet connection")), results)
        assertFalse(downloads.hasWaiting())
    }

    @Test
    fun `a retry the app is stopped in the middle of still counts towards giving up`() {
        engine.failWith = "ERROR: Read timed out."
        downloads.download("https://x.com/someone/status/1")
        engine.failWith = null
        engine.interruptWith = Interrupted()

        repeat(RetryingPhoneDownloads.MAX_CONNECTION_RETRIES) {
            runCatching { newDownloads().retryWaiting { results += it } }
        }
        val requests = engine.requests.size
        val stillWaiting = newDownloads().retryWaiting { results += it }

        assertFalse(stillWaiting)
        assertEquals(requests, engine.requests.size) // gave up without trying again
        assertEquals(listOf<PhoneDownloadOutcome>(PhoneDownloadOutcome.Failed("No internet connection")), results)
    }

    // yt-dlp updates

    @Test
    fun `X breaking yt-dlp's access updates yt-dlp instead of asking for a login`() {
        engine.failWith = "ERROR: [twitter] 1: Error(s) while querying API: Could not authenticate you"
        updater.onUpdate = { engine.failWith = null }

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertIs<PhoneDownloadOutcome.Saved>(outcome)
        assertEquals(1, updater.calls)
    }

    @Test
    fun `the Maximum quality setting applies to the retry after an update too`() {
        val downloads = RetryingPhoneDownloads(
            PhoneDownloads(engine, library, network, workDir, maximumQuality = { MaximumQuality.P480 }),
            network,
            YtDlpUpdates(updater, storage, clock),
            storage,
        )
        engine.failWith = "ERROR: [twitter] 1: Unable to extract guest token"

        downloads.download("https://x.com/someone/status/1")

        assertEquals(listOf(MaximumQuality.P480.format, MaximumQuality.P480.format), engine.requests.map { it.format })
    }

    @Test
    fun `each failed download gets its own update before the retry`() {
        engine.failWith = "ERROR: [twitter] 1: Unable to extract guest token"

        downloads.download("https://x.com/someone/status/1")
        downloads.download("https://x.com/someone/status/2")

        assertEquals(2, updater.calls)
    }

    @Test
    fun `a failure a newer yt-dlp might fix updates yt-dlp and retries once`() {
        engine.failWith = "ERROR: [twitter] 1: Unable to extract guest token"
        updater.onUpdate = { engine.failWith = null }

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertIs<PhoneDownloadOutcome.Saved>(outcome)
        assertEquals(1, updater.calls)
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `if the retry after the update fails too, it's reported as a failure`() {
        engine.failWith = "ERROR: [twitter] 1: Unable to extract guest token"

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertEquals(PhoneDownloadOutcome.Failed("Unable to extract guest token"), outcome)
        assertEquals(1, updater.calls)
        assertEquals(2, engine.requests.size)
        assertFalse(downloads.hasWaiting())
    }

    @Test
    fun `if the update itself fails the download is still retried once`() {
        engine.failWith = "ERROR: [twitter] 1: Unable to extract guest token"
        updater.failWith = "GitHub is down"

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertEquals(PhoneDownloadOutcome.Failed("Unable to extract guest token"), outcome)
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `a retry after the update that loses the connection waits for it`() {
        engine.failWith = "ERROR: [twitter] 1: Unable to extract guest token"
        updater.onUpdate = { network.online = false }

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertEquals(
            PhoneDownloadOutcome.Failed("No internet connection. XVid will try again when the connection is back"),
            outcome,
        )
        assertTrue(downloads.hasWaiting())
    }

    // Failures that retrying can't fix

    @Test
    fun `permanent errors are not retried`() {
        engine.failWith = "ERROR: [twitter] 1: Requested tweet is unavailable"

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertEquals(PhoneDownloadOutcome.Failed("Requested tweet is unavailable"), outcome)
        assertEquals(1, engine.requests.size)
        assertEquals(0, updater.calls)
        assertFalse(downloads.hasWaiting())
    }

    @Test
    fun `needs-login failures ask for an X login and are not retried until then`() {
        engine.failWith = "ERROR: [twitter] 1: NSFW tweet requires authentication. Use --cookies, --cookies-from-browser"

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertEquals(
            PhoneDownloadOutcome.NeedsLogin("NSFW tweet requires authentication. Use --cookies, --cookies-from-browser"),
            outcome,
        )
        assertEquals(1, engine.requests.size)
        assertEquals(0, updater.calls)
        assertFalse(downloads.hasWaiting())
    }

    @Test
    fun `a successful download passes straight through`() {
        val outcome = downloads.download("https://x.com/someone/status/1")

        assertIs<PhoneDownloadOutcome.Saved>(outcome)
        assertEquals(1, engine.requests.size)
        assertEquals(0, updater.calls)
    }
}
