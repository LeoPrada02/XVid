package app.xvid.core

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XLoginTest {
    @TempDir
    lateinit var workDir: File

    private val engine = FakeEngine()
    private val library = FakePhoneLibrary()
    private val network = FakeNetwork()
    private val storage = FakeStorage()
    private val updater = FakeUpdater()
    private val login = XLogin(storage)
    private lateinit var downloads: RetryingPhoneDownloads

    private val results = mutableListOf<PhoneDownloadOutcome>()

    @BeforeTest
    fun setUp() {
        downloads = newDownloads()
    }

    /** A fresh instance on the same storage, as after the app was restarted. */
    private fun newDownloads() = RetryingPhoneDownloads(
        PhoneDownloads(engine, library, network, workDir, xLogin = XLogin(storage)::cookies),
        network,
        YtDlpUpdates(updater, storage, FakeClock()),
        storage,
        loggedIn = XLogin(storage)::isLoggedIn,
    )

    @Test
    fun `the stored X login is passed to the engine for every phone download`() {
        assertTrue(login.logIn("guest_id=v1%3A1; auth_token=secret1; ct0=secret2"))

        downloads.download("https://x.com/someone/status/1")
        newDownloads().download("https://x.com/someone/status/2")

        val expected = """
            # Netscape HTTP Cookie File
            .x.com	TRUE	/	TRUE	0	guest_id	v1%3A1
            .x.com	TRUE	/	TRUE	0	auth_token	secret1
            .x.com	TRUE	/	TRUE	0	ct0	secret2

        """.trimIndent()
        assertEquals(listOf(expected, expected), engine.requests.map { it.cookies })
    }

    @Test
    fun `a download that needs an X login asks for one and retries after logging in`() {
        engine.failWith = NEEDS_LOGIN_ERROR

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertIs<PhoneDownloadOutcome.NeedsLogin>(outcome)
        assertFalse(downloads.hasWaiting()) // not waiting for the connection

        engine.failWith = null
        login.logIn("auth_token=secret1; ct0=secret2")
        downloads = newDownloads()
        downloads.retryAfterLogin { results += it }

        val saved = assertIs<PhoneDownloadOutcome.Saved>(results.single())
        assertEquals(listOf("XVid_1.mp4"), saved.videos.map { it.name })
        assertEquals(2, engine.requests.size)
        assertTrue(engine.requests.last().cookies.orEmpty().contains("auth_token\tsecret1"))
    }

    @Test
    fun `a download is retried after only one login`() {
        engine.failWith = NEEDS_LOGIN_ERROR
        downloads.download("https://x.com/someone/status/1")
        engine.failWith = null
        login.logIn("auth_token=secret1; ct0=secret2")

        downloads.retryAfterLogin { results += it }
        downloads.retryAfterLogin { results += it }

        assertEquals(1, results.size)
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `a retry after logging in that still needs a login asks again`() {
        engine.failWith = NEEDS_LOGIN_ERROR
        downloads.download("https://x.com/someone/status/1")
        login.logIn("auth_token=expired; ct0=old")

        downloads.retryAfterLogin { results += it }
        assertIs<PhoneDownloadOutcome.NeedsLogin>(results.single())

        engine.failWith = null
        downloads.retryAfterLogin { results += it }
        assertIs<PhoneDownloadOutcome.Saved>(results.last())
    }

    @Test
    fun `a retry after logging in with no connection waits for the connection`() {
        engine.failWith = NEEDS_LOGIN_ERROR
        downloads.download("https://x.com/someone/status/1")
        engine.failWith = null
        login.logIn("auth_token=secret1; ct0=secret2")
        network.online = false

        downloads.retryAfterLogin { results += it }

        assertTrue(downloads.hasWaiting())
        network.online = true
        downloads.retryWaiting { results += it }
        assertIs<PhoneDownloadOutcome.Saved>(results.last())
    }

    @Test
    fun `a waiting download that turns out to need a login is retried after logging in`() {
        network.online = false
        downloads.download("https://x.com/someone/status/1")
        network.online = true
        engine.failWith = NEEDS_LOGIN_ERROR
        downloads.retryWaiting { results += it }
        assertIs<PhoneDownloadOutcome.NeedsLogin>(results.single())

        engine.failWith = null
        login.logIn("auth_token=secret1; ct0=secret2")
        downloads.retryAfterLogin { results += it }

        assertIs<PhoneDownloadOutcome.Saved>(results.last())
    }

    @Test
    fun `an expired X login asks for a new one after updating yt-dlp doesn't help`() {
        login.logIn("auth_token=expired; ct0=old")
        engine.failWith = "ERROR: [twitter] 1: Error(s) while querying API: Could not authenticate you"

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertIs<PhoneDownloadOutcome.NeedsLogin>(outcome)
        assertEquals(1, updater.calls)
        engine.failWith = null
        login.logIn("auth_token=secret1; ct0=secret2")
        downloads.retryAfterLogin { results += it }
        assertIs<PhoneDownloadOutcome.Saved>(results.single())
    }

    @Test
    fun `logged out, X refusing yt-dlp's access is not taken for a login problem`() {
        engine.failWith = "ERROR: [twitter] 1: Error(s) while querying API: Could not authenticate you"

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertEquals(PhoneDownloadOutcome.Failed("Error(s) while querying API: Could not authenticate you"), outcome)
    }

    @Test
    fun `a post that downloads when shared again no longer waits for a login`() {
        engine.failWith = NEEDS_LOGIN_ERROR
        downloads.download("https://x.com/someone/status/1")
        engine.failWith = null
        downloads.download("https://x.com/someone/status/1")

        login.logIn("auth_token=secret1; ct0=secret2")
        downloads.retryAfterLogin { results += it }

        assertTrue(results.isEmpty())
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `the login page's cookies are only a login once they include the session`() {
        assertFalse(login.logIn("guest_id=v1%3A1; ct0=abc"))
        assertFalse(login.logIn("guest_id=v1%3A1; auth_token=secret1"))
        assertFalse(login.isLoggedIn())

        assertTrue(login.logIn("guest_id=v1%3A1; auth_token=secret1; ct0=secret2"))
        assertTrue(login.isLoggedIn())
    }

    @Test
    fun `logging out stops passing the login to the engine`() {
        login.logIn("auth_token=secret1; ct0=secret2")

        login.logOut()
        downloads.download("https://x.com/someone/status/1")

        assertFalse(login.isLoggedIn())
        assertNull(engine.requests.single().cookies)
    }

    @Test
    fun `with no X login the engine gets no cookies`() {
        downloads.download("https://x.com/someone/status/1")

        assertNull(engine.requests.single().cookies)
        assertFalse(login.isLoggedIn())
    }

    private companion object {
        const val NEEDS_LOGIN_ERROR =
            "ERROR: [twitter] 1: NSFW tweet requires authentication. Use --cookies, --cookies-from-browser"
    }
}
