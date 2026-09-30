package app.xvid.core

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the app shows on its main screen, so a download's result is visible even with notifications off. */
class RecentDownloadsTest {
    @TempDir
    lateinit var workDir: File

    private val engine = FakeEngine()
    private val network = FakeNetwork()
    private val storage = FakeStorage()
    private val clock = FakeClock()
    private lateinit var downloads: RetryingPhoneDownloads

    @BeforeTest
    fun setUp() {
        downloads = newDownloads()
    }

    /** A fresh instance on the same storage, as after the app was restarted. */
    private fun newDownloads() = RetryingPhoneDownloads(
        PhoneDownloads(engine, FakePhoneLibrary(), network, workDir),
        network,
        YtDlpUpdates(FakeUpdater(), storage, clock),
        storage,
        loggedIn = XLogin(storage)::isLoggedIn,
        recent = RecentDownloads(storage, clock),
    )

    private fun recent() = RecentDownloads(storage, clock).list()

    @Test
    fun `a saved download shows as saved`() {
        downloads.download("Look https://x.com/someone/status/1?s=20")

        assertEquals(
            listOf(RecentDownload("https://x.com/someone/status/1", RecentDownload.State.SAVED, "XVid_1.mp4", clock.now())),
            recent(),
        )
    }

    @Test
    fun `a download shows as downloading while it runs`() {
        var whileRunning: List<RecentDownload>? = null
        downloads.download("https://x.com/someone/status/1") { whileRunning = whileRunning ?: recent() }

        assertEquals(listOf(RecentDownload.State.DOWNLOADING), whileRunning?.map { it.state })
    }

    @Test
    fun `a failed download shows why`() {
        engine.failWith = "ERROR: [twitter] 1: No video could be found in this tweet"

        downloads.download("https://x.com/someone/status/1")

        assertEquals(
            listOf(RecentDownload.State.FAILED to "No video could be found in this tweet"),
            recent().map { it.state to it.detail },
        )
    }

    @Test
    fun `a download that needs an X login shows so, then saved once it's retried after logging in`() {
        engine.failWith = "ERROR: [twitter] 1: NSFW tweet requires authentication. Use --cookies"
        downloads.download("https://x.com/someone/status/1")
        assertEquals(listOf(RecentDownload.State.NEEDS_LOGIN), recent().map { it.state })

        engine.failWith = null
        XLogin(storage).logIn("auth_token=secret1; ct0=secret2")
        clock.advanceHours(1)
        newDownloads().retryAfterLogin {}

        assertEquals(
            listOf(RecentDownload("https://x.com/someone/status/1", RecentDownload.State.SAVED, "XVid_1.mp4", clock.now())),
            recent(),
        )
    }

    @Test
    fun `a download waiting for the connection shows so, then how its retry ended`() {
        network.online = false
        downloads.download("https://x.com/someone/status/1")
        assertEquals(
            listOf(RecentDownload.State.FAILED to "No internet connection. XVid will try again when the connection is back"),
            recent().map { it.state to it.detail },
        )

        network.online = true
        downloads.retryWaiting {}

        assertEquals(listOf(RecentDownload.State.SAVED), recent().map { it.state })
    }

    @Test
    fun `newest first, and only the latest few`() {
        repeat(RecentDownloads.MAX + 2) { n ->
            clock.advanceHours(1)
            downloads.download("https://x.com/someone/status/${n + 1}")
        }

        val urls = recent().map { it.url }
        assertEquals(RecentDownloads.MAX, urls.size)
        assertEquals("https://x.com/someone/status/${RecentDownloads.MAX + 2}", urls.first())
        assertEquals("https://x.com/someone/status/3", urls.last())
    }

    @Test
    fun `a post downloaded again moves to the top instead of showing twice`() {
        downloads.download("https://x.com/someone/status/1")
        downloads.download("https://x.com/someone/status/2")
        downloads.download("https://x.com/someone/status/1")

        assertEquals(listOf("https://x.com/someone/status/1", "https://x.com/someone/status/2"), recent().map { it.url })
    }

    @Test
    fun `several videos show how many were saved`() {
        engine.videosPerPost = 3

        downloads.download("https://x.com/someone/status/1")

        assertEquals("3 videos", recent().single().detail)
    }
}
